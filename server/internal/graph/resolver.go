package graph

// This file will not be regenerated automatically.

import (
	"github.com/yophon/gopan/server/internal/config"
	"github.com/yophon/gopan/server/internal/service"
)

type Resolver struct {
	Cfg      *config.Config
	Auth     *service.Auth
	Nodes    *service.Nodes
	Uploads  *service.Uploads
	Previews *service.Previews
	Shares   *service.Shares
	Admin    *service.Admin
	AppPass  *service.AppPasswords
}
