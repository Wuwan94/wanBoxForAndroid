//go:build android && arm64

// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"runtime"
	"syscall"
	"unsafe"
)

const (
	linuxArm64BPFSyscall = 280
	bpfMapLookupElem     = 1
	bpfMapDeleteElem     = 3
	bpfObjGet            = 7
	bpfObjGetInfoByFD    = 15
)

type colorOSGoogleBPFHandles struct {
	programFD int
	mapFD     int
}

// ColorOS 16 owns these shared socket filters. Keep the filters and maps in
// place; only remove a verified Google UID whose value is exactly the reject bit.
// Inspect every present pin before any map entry is changed.
func repairColorOSGoogleFirewall(uids map[uint32]bool, beforeMutation func() error) (int, error) {
	if len(uids) == 0 {
		return 0, nil
	}
	handles := make(map[string]colorOSGoogleBPFHandles, 3)
	defer func() {
		for _, handle := range handles {
			_ = syscall.Close(handle.programFD)
			_ = syscall.Close(handle.mapFD)
		}
	}()
	return repairColorOSGoogleMaps([]string{"wlan", "qcom", "mtk"}, func(kind string) error {
		handle, err := openVerifiedColorOSGoogleMap(kind)
		if err != nil {
			return err
		}
		handles[kind] = handle
		return nil
	}, beforeMutation, func(kind string) (int, error) {
		return repairColorOSGoogleMapEntries(handles[kind].mapFD, uids, beforeMutation)
	})
}

func openVerifiedColorOSGoogleMap(kind string) (colorOSGoogleBPFHandles, error) {
	var programPath, mapPath string
	switch kind {
	case "wlan", "qcom", "mtk":
		programPath = "/sys/fs/bpf/prog_oplus-netd_skfilter_reject_" + kind + "_uid"
		mapPath = "/sys/fs/bpf/map_oplus-netd_app_" + kind + "_socket_uid_limit_map"
	default:
		return colorOSGoogleBPFHandles{}, errors.New("unknown_coloros_bpf_map")
	}
	programFD, err := openPinnedColorOSBPF(programPath)
	if err != nil {
		return colorOSGoogleBPFHandles{}, err
	}
	mapFD, err := openPinnedColorOSBPF(mapPath)
	if err != nil {
		_ = syscall.Close(programFD)
		return colorOSGoogleBPFHandles{}, err
	}

	var mapInfo struct{ Type, ID, KeySize, ValueSize uint32 }
	if err := colorOSBPFInfo(mapFD, unsafe.Pointer(&mapInfo), unsafe.Sizeof(mapInfo)); err != nil {
		_ = syscall.Close(programFD)
		_ = syscall.Close(mapFD)
		return colorOSGoogleBPFHandles{}, err
	}
	var associatedMapID uint32
	// Linux UAPI struct bpf_prog_info prefix through the map_ids pointer.
	programInfo := struct {
		Type, ID            uint32
		Tag                 [8]byte
		JitedLen, XlatedLen uint32
		Jited, Xlated       uint64
		LoadTime            uint64
		CreatedByUID        uint32
		MapCount            uint32
		MapIDs              unsafe.Pointer
	}{MapCount: 1, MapIDs: unsafe.Pointer(&associatedMapID)}
	if err := colorOSBPFInfo(programFD, unsafe.Pointer(&programInfo), unsafe.Sizeof(programInfo)); err != nil {
		_ = syscall.Close(programFD)
		_ = syscall.Close(mapFD)
		return colorOSGoogleBPFHandles{}, err
	}
	if !colorOSGoogleBPFCompatible(programInfo.Type, programInfo.Tag, programInfo.MapCount,
		associatedMapID, mapInfo.Type, mapInfo.ID, mapInfo.KeySize, mapInfo.ValueSize) {
		_ = syscall.Close(programFD)
		_ = syscall.Close(mapFD)
		return colorOSGoogleBPFHandles{}, errors.New("unrecognized_coloros_bpf_layout")
	}
	return colorOSGoogleBPFHandles{programFD: programFD, mapFD: mapFD}, nil
}

func repairColorOSGoogleMapEntries(mapFD int, uids map[uint32]bool, beforeMutation func() error) (int, error) {
	return repairColorOSGoogleEntries(uids, func(uid uint32) (uint32, error) {
		var value uint32
		attr := struct {
			FD, _ uint32
			Key   unsafe.Pointer
			Value unsafe.Pointer
		}{FD: uint32(mapFD), Key: unsafe.Pointer(&uid), Value: unsafe.Pointer(&value)}
		_, err := colorOSBPF(bpfMapLookupElem, unsafe.Pointer(&attr), unsafe.Sizeof(attr))
		runtime.KeepAlive(&uid)
		runtime.KeepAlive(&value)
		return value, err
	}, func(uid uint32) error {
		attr := struct {
			FD, _ uint32
			Key   unsafe.Pointer
			Value unsafe.Pointer
		}{FD: uint32(mapFD), Key: unsafe.Pointer(&uid)}
		_, err := colorOSBPF(bpfMapDeleteElem, unsafe.Pointer(&attr), unsafe.Sizeof(attr))
		runtime.KeepAlive(&uid)
		return err
	}, beforeMutation)
}

func openPinnedColorOSBPF(path string) (int, error) {
	name, err := syscall.BytePtrFromString(path)
	if err != nil {
		return -1, err
	}
	attr := struct{ Path *byte }{Path: name}
	fd, err := colorOSBPF(bpfObjGet, unsafe.Pointer(&attr), unsafe.Sizeof(attr))
	runtime.KeepAlive(name)
	if err != nil {
		return -1, err
	}
	return int(fd), nil
}

func colorOSBPFInfo(fd int, info unsafe.Pointer, size uintptr) error {
	attr := struct {
		FD, Length uint32
		Info       unsafe.Pointer
	}{uint32(fd), uint32(size), info}
	_, err := colorOSBPF(bpfObjGetInfoByFD, unsafe.Pointer(&attr), unsafe.Sizeof(attr))
	runtime.KeepAlive(info)
	return err
}

func colorOSBPF(command uintptr, attr unsafe.Pointer, size uintptr) (uintptr, error) {
	result, _, errno := syscall.Syscall(linuxArm64BPFSyscall, command, uintptr(attr), size)
	runtime.KeepAlive(attr)
	if errno != 0 {
		return 0, errno
	}
	return result, nil
}
