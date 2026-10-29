package service

import (
	"context"
	"encoding/json"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/store"
)

// 动态(doc/23 二):谁、什么时候、对哪个节点做了什么。来源由各入口放进 context,
// 服务层的写操作在同一个事务里记一笔;查询与撤回见 activity_undo.go。

// 来源(与 GraphQL ActorKind 小写同名)。
const (
	ActorWeb       = "web"
	ActorAndroid   = "android"
	ActorWebDAV    = "webdav"
	ActorAgent     = "agent"
	ActorImg       = "img"
	ActorCollect   = "collect"
	ActorShare     = "share"     // 分享访客(doc/32 一),Ref 是分享 id
	ActorAssistant = "assistant" // 助手(doc/33),Ref 是对话 id
	ActorJob       = "job"
	ActorFeed      = "feed"
	ActorClip      = "clip"
	ActorSystem    = "system"
)

// 操作(与 GraphQL ActivityAction 小写同名)。
const (
	OpMkdir     = "mkdir"
	OpUpload    = "upload"
	OpOverwrite = "overwrite"
	OpRename    = "rename"
	OpMove      = "move"
	OpCopy      = "copy"
	OpDelete    = "delete"
	OpRestore   = "restore"
	OpPurge     = "purge"
	OpExtract   = "extract"
)

// Actor 一次改动的来源。Ref 是来源的标识(登录会话、应用密码、凭据、收集链接、任务、订阅的 id),
// Label 是给人看的名字,写进记录里做快照(会话过期删掉后也看得到)。
type Actor struct {
	Kind  string `json:"k"`
	Ref   string `json:"r,omitempty"`
	Label string `json:"l,omitempty"`
}

type (
	actorKey struct{}
	batchKey struct{}
	quietKey struct{}
	undoKey  struct{}
)

func WithActor(ctx context.Context, a Actor) context.Context {
	return context.WithValue(ctx, actorKey{}, a)
}

// ActorFrom 没放来源的(后台清理、测试)算系统。
func ActorFrom(ctx context.Context) Actor {
	if a, ok := ctx.Value(actorKey{}).(Actor); ok && a.Kind != "" {
		return a
	}
	return Actor{Kind: ActorSystem}
}

// SessionActor 网页 / App 的登录会话:设备名与「登录设备」里显示的一致。
func SessionActor(family uuid.UUID, ua string) Actor {
	a := Actor{Kind: ActorWeb, Label: DeviceLabel(ua)}
	if reAndroidApp.MatchString(ua) {
		a.Kind = ActorAndroid
	}
	if family != uuid.Nil {
		a.Ref = family.String()
	}
	return a
}

// WithBatch 开一个新批次:之后这个 context 里的所有改动共用一个 batch_id(一次 Agent 工具调用、一个后台任务)。
// 没开批次时每次服务层调用各自一批(一次多选移动是一批)。
func WithBatch(ctx context.Context) context.Context {
	return context.WithValue(ctx, batchKey{}, uuid.Must(uuid.NewV7()))
}

// WithBatchID 用给定的批次:助手同一轮回答的几次工具调用合成一批(doc/33 三)。
func WithBatchID(ctx context.Context, id uuid.UUID) context.Context {
	return context.WithValue(ctx, batchKey{}, id)
}

func batchOf(ctx context.Context) uuid.UUID {
	if b, ok := ctx.Value(batchKey{}).(uuid.UUID); ok {
		return b
	}
	return uuid.Must(uuid.NewV7())
}

// withQuiet 里面的写操作不单独记(解压、剪藏逐个写文件时;结束时由调用方记一条汇总)。
func withQuiet(ctx context.Context) context.Context { return context.WithValue(ctx, quietKey{}, true) }

func isQuiet(ctx context.Context) bool { q, _ := ctx.Value(quietKey{}).(bool); return q }

func withUndoOf(ctx context.Context, id int64) context.Context {
	return context.WithValue(ctx, undoKey{}, id)
}

// actPlace 移动前 / 后的位置;Parent 为空 = 根目录。
type actPlace struct {
	Parent *uuid.UUID `json:"parent"`
	Path   string     `json:"path"`
}

type actDetail struct {
	From    *actPlace  `json:"from,omitempty"`
	To      *actPlace  `json:"to,omitempty"`
	OldName string     `json:"old_name,omitempty"`
	NewName string     `json:"new_name,omitempty"`
	Files   int64      `json:"files,omitempty"`
	Bytes   int64      `json:"bytes,omitempty"`
	Source  *uuid.UUID `json:"source,omitempty"` // 复制的源节点
	UndoOf  int64      `json:"undo_of,omitempty"`
	Note    string     `json:"note,omitempty"`    // 补充说明:任务标题、解压的是哪个包
	Version *uuid.UUID `json:"version,omitempty"` // 覆盖:换下来的旧内容留成的版本,撤回就是恢复它(doc/24)
}

// actItem 一条要记的改动。Parent 是节点现在所在的文件夹(nil = 根),用来算 parent_path。
type actItem struct {
	Action string
	NodeID uuid.UUID
	Kind   string
	Name   string
	Parent *uuid.UUID
	Detail actDetail
}

func nodeAct(action string, n store.Node, d actDetail) actItem {
	return actItem{Action: action, NodeID: n.ID, Kind: n.Kind, Name: n.Name, Parent: n.ParentID, Detail: d}
}

// fileAct 新建 / 覆盖一个文件:带上大小。
func fileAct(action string, n store.Node, size int64) actItem {
	return nodeAct(action, n, actDetail{Files: 1, Bytes: size})
}

type actRow struct {
	Action     string     `json:"action"`
	NodeID     *uuid.UUID `json:"node_id"`
	NodeKind   string     `json:"node_kind"`
	Name       string     `json:"name"`
	ParentPath string     `json:"parent_path"`
	Detail     actDetail  `json:"detail"`
}

// recordActs 用业务的 q(同一事务)把改动写进动态。quiet 的上下文不记。
func recordActs(ctx context.Context, q *store.Queries, owner uuid.UUID, items ...actItem) error {
	if len(items) == 0 || isQuiet(ctx) {
		return nil
	}
	paths := map[uuid.UUID]string{}
	pathOf := func(p *uuid.UUID) (string, error) {
		if p == nil {
			return "/", nil
		}
		if v, ok := paths[*p]; ok {
			return v, nil
		}
		v, err := q.NodePath(ctx, *p)
		if err != nil {
			return "", err
		}
		paths[*p] = v
		return v, nil
	}
	undoOf, _ := ctx.Value(undoKey{}).(int64)
	rows := make([]actRow, 0, len(items))
	for _, it := range items {
		pp, err := pathOf(it.Parent)
		if err != nil {
			return err
		}
		d := it.Detail
		if undoOf != 0 {
			d.UndoOf = undoOf
		}
		for _, pl := range []*actPlace{d.From, d.To} {
			if pl != nil && pl.Path == "" {
				if pl.Path, err = pathOf(pl.Parent); err != nil {
					return err
				}
			}
		}
		var id *uuid.UUID
		if it.NodeID != uuid.Nil {
			v := it.NodeID
			id = &v
		}
		rows = append(rows, actRow{Action: it.Action, NodeID: id, NodeKind: it.Kind, Name: it.Name, ParentPath: pp, Detail: d})
	}
	b, err := json.Marshal(rows)
	if err != nil {
		return err
	}
	if c, ok := filesChange(items); ok {
		if err := notifyChange(ctx, q, owner, c); err != nil {
			return err
		}
	}
	a := ActorFrom(ctx)
	return q.InsertActivities(ctx, store.InsertActivitiesParams{
		OwnerID: owner, ActorKind: a.Kind, ActorRef: a.Ref, ActorLabel: truncateRunes(a.Label, 100),
		BatchID: batchOf(ctx), Rows: b,
	})
}

// sessionActor 上传会话里存的来源:活动记录的发起者,加上传输助手消息的来源(桌面端等,doc/29)。
// 定稿常在 worker 里做,拿不到发起上传的那个请求,两样都得从这里取回。
type sessionActor struct {
	Actor
	ChatSource string `json:"s,omitempty"`
	// 分享访客(doc/32 一):留的名字,与开会话时的 IP / 客户端(定稿在 worker 里,拿不到请求)
	Uploader string `json:"u,omitempty"`
	IP       string `json:"ip,omitempty"`
	UA       string `json:"ua,omitempty"`
}

// actorJSON / actorFromJSON 上传会话里存的来源(worker 定稿时取回)。
func actorJSON(ctx context.Context) []byte {
	sa := sessionActor{Actor: ActorFrom(ctx)}
	if s, ok := ctx.Value(chatSourceKey{}).(string); ok {
		sa.ChatSource = s
	}
	b, _ := json.Marshal(sa)
	return b
}

func actorFromJSON(ctx context.Context, raw []byte) context.Context {
	var a sessionActor
	if len(raw) == 0 || json.Unmarshal(raw, &a) != nil {
		return ctx
	}
	if a.Kind != "" {
		ctx = WithActor(ctx, a.Actor)
	}
	if a.ChatSource != "" {
		ctx = WithChatSource(ctx, a.ChatSource)
	}
	return ctx
}
