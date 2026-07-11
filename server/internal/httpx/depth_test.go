package httpx

import (
	"testing"

	"github.com/vektah/gqlparser/v2/ast"
	"github.com/vektah/gqlparser/v2/parser"
)

func depthOf(t *testing.T, query string) int {
	t.Helper()
	doc, err := parser.ParseQuery(&ast.Source{Input: query})
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	frags := make(map[string]*ast.FragmentDefinition)
	for _, f := range doc.Fragments {
		frags[f.Name] = f
	}
	return queryDepth(doc.Operations[0].SelectionSet, frags, map[string]bool{})
}

func TestQueryDepth(t *testing.T) {
	cases := []struct {
		name  string
		query string
		want  int
	}{
		{"单层", `query { me { id } }`, 2},
		{"平铺不叠加", `query { a { x } b { y } }`, 2},
		{"深嵌套", `query { a { b { c { d { e } } } } }`, 5},
		{"inline fragment 不加层", `query { a { ... on Node { b { c } } } }`, 3},
		{"命名 fragment 展开", `query { a { ...f } } fragment f on Node { b { c } }`, 3},
		{"fragment 循环不死递归", `query { a { ...f } } fragment f on Node { b { ...f } }`, 2},
	}
	for _, c := range cases {
		if got := depthOf(t, c.query); got != c.want {
			t.Errorf("%s: depth=%d want %d", c.name, got, c.want)
		}
	}
}
