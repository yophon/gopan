package graph

import (
	"strings"

	"github.com/yophon/gopan/server/internal/service"
)

func oauthAuthorizationInput(input OAuthAuthorizationInput) service.OAuthAuthorizationInput {
	var scopes []string
	if input.Scope != nil {
		scopes = strings.Fields(*input.Scope)
	}
	state := ""
	if input.State != nil {
		state = *input.State
	}
	return service.OAuthAuthorizationInput{
		ClientID: input.ClientID, RedirectURI: input.RedirectURI,
		ResponseType: input.ResponseType, Scopes: scopes, State: state,
		CodeChallenge: input.CodeChallenge, CodeChallengeMethod: input.CodeChallengeMethod,
	}
}
