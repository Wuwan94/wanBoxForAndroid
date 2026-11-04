// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// App database changes are performed by Room, never by the root module. A journal
// blocks reconnect/upgrade until the App has committed its own backup transaction.
//
// @author 雾晚: 归档必须保留最近若干份供回滚，但**不能无限增长**。此前每调用一次
// PrepareDataUpdate 就 MkdirTemp 一份且从无清理（已复现缺陷 B8）。现在保留最近
// maxDataResetArchives 份，清理时绝不触碰 journal 正在使用的那一份。
const maxDataResetArchives = 5

var dataEntries = []string{"generations", "runtime", "current", "previous", "state.json", "boot.json", "events.json"}

type dataReset struct {
	Archive string `json:"archive"`
}

func (r *Runtime) resetJournal() (dataReset, error) {
	var j dataReset
	if e := readJSON(r.path("data-reset.json"), &j, 4096); e != nil {
		return j, e
	}
	if !strings.HasPrefix(j.Archive, "data-reset-") || filepath.Base(j.Archive) != j.Archive {
		return j, errors.New("data_archive_invalid")
	}
	info, e := os.Lstat(r.path("archives", j.Archive))
	if e != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return j, errors.New("data_archive_invalid")
	}
	return j, nil
}
// pruneDataResetArchives keeps the newest archives and removes older ones.
// Only entries directly under archives/ with the data-reset- prefix are touched;
// symlinks and non-directories are rejected instead of being followed.
func (r *Runtime) pruneDataResetArchives(active string) error {
	dir := r.path("archives")
	entries, e := os.ReadDir(dir)
	if os.IsNotExist(e) {
		return nil
	}
	if e != nil {
		return e
	}
	type candidate struct {
		name string
		mod  time.Time
	}
	var list []candidate
	for _, entry := range entries {
		name := entry.Name()
		if !strings.HasPrefix(name, "data-reset-") || name == active {
			continue
		}
		info, e := os.Lstat(filepath.Join(dir, name))
		if e != nil {
			return e
		}
		if info.Mode()&os.ModeSymlink != 0 || !info.IsDir() {
			return errors.New("unsafe_data_path")
		}
		list = append(list, candidate{name, info.ModTime()})
	}
	keep := maxDataResetArchives
	if active != "" {
		keep-- // the in-flight archive must never be removed
	}
	if len(list) <= keep {
		return nil
	}
	sort.Slice(list, func(i, j int) bool {
		if list[i].mod.Equal(list[j].mod) {
			return list[i].name > list[j].name
		}
		return list[i].mod.After(list[j].mod)
	})
	for _, c := range list[keep:] {
		if e := os.RemoveAll(filepath.Join(dir, c.name)); e != nil {
			return e
		}
	}
	return syncDir(dir)
}

func (r *Runtime) PrepareDataUpdate(ctx context.Context) error {
	return r.withDataLock(func() error {
		if _, e := os.Lstat(r.path("data-reset.json")); e == nil {
			return r.completeDataMove()
		} else if !os.IsNotExist(e) {
			return e
		}
		if e := ctx.Err(); e != nil {
			return e
		}
		if e := r.stop(ctx); e != nil {
			return e
		}
		if e := privateDirectory(r.path("archives")); e != nil {
			return e
		}
		// No journal exists at this point, so every archive is historical.
		if e := r.pruneDataResetArchives(""); e != nil {
			return e
		}
		archive, e := os.MkdirTemp(r.path("archives"), "data-reset-")
		if e != nil {
			return e
		}
		if e = jsonWrite(r.path("data-reset.json"), dataReset{filepath.Base(archive)}); e != nil {
			return e
		}
		return r.completeDataMove()
	})
}
func (r *Runtime) completeDataMove() error {
	j, e := r.resetJournal()
	if e != nil {
		return e
	}
	for _, name := range dataEntries {
		dest := r.path("archives", j.Archive, name)
		if _, e = os.Lstat(dest); e == nil {
			continue
		} else if !os.IsNotExist(e) {
			return e
		}
		info, e := os.Lstat(r.path(name))
		if os.IsNotExist(e) {
			continue
		}
		if e != nil {
			return e
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return errors.New("unsafe_data_path")
		}
		if e = os.Rename(r.path(name), dest); e != nil {
			return e
		}
		if e = syncDir(r.Root); e != nil {
			return e
		}
		if e = syncDir(filepath.Dir(dest)); e != nil {
			return e
		}
	}
	return nil
}
func (r *Runtime) FinishDataUpdate() error {
	return r.withDataLock(func() error {
		if _, e := r.resetJournal(); e != nil {
			if os.IsNotExist(e) {
				return nil
			}
			return e
		}
		if e := r.completeDataMove(); e != nil {
			return e
		}
		// init() creates empty runtime/generations; old ones are safely archived.
		if e := os.Remove(r.path("data-reset.json")); e != nil {
			return e
		}
		// Journal is gone: all remaining archives are historical and bounded.
		if e := r.pruneDataResetArchives(""); e != nil {
			return e
		}
		return syncDir(r.Root)
	})
}
func (r *Runtime) RollbackDataUpdate() error {
	return r.withDataLock(func() error {
		j, e := r.resetJournal()
		if e != nil {
			if os.IsNotExist(e) {
				return nil
			}
			return e
		}
		for _, name := range dataEntries {
			source := r.path("archives", j.Archive, name)
			if _, e = os.Lstat(source); os.IsNotExist(e) {
				continue
			} else if e != nil {
				return e
			}
			if _, e = os.Lstat(r.path(name)); e == nil {
				// Only empty directories introduced by init are allowed. No recursive delete.
				if name != "runtime" && name != "generations" {
					return errors.New("data_restore_conflict")
				}
				if e = os.Remove(r.path(name)); e != nil {
					return errors.New("data_restore_conflict")
				}
			} else if !os.IsNotExist(e) {
				return e
			}
			if e = os.Rename(source, r.path(name)); e != nil {
				return e
			}
			if e = syncDir(r.Root); e != nil {
				return e
			}
		}
		if e = os.Remove(r.path("data-reset.json")); e != nil {
			return e
		}
		return syncDir(r.Root)
	})
}
