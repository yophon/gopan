package graph

import (
	"strings"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/service"
)

// searchFilter GraphQL 的 SearchFilter → service 的(doc/30);nil 进 nil 出
func searchFilter(f *SearchFilter) (*service.SearchFilter, error) {
	if f == nil {
		return nil, nil
	}
	out := &service.SearchFilter{After: f.ModifiedAfter, Before: f.ModifiedBefore, MinSize: f.MinSize, MaxSize: f.MaxSize}
	for _, t := range f.Types {
		out.Types = append(out.Types, strings.ToLower(string(t)))
	}
	if f.FolderID != nil && *f.FolderID != "" {
		id, err := uuid.Parse(*f.FolderID)
		if err != nil {
			return nil, &service.Error{Code: "INVALID_INPUT", Message: "folderId 不对"}
		}
		out.Root = &id
	}
	if f.Order != nil {
		out.Order = string(*f.Order)
	}
	return out, nil
}
