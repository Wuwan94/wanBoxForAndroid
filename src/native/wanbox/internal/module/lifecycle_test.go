package module

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/Wuwan94/wanBoxForAndroid/src/native/wanbox/internal/paths"
)

func TestBootCompletedReadUsesAndroidSystemCommand(t *testing.T) {
	command := newBootCompletedReadCommand(context.Background())
	want := []string{"/system/bin/getprop", "sys.boot_completed"}
	if !reflect.DeepEqual(command.Args, want) {
		t.Fatalf("boot wait command args = %v, want %v", command.Args, want)
	}
}

func TestWaitForBootCompletedRequiresExactReadyProperty(t *testing.T) {
	states := []string{"", "0", "2", " 1\n"}
	reads := 0
	err := waitForBootCompleted(context.Background(), func(context.Context) (string, error) {
		state := states[reads]
		reads++
		return state, nil
	}, time.Millisecond)
	if err != nil || reads != len(states) {
		t.Fatalf("启动等待结果 = %v，读取次数 = %d", err, reads)
	}
}

func TestWaitForBootCompletedReturnsImmediatelyWhenReady(t *testing.T) {
	err := waitForBootCompleted(context.Background(), func(context.Context) (string, error) {
		return "1", nil
	}, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
}

func TestWaitForBootCompletedPropagatesReadFailure(t *testing.T) {
	want := errors.New("fixture property failure")
	err := waitForBootCompleted(context.Background(), func(context.Context) (string, error) {
		return "", want
	}, time.Hour)
	if !errors.Is(err, want) {
		t.Fatalf("属性读取错误未保留: %v", err)
	}
}

func TestWaitForBootCompletedHonorsCancellationAndDeadline(t *testing.T) {
	for _, beforeRead := range []bool{false, true} {
		ctx, cancel := context.WithCancel(context.Background())
		if beforeRead {
			cancel()
		}
		reads := 0
		err := waitForBootCompleted(ctx, func(context.Context) (string, error) {
			reads++
			cancel()
			return "0", nil
		}, time.Hour)
		cancel()
		if !errors.Is(err, context.Canceled) || beforeRead && reads != 0 {
			t.Fatalf("取消未生效: %v，读取次数 %d", err, reads)
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Millisecond)
	defer cancel()
	err := waitForBootCompleted(ctx, func(context.Context) (string, error) {
		return "0", nil
	}, time.Hour)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("超时未生效: %v", err)
	}
}

func TestCoreLogCommandCapturesOutputAfterParentHandleCloses(t *testing.T) {
	if os.Getenv("WANBOX_TEST_CORE_LOG_CHILD") == "1" {
		deadline := time.Now().Add(5 * time.Second)
		for {
			if _, err := os.Stat(os.Getenv("WANBOX_TEST_CORE_LOG_SIGNAL")); err == nil {
				break
			}
			if time.Now().After(deadline) {
				os.Exit(3)
			}
			time.Sleep(10 * time.Millisecond)
		}
		fmt.Println("fixture stdout")
		fmt.Fprintln(os.Stderr, "FATAL[0000] startup: fixture error")
		panic("fixture panic")
	}
	options := NewOptions(t.TempDir())
	options.SingBoxPath = os.Args[0]
	options.SingBoxDir = options.ModuleDir
	path, err := LogFile(options, "core")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(options.LogDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("previous core\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	prepared := PrepareResult{Providers: "providers.json", Outbounds: "outbounds.json", Inbound: "inbound.json"}
	command, file, err := newSingBoxCommand(options, prepared)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	want := []string{os.Args[0], "run", "--disable-color", "-c", paths.SingBoxConfig(options.SingBoxDir), "-c", prepared.Providers, "-c", prepared.Outbounds, "-c", prepared.Inbound}
	if !slices.Equal(command.Args, want) || command.Stdout != file || command.Stderr != file {
		t.Fatalf("核心参数或继承句柄异常: %v", command.Args)
	}
	signal := filepath.Join(options.ModuleDir, "continue")
	command.Args = []string{os.Args[0], "-test.run=^TestCoreLogCommandCapturesOutputAfterParentHandleCloses$"}
	command.Env = append(os.Environ(), "WANBOX_TEST_CORE_LOG_CHILD=1", "WANBOX_TEST_CORE_LOG_SIGNAL="+signal)
	if err := command.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = command.Process.Kill() })
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(signal, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := command.Wait(); err == nil {
		t.Fatal("异常核心返回成功")
	}
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, marker := range []string{"previous core", "fixture stdout", "FATAL[0000] startup: fixture error", "panic: fixture panic"} {
		if strings.Count(string(content), marker) != 1 {
			t.Fatalf("核心输出丢失或重复 %q: %s", marker, content)
		}
	}
}
