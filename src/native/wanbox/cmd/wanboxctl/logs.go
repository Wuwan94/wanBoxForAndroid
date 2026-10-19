package main

import (
	"context"
	"fmt"
	"os"

	moduleapp "github.com/Wuwan94/wanBoxForAndroid/src/native/wanbox/internal/module"
)

func (c *cli) logs(_ context.Context, args []string) error {
	if len(args) == 0 {
		args = []string{"show"}
	}
	flags := newFlagSet("logs")
	options := c.options
	flags.StringVar(&options.ManagerVersion, "manager-version", "unknown", "Android 管理器版本")
	flags.StringVar(&options.ManagerVersionCode, "manager-version-code", "unknown", "Android 管理器版本号")
	lines := flags.Int("lines", 200, "显示行数")
	output := flags.String("output", "/sdcard/Download/wanbox-diagnostics.tar.gz", "诊断包路径")
	format := flags.String("format", "json", "输出格式")
	if err := flags.Parse(args[1:]); err != nil {
		return err
	}
	action := args[0]
	positionals := flags.Args()
	kind := "service"
	if len(positionals) > 0 {
		kind = positionals[0]
	}
	switch action {
	case "show":
		snapshot, err := moduleapp.ReadLog(options, kind, *lines)
		if err != nil {
			return err
		}
		if *format == "text" {
			fmt.Fprint(os.Stdout, snapshot.Content)
			return nil
		}
		writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "logs.show", Message: "日志内容", Data: snapshot})
	case "clear":
		if err := moduleapp.ClearLog(options, kind); err != nil {
			return err
		}
		writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "logs.cleared", Message: "日志已清空", Data: map[string]string{"kind": kind}})
	case "export":
		if len(positionals) > 0 {
			*output = positionals[0]
		}
		if err := moduleapp.ExportLogs(options, *output); err != nil {
			return err
		}
		writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "logs.exported", Message: "诊断包已导出", Data: map[string]string{"path": *output}})
	default:
		return fmt.Errorf("未知 logs 操作 %q", action)
	}
	return nil
}
