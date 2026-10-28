package graph

// This file will not be regenerated automatically.

import (
	"github.com/yophon/gopan/server/internal/config"
	"github.com/yophon/gopan/server/internal/service"
)

type Resolver struct {
	Cfg       *config.Config
	Auth      *service.Auth
	Nodes     *service.Nodes
	Uploads   *service.Uploads
	Previews  *service.Previews
	Shares    *service.Shares
	Admin     *service.Admin
	AppPass   *service.AppPasswords
	MCPTokens *service.MCPTokens
	OAuth     *service.OAuth
	Chats     *service.Chats
	Collects  *service.Collects
	// ShareUploads 分享访客的上传(doc/32 一)
	ShareUploads *service.ShareUploads
	Content   *service.ContentSearch
	Jobs      *service.Jobs
	Archives  *service.Archives
	Media     *service.Media
	Notify    *service.Notifier
	Links     *service.Links

	Activities *service.Activities
	Space      *service.Space
	Feeds      *service.Feeds
	Clips      *service.Clips

	Versions *service.Versions
}
