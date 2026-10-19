package main

import (
	"context"
	"os"

	moduleapp "github.com/Wuwan94/wanBoxForAndroid/src/native/wanbox/internal/module"
)

func (c *cli) service(ctx context.Context, args []string) error {
	operation := "status"
	if len(args) > 0 {
		operation = args[0]
	}
	switch operation {
	case "status", "start", "stop", "restart", "reload", "check", "toggle":
	default:
		return usageError("用法: wanboxctl service status|start|stop|restart|reload|check|toggle")
	}
	data, err := moduleapp.ManageService(ctx, c.options, operation)
	if err != nil {
		return err
	}
	message := "服务操作完成"
	responseData := any(data)
	if operation == "status" {
		message = "服务状态"
		// service.status 是 Android 与 WebUI 的既有公开契约，状态字段必须直接位于 data。
		responseData = data.Status
	}
	writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "service." + operation, Message: message, Data: responseData})
	return nil
}
