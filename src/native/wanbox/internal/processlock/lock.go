package processlock

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// ErrBusy 表示目标文件已被其他进程持有。
var ErrBusy = errors.New("跨进程锁正忙")

// Lock 表示由操作系统维护生命周期的非阻塞文件锁。
type Lock struct {
	file       *os.File
	once       sync.Once
	releaseErr error
}

// TryAcquire 尝试获取文件锁；进程退出时操作系统会自动释放锁。
func TryAcquire(path string) (*Lock, error) {
	return acquire(context.Background(), path, false)
}

// Acquire 等待文件锁，等待期间遵循调用方的取消和截止时间。
func Acquire(ctx context.Context, path string) (*Lock, error) {
	return acquire(ctx, path, true)
}

func acquire(ctx context.Context, path string, wait bool) (*Lock, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, err
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, err
	}
	for {
		err := tryLockFile(file)
		if err == nil {
			if err := ctx.Err(); err != nil {
				return nil, errors.Join(err, unlockFile(file), file.Close())
			}
			return &Lock{file: file}, nil
		}
		if !lockFileBusy(err) {
			return nil, errors.Join(err, file.Close())
		}
		if !wait {
			return nil, errors.Join(ErrBusy, file.Close())
		}
		timer := time.NewTimer(25 * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil, errors.Join(ctx.Err(), file.Close())
		case <-timer.C:
		}
	}
}

// Release 释放文件锁；重复调用不会重复解锁或关闭文件。
func (lock *Lock) Release() error {
	if lock == nil || lock.file == nil {
		return nil
	}
	lock.once.Do(func() {
		lock.releaseErr = errors.Join(unlockFile(lock.file), lock.file.Close())
	})
	return lock.releaseErr
}
