package service

// 助手的凭证与权限(doc/33 三):每个用户固定一项「助手」,没有自己的密钥,用登录凭证 + X-Gopan-Assistant 头
// 调 /mcp。权限按组三态:allow / ask / deny。deny 由服务端管(这组的 scope 不给,工具不出现、硬调被拒);
// ask 由客户端里的 tau 判、gopan 弹确认卡,服务端只当它是给了。

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	AssistantAllow = "allow"
	AssistantAsk   = "ask"
	AssistantDeny  = "deny"

	CredentialAssistant = "assistant"
)

// AssistantGroup 权限表里的一行。Outbound:这组能把东西发到外面(公开链接、访问任意网址),改成允许时提示风险。
// 这张表只放按账号同步的组;以后的本机文件组按设备记,由客户端自己存(doc/33 六)。
type AssistantGroup struct {
	Key      string
	Label    string
	Scopes   []string
	Default  string
	Outbound bool
}

var AssistantGroups = []AssistantGroup{
	{Key: "files:read", Label: "查看文件", Scopes: []string{"files:read", "files:download"}, Default: AssistantAllow},
	{Key: "files:write", Label: "修改文件", Scopes: []string{"files:write"}, Default: AssistantAsk},
	{Key: "files:delete", Label: "删除文件", Scopes: []string{"files:delete"}, Default: AssistantAsk},
	{Key: "files:upload", Label: "存入文件", Scopes: []string{"files:upload"}, Default: AssistantAsk, Outbound: true},
	{Key: "shares:read", Label: "查看分享", Scopes: []string{"shares:read"}, Default: AssistantAllow},
	{Key: "shares:write", Label: "管理分享", Scopes: []string{"shares:write"}, Default: AssistantAsk, Outbound: true},
	{Key: "chat:read", Label: "读传输助手", Scopes: []string{"chat:read"}, Default: AssistantAllow},
	{Key: "chat:write", Label: "发传输助手", Scopes: []string{"chat:write"}, Default: AssistantAsk},
	{Key: "audit:read", Label: "查看动态", Scopes: []string{"audit:read"}, Default: AssistantAllow},
}

// AssistantGroupOf scope 归哪一组。
func AssistantGroupOf(scope string) (AssistantGroup, bool) {
	for _, g := range AssistantGroups {
		for _, s := range g.Scopes {
			if s == scope {
				return g, true
			}
		}
	}
	return AssistantGroup{}, false
}

type AssistantAccess struct {
	Disabled  bool
	Policy    map[string]string // 每组都有值(没设的填默认)
	UpdatedAt *time.Time
}

type Assistant struct {
	pool *pgxpool.Pool
}

func NewAssistant(pool *pgxpool.Pool) *Assistant { return &Assistant{pool: pool} }

func fillAssistantPolicy(stored map[string]string) map[string]string {
	out := make(map[string]string, len(AssistantGroups))
	for _, g := range AssistantGroups {
		v := stored[g.Key]
		if v != AssistantAllow && v != AssistantAsk && v != AssistantDeny {
			v = g.Default
		}
		out[g.Key] = v
	}
	return out
}

func (a *Assistant) Access(ctx context.Context, user uuid.UUID) (AssistantAccess, error) {
	var raw []byte
	var at time.Time
	out := AssistantAccess{}
	err := a.pool.QueryRow(ctx, `SELECT disabled, policy, updated_at FROM assistant_access WHERE user_id = $1`, user).Scan(&out.Disabled, &raw, &at)
	if errors.Is(err, pgx.ErrNoRows) {
		out.Policy = fillAssistantPolicy(nil)
		return out, nil
	}
	if err != nil {
		return out, err
	}
	stored := map[string]string{}
	_ = json.Unmarshal(raw, &stored)
	out.Policy = fillAssistantPolicy(stored)
	out.UpdatedAt = &at
	return out, nil
}

// SetAccess policy 里只认已知的组和三个值;没给的组保持原样。
func (a *Assistant) SetAccess(ctx context.Context, user uuid.UUID, disabled bool, policy map[string]string) (AssistantAccess, error) {
	cur, err := a.Access(ctx, user)
	if err != nil {
		return cur, err
	}
	for k, v := range policy {
		if _, ok := cur.Policy[k]; !ok {
			return cur, errf("INVALID_INPUT", "不认识的权限组 %s", k)
		}
		if v != AssistantAllow && v != AssistantAsk && v != AssistantDeny {
			return cur, errf("INVALID_INPUT", "权限只能是 allow / ask / deny")
		}
		cur.Policy[k] = v
	}
	raw, _ := json.Marshal(cur.Policy)
	if _, err := a.pool.Exec(ctx, `INSERT INTO assistant_access (user_id, disabled, policy) VALUES ($1, $2, $3)
		ON CONFLICT (user_id) DO UPDATE SET disabled = EXCLUDED.disabled, policy = EXCLUDED.policy, updated_at = now()`,
		user, disabled, raw); err != nil {
		return cur, err
	}
	return a.Access(ctx, user)
}

// Principal /mcp 上助手的身份:scope = 不是 deny 的组;停用时拒绝。conversation 记进动态来源,turn 是这一轮回答的批次。
func (a *Assistant) Principal(ctx context.Context, user uuid.UUID, username string, conversation, turn uuid.UUID) (*MCPPrincipal, error) {
	acc, err := a.Access(ctx, user)
	if err != nil {
		return nil, err
	}
	if acc.Disabled {
		return nil, errf("FORBIDDEN", "助手已停用")
	}
	scopes := map[string]struct{}{}
	for _, g := range AssistantGroups {
		if acc.Policy[g.Key] == AssistantDeny {
			continue
		}
		for _, s := range g.Scopes {
			scopes[s] = struct{}{}
		}
	}
	return &MCPPrincipal{
		CredentialID: user, UserID: user, Username: username, CredentialType: CredentialAssistant,
		Scopes: scopes, Name: "助手", Conversation: conversation, Turn: turn,
	}, nil
}
