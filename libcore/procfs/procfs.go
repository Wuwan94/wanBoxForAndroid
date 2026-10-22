package procfs

import (
	"bufio"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"unsafe"

	N "github.com/sagernet/sing/common/network"
)

var (
	netIndexOfLocal = -1
	netIndexOfUid   = -1
	nativeEndian    binary.ByteOrder
)

func init() {
	var x uint32 = 0x01020304
	if *(*byte)(unsafe.Pointer(&x)) == 0x01 {
		nativeEndian = binary.BigEndian
	} else {
		nativeEndian = binary.LittleEndian
	}
}

func ResolveSocketByProcSearch(network string, source, _ netip.AddrPort) int32 {
	if netIndexOfLocal < 0 || netIndexOfUid < 0 || !source.IsValid() {
		return -1
	}

	var tablePrefix string
	switch network {
	case N.NetworkTCP:
		tablePrefix = "/proc/net/tcp"
	case N.NetworkUDP:
		tablePrefix = "/proc/net/udp"
	default:
		return -1
	}

	addr := source.Addr().Unmap()
	sIP := addr.AsSlice()
	if len(sIP) == 0 {
		return -1
	}

	var port [2]byte
	binary.BigEndian.PutUint16(port[:], source.Port())
	portHex := hex.EncodeToString(port[:])
	local := fmt.Sprintf("%s:%s", hex.EncodeToString(nativeEndianIP(sIP)), portHex)
	wildcard := fmt.Sprintf("%s:%s", hex.EncodeToString(make([]byte, len(sIP))), portHex)

	type tableQuery struct {
		path       string
		candidates map[string]int
	}
	tables := []tableQuery{}
	if addr.Is4() {
		// IPv4 packets can be owned by a dual-stack AF_INET6 socket. Search
		// both proc tables and prefer exact bindings over either wildcard.
		mappedIP := make(net.IP, net.IPv6len)
		mappedIP[10], mappedIP[11] = 0xff, 0xff
		copy(mappedIP[12:], sIP)
		mappedLocal := fmt.Sprintf("%s:%s", hex.EncodeToString(nativeEndianIP(mappedIP)), portHex)
		wildcard6 := fmt.Sprintf("%s:%s", hex.EncodeToString(make([]byte, net.IPv6len)), portHex)
		tables = append(tables,
			tableQuery{tablePrefix, map[string]int{strings.ToUpper(local): 4, strings.ToUpper(wildcard): 2}},
			tableQuery{tablePrefix + "6", map[string]int{strings.ToUpper(mappedLocal): 3, strings.ToUpper(wildcard6): 1}},
		)
	} else {
		tables = append(tables, tableQuery{tablePrefix + "6", map[string]int{strings.ToUpper(local): 4, strings.ToUpper(wildcard): 2}})
	}

	bestUID, bestPriority, ambiguous := int32(-1), -1, false
	for _, table := range tables {
		file, err := os.Open(table.path)
		if err != nil {
			continue
		}
		uid, priority, tableAmbiguous := resolveSocketOwner(file, table.candidates)
		_ = file.Close()
		if priority < 0 {
			continue
		}
		if priority > bestPriority {
			bestUID, bestPriority, ambiguous = uid, priority, tableAmbiguous
		} else if priority == bestPriority {
			if tableAmbiguous || uid != bestUID {
				ambiguous = true
			}
		}
	}
	if ambiguous {
		return -1
	}
	return bestUID
}

// resolveSocketOwner prefers the most specific address candidate. UDP sockets
// commonly bind to 0.0.0.0 or :: while packets carry the selected interface
// address. Conflicting owners at the same specificity are ambiguous and must
// not be guessed.
func resolveSocketOwner(reader io.Reader, candidates map[string]int) (int32, int, bool) {
	rows := bufio.NewReader(reader)
	bestUID, bestPriority, ambiguous := int32(-1), -1, false

	for {
		row, _, err := rows.ReadLine()
		if err != nil {
			break
		}
		fields := strings.Fields(string(row))
		if len(fields) <= netIndexOfLocal || len(fields) <= netIndexOfUid {
			continue
		}
		priority, matches := candidates[strings.ToUpper(fields[netIndexOfLocal])]
		if !matches {
			continue
		}
		uid, err := strconv.Atoi(fields[netIndexOfUid])
		if err != nil || uid < 0 {
			continue
		}
		if priority > bestPriority {
			bestUID, bestPriority, ambiguous = int32(uid), priority, false
		} else if priority == bestPriority && int32(uid) != bestUID {
			ambiguous = true
		}
	}
	return bestUID, bestPriority, ambiguous
}

func nativeEndianIP(ip net.IP) []byte {
	result := make([]byte, len(ip))

	for i := 0; i < len(ip); i += 4 {
		value := binary.BigEndian.Uint32(ip[i:])

		nativeEndian.PutUint32(result[i:], value)
	}

	return result
}

func init() {
	file, err := os.Open("/proc/net/tcp")
	if err != nil {
		return
	}

	defer file.Close()

	reader := bufio.NewReader(file)

	header, _, err := reader.ReadLine()
	if err != nil {
		return
	}

	columns := strings.Fields(string(header))

	var txQueue, rxQueue, tr, tmWhen bool

	for idx, col := range columns {
		offset := 0

		if txQueue && rxQueue {
			offset--
		}

		if tr && tmWhen {
			offset--
		}

		switch col {
		case "tx_queue":
			txQueue = true
		case "rx_queue":
			rxQueue = true
		case "tr":
			tr = true
		case "tm->when":
			tmWhen = true
		case "local_address":
			netIndexOfLocal = idx + offset
		case "uid":
			netIndexOfUid = idx + offset
		}
	}
}
