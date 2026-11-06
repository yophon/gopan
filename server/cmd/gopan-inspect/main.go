// gopan-inspect:给 MCP 目录(Glama 等)做 schema 自省的 stdio 入口(docs/MCP.md「目录收录」)。
// 工具定义与 /mcp 完全相同;不读配置、不连数据库与对象存储,所有工具调用都会被拒绝。
// 真正使用请部署 gopan,连 https://<你的域名>/mcp。stdout 只走 MCP 协议,日志写 stderr。
package main

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/yophon/gopan/server/internal/mcpserver"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := mcpserver.NewInspectionServer().Run(ctx, &mcp.StdioTransport{}); err != nil && ctx.Err() == nil {
		fmt.Fprintln(os.Stderr, "gopan-inspect:", err)
		os.Exit(1)
	}
}
