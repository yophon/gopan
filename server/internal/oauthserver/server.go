package oauthserver

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"strings"

	"github.com/yophon/gopan/server/internal/service"
)

type Server struct {
	oauth *service.OAuth
}

func New(oauth *service.OAuth) *Server {
	return &Server{oauth: oauth}
}

func (s *Server) AuthorizationMetadata(w http.ResponseWriter, r *http.Request) {
	base := requestBaseURL(r)
	writeJSON(w, http.StatusOK, map[string]any{
		"issuer":                                base,
		"authorization_endpoint":                base + "/oauth/authorize",
		"token_endpoint":                        base + "/oauth/token",
		"registration_endpoint":                 base + "/oauth/register",
		"response_types_supported":              []string{"code"},
		"grant_types_supported":                 []string{"authorization_code", "refresh_token"},
		"token_endpoint_auth_methods_supported": []string{"none"},
		"code_challenge_methods_supported":      []string{"S256"},
		"scopes_supported":                      service.MCPScopes,
	})
}

func (s *Server) ProtectedResourceMetadata(w http.ResponseWriter, r *http.Request) {
	base := requestBaseURL(r)
	resource, scopes := "/mcp", service.MCPScopes
	if strings.HasSuffix(r.URL.Path, "/mcp/admin") {
		resource = "/mcp/admin"
		scopes = service.MCPAdminScopes
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"resource":                 base + resource,
		"authorization_servers":    []string{base},
		"bearer_methods_supported": []string{"header"},
		"scopes_supported":         scopes,
	})
}

func (s *Server) Authorize(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	in := authorizationInput(q)
	if _, err := s.oauth.InspectAuthorization(r.Context(), in); err != nil {
		writeOAuthError(w, err)
		return
	}
	location := "/oauth/consent"
	if r.URL.RawQuery != "" {
		location += "?" + r.URL.RawQuery
	}
	http.Redirect(w, r, location, http.StatusFound)
}

type registrationRequest struct {
	ClientName              string   `json:"client_name"`
	RedirectURIs            []string `json:"redirect_uris"`
	TokenEndpointAuthMethod string   `json:"token_endpoint_auth_method"`
}

func (s *Server) Register(w http.ResponseWriter, r *http.Request) {
	defer r.Body.Close()
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	var req registrationRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeOAuthJSONError(w, http.StatusBadRequest, "invalid_client_metadata", "invalid JSON body")
		return
	}
	if req.TokenEndpointAuthMethod != "" && req.TokenEndpointAuthMethod != "none" {
		writeOAuthJSONError(w, http.StatusBadRequest, "invalid_client_metadata", "only public clients are supported")
		return
	}
	client, err := s.oauth.RegisterClient(r.Context(), req.ClientName, req.RedirectURIs)
	if err != nil {
		writeOAuthJSONError(w, http.StatusBadRequest, "invalid_client_metadata", errorDescription(err))
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{
		"client_id":                  client.ClientID,
		"client_name":                client.ClientName,
		"redirect_uris":              client.RedirectUris,
		"token_endpoint_auth_method": "none",
		"grant_types":                []string{"authorization_code", "refresh_token"},
		"response_types":             []string{"code"},
		"client_id_issued_at":        client.CreatedAt.Time.Unix(),
	})
}

func (s *Server) Token(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	if err := r.ParseForm(); err != nil {
		writeOAuthJSONError(w, http.StatusBadRequest, "invalid_request", "invalid form body")
		return
	}
	var (
		result *service.OAuthTokenResult
		err    error
	)
	switch r.Form.Get("grant_type") {
	case "authorization_code":
		result, err = s.oauth.ExchangeAuthorizationCode(r.Context(),
			r.Form.Get("code"), r.Form.Get("client_id"), r.Form.Get("redirect_uri"), r.Form.Get("code_verifier"))
	case "refresh_token":
		result, err = s.oauth.Refresh(r.Context(), r.Form.Get("refresh_token"), r.Form.Get("client_id"))
	default:
		writeOAuthJSONError(w, http.StatusBadRequest, "unsupported_grant_type", "unsupported grant_type")
		return
	}
	if err != nil {
		writeOAuthError(w, err)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Pragma", "no-cache")
	writeJSON(w, http.StatusOK, map[string]any{
		"access_token":  result.AccessToken,
		"token_type":    "Bearer",
		"expires_in":    result.ExpiresIn,
		"refresh_token": result.RefreshToken,
		"scope":         strings.Join(result.Scopes, " "),
	})
}

func authorizationInput(q url.Values) service.OAuthAuthorizationInput {
	return service.OAuthAuthorizationInput{
		ClientID: q.Get("client_id"), RedirectURI: q.Get("redirect_uri"),
		ResponseType: q.Get("response_type"), Scopes: strings.Fields(q.Get("scope")),
		State: q.Get("state"), CodeChallenge: q.Get("code_challenge"),
		CodeChallengeMethod: q.Get("code_challenge_method"),
	}
}

func requestBaseURL(r *http.Request) string {
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	if forwarded := firstHeaderValue(r.Header.Get("X-Forwarded-Proto")); forwarded == "http" || forwarded == "https" {
		scheme = forwarded
	}
	host := r.Host
	if forwarded := firstHeaderValue(r.Header.Get("X-Forwarded-Host")); forwarded != "" {
		host = forwarded
	}
	return scheme + "://" + host
}

func firstHeaderValue(value string) string {
	value, _, _ = strings.Cut(value, ",")
	return strings.TrimSpace(value)
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeOAuthError(w http.ResponseWriter, err error) {
	code := "invalid_request"
	status := http.StatusBadRequest
	var se *service.Error
	if errors.As(err, &se) {
		switch se.Code {
		case "OAUTH_INVALID_CLIENT":
			code = "invalid_client"
			status = http.StatusUnauthorized
		case "OAUTH_INVALID_GRANT":
			code = "invalid_grant"
		}
	}
	writeOAuthJSONError(w, status, code, errorDescription(err))
}

func writeOAuthJSONError(w http.ResponseWriter, status int, code, description string) {
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, status, map[string]string{"error": code, "error_description": description})
}

func errorDescription(err error) string {
	var se *service.Error
	if errors.As(err, &se) {
		return se.Message
	}
	return "internal error"
}
