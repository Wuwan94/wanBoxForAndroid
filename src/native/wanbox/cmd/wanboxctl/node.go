package main

import (
	"context"
	"encoding/json/jsontext"
	"errors"
	"os"

	"github.com/Wuwan94/wanBoxForAndroid/src/native/wanbox/internal/catalog"
	moduleapp "github.com/Wuwan94/wanBoxForAndroid/src/native/wanbox/internal/module"
	"github.com/Wuwan94/wanBoxForAndroid/src/native/wanbox/internal/provider"
	"github.com/Wuwan94/wanBoxForAndroid/src/native/wanbox/internal/service"
)

func (c *cli) node(ctx context.Context, args []string) error {
	if len(args) == 0 {
		args = []string{"list"}
	}
	action := args[0]
	flags := newFlagSet("node " + action)
	allowInsecure := flags.Bool("allow-insecure", false, "跳过节点 TLS 校验")
	if err := flags.Parse(args[1:]); err != nil {
		return err
	}
	positionals := flags.Args()
	options := c.options
	control := service.Options{
		CatalogRoot: options.CatalogRoot, ModuleConfig: options.ModuleConfig, StateFile: options.StateFile,
		ProgressDir: options.ProgressDir, WorkerPIDFile: options.WorkerPIDFile, SingBoxPath: options.SingBoxPath,
		ServiceAddress: options.ServiceAddress, ServiceSecret: options.ServiceSecret, RequestTimeout: options.RequestTimeout,
	}
	switch action {
	case "add", "import", "edit", "remove", "use", "show":
		if len(positionals) == 0 {
			return usageError("node 操作缺少参数")
		}
	}
	switch action {
	case "list":
		groups, err := service.ReadNodes(ctx, control, first(positionals))
		if err != nil {
			return err
		}
		writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "node.list", Message: "节点列表", Data: groups})
		return nil
	case "snapshot":
		snapshot, err := service.ReadSnapshot(ctx, control, first(positionals))
		if err != nil {
			return err
		}
		writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "node.snapshot", Message: "节点快照", Data: snapshot})
		return nil
	case "current":
		selection, err := service.ReadSelection(ctx, control)
		if err != nil {
			return err
		}
		writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "node.current", Message: "当前节点选择", Data: selection})
		return nil
	case "delay":
		delay, err := service.Delay(ctx, control, first(positionals), second(positionals))
		if err != nil {
			if structured, ok := errors.AsType[*service.Error](err); ok {
				return &resultError{Code: structured.Code, Message: structured.Message, Data: structured.Data}
			}
			return err
		}
		writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "node.delay", Message: "节点测速完成", Data: delay})
		return nil

	case "show":
		return c.catalogSnapshot(ctx, positionals[0], true)
	case "get", "export":
		group, tag, ok := splitReference(first(positionals))
		if !ok {
			return &resultError{Code: "node.ref_invalid", Message: "节点引用格式应为 <group-id>/<tag>", Status: 2}
		}
		if action == "export" {
			exported, err := catalog.ExportGroupNode(ctx, options.CatalogRoot, group, tag)
			if err != nil {
				return err
			}
			writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "node.exported", Message: "节点分享链接已生成", Data: exported})
			return nil
		}
		document, err := catalog.GroupNode(ctx, options.CatalogRoot, group, tag)
		if err != nil {
			return err
		}
		content, err := provider.Marshal(ctx, document)
		if err != nil {
			return err
		}
		writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "node.loaded", Message: "节点配置已读取", Data: jsontext.Value(content)})
	case "add":
		group := "default"
		if len(positionals) > 1 {
			group = positionals[1]
		}
		data, err := moduleapp.NodeAppend(ctx, options, group, positionals[0], *allowInsecure)
		if err != nil {
			return err
		}
		writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "node.added", Message: "节点已加入本地配置", Data: data})
	case "import":
		if len(positionals) != 1 {
			return usageError("用法: wanboxctl node import <文件>")
		}
		data, err := moduleapp.NodeImport(ctx, options, positionals[0], *allowInsecure)
		if err != nil {
			return err
		}
		writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "node.imported", Message: "文件节点已加入本地配置", Data: data})
	case "edit":
		if len(positionals) < 2 {
			return errors.New("node edit 需要节点引用和节点内容")
		}
		data, err := moduleapp.NodeEdit(ctx, options, positionals[0], positionals[1], *allowInsecure)
		if err != nil {
			return err
		}
		writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "node.edited", Message: "节点已更新", Data: data})
	case "remove":
		data, err := moduleapp.NodeRemove(ctx, options, positionals[0])
		if err != nil {
			return err
		}
		writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "node.removed", Message: "节点已删除", Data: data})

	case "use":
		data, err := moduleapp.SelectNode(ctx, options, positionals[0], second(positionals))
		if err != nil {
			return err
		}
		writeJSON(os.Stdout, result{Schema: 1, OK: true, Code: "node.selected", Message: "节点选择已更新", Data: data})
	default:
		return usageError("用法: wanboxctl node list|snapshot|current|show|get|export|delay|add|import|edit|remove|use")
	}
	return nil
}

func second(values []string) string {
	if len(values) < 2 {
		return ""
	}
	return values[1]
}
