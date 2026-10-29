package graph

// 助手(doc/33)的 GraphQL 转换。

import (
	"strings"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/service"
)

func aiKindToGQL(k string) AiProviderKind {
	return AiProviderKind(strings.ToUpper(strings.ReplaceAll(k, "-", "_")))
}

func aiKindFromGQL(k AiProviderKind) string {
	return strings.ToLower(strings.ReplaceAll(string(k), "_", "-"))
}

func gqlAiProvider(p service.AIProvider, used map[uuid.UUID]int64) *AiProvider {
	return &AiProvider{
		ID: p.ID.String(), Name: p.Name, Kind: aiKindToGQL(p.Kind), BaseURL: p.BaseURL,
		Models: p.Models, HasKey: p.HasKey, Site: p.Site(), DailyTokens: p.DailyTokens,
		UsedTokens: used[p.ID], UpdatedAt: p.UpdatedAt,
	}
}

func aiInput(in AiProviderInput) service.AIProviderInput {
	out := service.AIProviderInput{Name: in.Name, Kind: aiKindFromGQL(in.Kind), BaseURL: in.BaseURL, Models: in.Models, Key: in.Key}
	if in.DailyTokens != nil {
		out.DailyTokens = *in.DailyTokens
	}
	return out
}

func gqlAssistantAccess(a service.AssistantAccess) *AssistantAccess {
	out := &AssistantAccess{Disabled: a.Disabled}
	for _, g := range service.AssistantGroups {
		out.Groups = append(out.Groups, &AssistantGroup{
			Key: g.Key, Label: g.Label, Scopes: g.Scopes, Outbound: g.Outbound,
			Permission:        AssistantPermission(strings.ToUpper(a.Policy[g.Key])),
			DefaultPermission: AssistantPermission(strings.ToUpper(g.Default)),
		})
	}
	return out
}

func gqlAiConversation(c service.AIConversation) *AiConversation {
	out := &AiConversation{
		ID: c.ID.String(), Title: c.Title, Model: c.Model, Entries: int(c.Entries),
		CreatedAt: c.CreatedAt, UpdatedAt: c.UpdatedAt,
	}
	if c.ProviderID != nil {
		s := c.ProviderID.String()
		out.ProviderID = &s
	}
	return out
}

func optionalUUID(s *string) (*uuid.UUID, error) {
	if s == nil || *s == "" {
		return nil, nil
	}
	id, err := uuid.Parse(*s)
	if err != nil {
		return nil, service.ErrNotFound
	}
	return &id, nil
}
