package main

import (
	"context"
	"errors"
	"fmt"
	"os"

	moduleapp "github.com/Wuwan94/wanBoxForAndroid/src/native/wanbox/internal/module"
)

func (c *cli) config(ctx context.Context, args []string) error {
	if len(args) == 0 {
		return errors.New("缺少 config 操作")
	}
	flags := newFlagSet("config")
	revision := flags.String("revision", "", "读取配置时返回的 revision，阻止覆盖并发修改")
	if err := flags.Parse(args[1:]); err != nil {
		return err
	}
	options := c.options
	action := args[0]
	positionals := flags.Args()
	switch action {
	case "list":
		data, err := moduleapp.ListConfigs(options)
		if err != nil {
			return err
		}
		writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "config.list", Message: "sing-box 配置列表", Data: data})
	case "read":
		if len(positionals) == 0 {
			return errors.New("config read 需要目标")
		}
		data, err := moduleapp.ReadConfig(options, positionals[0])
		if err != nil {
			return err
		}
		writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "config.read", Message: "配置内容", Data: data})
	case "check":
		err := moduleapp.CheckService(ctx, options)
		if err != nil {
			return err
		}
		writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "config.checked", Message: "sing-box 配置检查通过", Data: map[string]any{}})
	case "validate", "apply":
		if len(positionals) < 2 {
			return errors.New("config 操作需要目标和内容文件")
		}
		savedRevision, err := moduleapp.ApplyConfig(ctx, options, positionals[0], positionals[1], action == "validate", *revision)
		if err != nil {
			if errors.Is(err, moduleapp.ErrConfigConflict) {
				return &resultError{Code: "config.conflict", Message: err.Error()}
			}
			return err
		}
		writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "config." + action, Message: "配置检查通过", Data: map[string]string{"target": positionals[0], "revision": savedRevision}})
	default:
		return fmt.Errorf("未知 config 操作 %q", action)
	}
	return nil
}
