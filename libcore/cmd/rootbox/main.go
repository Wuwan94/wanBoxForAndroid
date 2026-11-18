// @author 雾晚
package main

import (
	"encoding/json"
	"fmt"
	"libcore"
	"os"
	"strconv"
)

func main() {
	if len(os.Args) == 4 && os.Args[1] == "--check" {
		if err := libcore.CheckRootConfig(os.Args[2], os.Args[3]); err != nil {
			fmt.Fprintln(os.Stderr, "core_config_invalid")
			os.Exit(1)
		}
		return
	}
	// B5（N1）：短生命周期测试会话。stdout 只含结果 JSON，诊断写 stderr。
	if len(os.Args) == 6 && os.Args[1] == "--url-test" {
		timeoutMs, err := strconv.Atoi(os.Args[5])
		if err != nil {
			fmt.Fprintln(os.Stderr, "invalid url-test timeout")
			os.Exit(2)
		}
		delay, err := libcore.RunRootURLTest(os.Args[2], os.Args[3], os.Args[4], timeoutMs)
		if err != nil {
			fmt.Fprintln(os.Stderr, "url_test_failed")
			os.Exit(1)
		}
		out, _ := json.Marshal(struct {
			DelayMs int32 `json:"delayMs"`
		}{delay})
		fmt.Println(string(out))
		return
	}
	if len(os.Args) == 5 && os.Args[1] == "--speed-test" {
		raw, err := os.ReadFile(os.Args[4])
		if err != nil {
			fmt.Fprintln(os.Stderr, "invalid speed-test params")
			os.Exit(2)
		}
		var params libcore.SpeedTestParams
		if err := json.Unmarshal(raw, &params); err != nil {
			fmt.Fprintln(os.Stderr, "invalid speed-test params")
			os.Exit(2)
		}
		result, err := libcore.RunRootSpeedTest(os.Args[2], os.Args[3], params)
		if err != nil {
			fmt.Fprintln(os.Stderr, "speed_test_failed")
			os.Exit(1)
		}
		out, _ := json.Marshal(result)
		fmt.Println(string(out))
		return
	}
	if len(os.Args) != 7 {
		fmt.Fprintln(os.Stderr, "usage: rootbox CONFIG ASSETS PID READY STOP SUPERVISOR_PID")
		os.Exit(2)
	}
	supervisorPID, err := strconv.Atoi(os.Args[6])
	if err != nil || supervisorPID <= 1 {
		fmt.Fprintln(os.Stderr, "invalid supervisor PID")
		os.Exit(2)
	}
	if err := libcore.RunRootBox(os.Args[1], os.Args[2], os.Args[3], os.Args[4], os.Args[5], supervisorPID); err != nil {
		fmt.Fprintln(os.Stderr, "core_runtime_failed")
		os.Exit(1)
	}
}
