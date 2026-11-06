package mcpserver

import "github.com/modelcontextprotocol/go-sdk/mcp"

// implementationVersion 是 /mcp 与自省入口在 initialize 里报的 serverInfo.version。
const implementationVersion = "2.3.0"

const inspectionInstructions = "Schema inspection only. This is the tool catalog of the remote MCP endpoint " +
	"(/mcp) built into gopan, a self-hosted cloud drive. No database or storage is connected and every tool call " +
	"is refused. To use these tools, deploy gopan and connect to https://<your-host>/mcp with OAuth 2.1 or an API key."

// NewInspectionServer 给 MCP 目录(Glama 等)做 schema 自省:注册与 /mcp 完全相同的工具定义,
// 但不读配置、不连数据库与对象存储。上下文里没有凭据,addTool 的包装在碰任何服务之前就拒绝调用
// (ErrUnauthenticated),所以这里不需要另造一套假的服务。入口是 cmd/gopan-inspect(docs/MCP.md「目录收录」)。
func NewInspectionServer() *mcp.Server {
	protocol := mcp.NewServer(&mcp.Implementation{Name: "gopan", Version: implementationVersion},
		&mcp.ServerOptions{Instructions: inspectionInstructions})
	(&Server{}).registerTools(protocol)
	return protocol
}
