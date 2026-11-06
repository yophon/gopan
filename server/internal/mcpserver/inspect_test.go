package mcpserver

import (
	"context"
	"testing"

	"github.com/modelcontextprotocol/go-sdk/mcp"
)

// 自省入口(cmd/gopan-inspect)不连库:工具清单要和 /mcp 一样全,任何调用都要被拒绝
func TestInspectionServerListsToolsAndRefusesCalls(t *testing.T) {
	ctx := context.Background()
	ct, st := mcp.NewInMemoryTransports()
	if _, err := NewInspectionServer().Connect(ctx, st, nil); err != nil {
		t.Fatal(err)
	}
	sess, err := mcp.NewClient(&mcp.Implementation{Name: "c", Version: "0"}, nil).Connect(ctx, ct, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer sess.Close()

	res, err := sess.ListTools(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	names := map[string]bool{}
	for _, tool := range res.Tools {
		names[tool.Name] = true
		if tool.Description == "" || tool.InputSchema == nil {
			t.Errorf("tool %s has no description or input schema", tool.Name)
		}
	}
	for _, want := range []string{"list_files", "search_files", "read_text_file", "prepare_upload", "complete_upload", "create_share", "batch_nodes"} {
		if !names[want] {
			t.Errorf("inspection catalog misses %s", want)
		}
	}
	if len(res.Tools) < 40 {
		t.Fatalf("only %d tools in the inspection catalog", len(res.Tools))
	}

	for _, name := range []string{"list_files", "storage_status"} {
		call, err := sess.CallTool(ctx, &mcp.CallToolParams{Name: name, Arguments: map[string]any{}})
		if err == nil && !call.IsError {
			t.Fatalf("inspection server executed %s", name)
		}
	}
}
