package httpx

import (
	"encoding/json"
	"testing"
)

func meterOf(chunks ...string) *usageMeter {
	m := &usageMeter{}
	for _, c := range chunks {
		m.Write([]byte(c))
	}
	m.finish()
	return m
}

func TestUsageMeter(t *testing.T) {
	cases := []struct {
		name          string
		chunks        []string
		input, output int64
	}{
		{"openai stream", []string{"data: {\"choices\":[]}\n\n", "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"comp", "letion_tokens\":34}}\n\ndata: [DONE]\n\n"}, 12, 34},
		{"anthropic stream", []string{
			"event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":100,\"output_tokens\":1}}}\n\n",
			"event: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":40}}\n\n",
		}, 100, 40},
		{"google stream", []string{"data: {\"usageMetadata\":{\"promptTokenCount\":7,\"candidatesTokenCount\":3}}\r\n\r\n", "data: {\"usageMetadata\":{\"promptTokenCount\":7,\"candidatesTokenCount\":9}}"}, 7, 9},
		{"responses stream", []string{"event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":5,\"output_tokens\":6}}}\n\n"}, 5, 6},
		{"plain json", []string{"{\"usage\":{\"prompt_tokens\":2,", "\"completion_tokens\":3}}"}, 2, 3},
		{"error body", []string{"{\"error\":{\"message\":\"bad key\"}}"}, 0, 0},
	}
	for _, c := range cases {
		m := meterOf(c.chunks...)
		if m.input != c.input || m.output != c.output {
			t.Errorf("%s: got %d/%d, want %d/%d", c.name, m.input, m.output, c.input, c.output)
		}
	}
}

func TestAIChatPath(t *testing.T) {
	ok := map[string][]string{
		"openai":           {"chat/completions"},
		"openai-responses": {"responses"},
		"anthropic":        {"v1/messages"},
		"google":           {"models/gemini-2.5-flash:streamGenerateContent", "v1beta/models/x:generateContent"},
	}
	bad := map[string][]string{
		"openai":    {"models", "chat/completions/../../admin", "files"},
		"anthropic": {"v1/messages/batches", "v1/models"},
		"google":    {"models/x:countTokens", "files/abc"},
	}
	for kind, paths := range ok {
		for _, p := range paths {
			if !aiChatPath(kind, p) {
				t.Errorf("%s %s should pass", kind, p)
			}
		}
	}
	for kind, paths := range bad {
		for _, p := range paths {
			if aiChatPath(kind, p) {
				t.Errorf("%s %s should be refused", kind, p)
			}
		}
	}
}

func TestWithIncludeUsage(t *testing.T) {
	got := withIncludeUsage([]byte(`{"model":"m","stream":true,"messages":[]}`))
	var m map[string]any
	if err := json.Unmarshal(got, &m); err != nil {
		t.Fatal(err)
	}
	if opts, _ := m["stream_options"].(map[string]any); opts["include_usage"] != true {
		t.Fatalf("include_usage missing: %s", got)
	}
	keep := []byte(`{"stream":true,"stream_options":{"include_usage":false}}`)
	if string(withIncludeUsage(keep)) != string(keep) {
		t.Fatal("client's own include_usage overwritten")
	}
	noStream := []byte(`{"stream":false}`)
	if string(withIncludeUsage(noStream)) != string(noStream) {
		t.Fatal("non-stream body changed")
	}
}
