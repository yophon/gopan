package service

import (
	"errors"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

const packTicketIssuer = "gopan-mcp-pack"

type packTicketClaims struct {
	NodeIDs []string `json:"nodes"`
	jwt.RegisteredClaims
}

type PackTickets struct {
	secret []byte
	ttl    time.Duration
}

func NewPackTickets(secret []byte, ttl time.Duration) *PackTickets {
	return &PackTickets{secret: secret, ttl: ttl}
}

func (p *PackTickets) Issue(userID uuid.UUID, nodeIDs []uuid.UUID) (string, error) {
	if len(nodeIDs) == 0 || len(nodeIDs) > 100 {
		return "", errf("INVALID_INPUT", "打包节点数量必须为 1~100")
	}
	now := time.Now()
	ids := make([]string, len(nodeIDs))
	for i, id := range nodeIDs {
		ids[i] = id.String()
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, packTicketClaims{
		NodeIDs: ids,
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer: packTicketIssuer, Subject: userID.String(),
			IssuedAt: jwt.NewNumericDate(now), ExpiresAt: jwt.NewNumericDate(now.Add(p.ttl)),
		},
	})
	return token.SignedString(p.secret)
}

func (p *PackTickets) Parse(raw string) (*Identity, []uuid.UUID, error) {
	var claims packTicketClaims
	_, err := jwt.ParseWithClaims(raw, &claims, func(token *jwt.Token) (any, error) {
		if token.Method != jwt.SigningMethodHS256 {
			return nil, errors.New("unexpected signing method")
		}
		return p.secret, nil
	}, jwt.WithIssuer(packTicketIssuer))
	if err != nil {
		return nil, nil, ErrUnauthenticated
	}
	userID, err := uuid.Parse(claims.Subject)
	if err != nil {
		return nil, nil, ErrUnauthenticated
	}
	ids := make([]uuid.UUID, 0, len(claims.NodeIDs))
	for _, rawID := range claims.NodeIDs {
		id, err := uuid.Parse(rawID)
		if err != nil {
			return nil, nil, ErrUnauthenticated
		}
		ids = append(ids, id)
	}
	if len(ids) == 0 {
		return nil, nil, ErrUnauthenticated
	}
	return &Identity{UserID: userID, Scope: ScopeUser}, ids, nil
}
