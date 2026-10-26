package service

import (
	"context"
	"errors"
	"fmt"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/yophon/gopan/server/internal/store"
)

// QuickShareFolderName 「一步变成分享链接」(doc/31 四)传上来的文件都放在根目录的这个文件夹里。
const QuickShareFolderName = "快传"

// QuickShareFolder 幂等返回根目录的「快传」文件夹,没有就建。同名的是个文件时建「快传 (1)」。
func (s *Nodes) QuickShareFolder(ctx context.Context, owner uuid.UUID) (store.Node, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return store.Node{}, err
	}
	defer tx.Rollback(ctx)
	// 锁到事务结束:两台设备同时第一次用不会建出两个
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1,0))`, "quick-share-folder:"+owner.String()); err != nil {
		return store.Node{}, err
	}
	qtx := s.q.WithTx(tx)
	n, err := qtx.GetActiveChildByName(ctx, store.GetActiveChildByNameParams{OwnerID: owner, ParentID: nil, Name: QuickShareFolderName})
	if err == nil && n.Kind == "folder" {
		return n, tx.Commit(ctx)
	}
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return store.Node{}, err
	}
	name := QuickShareFolderName
	for i := 1; ; i++ {
		taken, err := qtx.SiblingNameExists(ctx, store.SiblingNameExistsParams{OwnerID: owner, ParentID: nil, Name: name})
		if err != nil {
			return store.Node{}, err
		}
		if !taken {
			break
		}
		name = fmt.Sprintf("%s (%d)", QuickShareFolderName, i)
	}
	n, err = qtx.CreateNode(ctx, store.CreateNodeParams{ID: uuid.Must(uuid.NewV7()), OwnerID: owner, Name: name, Kind: "folder"})
	if err != nil {
		return store.Node{}, err
	}
	if err := recordActs(ctx, qtx, owner, nodeAct(OpMkdir, n, actDetail{})); err != nil {
		return store.Node{}, err
	}
	return n, tx.Commit(ctx)
}
