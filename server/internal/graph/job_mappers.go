package graph

// This file will not be regenerated automatically.

import (
	"context"
	"encoding/json"
	"errors"
	"strings"

	"github.com/google/uuid"

	"github.com/yophon/gopan/server/internal/service"
	"github.com/yophon/gopan/server/internal/store"
)

func gqlJob(j store.UserJob) *Job {
	out := &Job{
		ID:           j.ID.String(),
		Kind:         JobKindFetchURL,
		Status:       JobStatus(strings.ToUpper(j.Status)),
		Title:        j.Title,
		ParentID:     idPtrStr(j.ParentID),
		BytesDone:    j.BytesDone,
		BytesTotal:   j.BytesTotal,
		FilesDone:    int(j.FilesDone),
		FilesSkipped: int(j.FilesSkipped),
		ErrorCode:    j.ErrorCode,
		ErrorMessage: j.ErrorMessage,
		Attempts:     int(j.Attempts),
		Source:       j.Source,
		CreatedAt:    j.CreatedAt.Time,
		OwnerID:      j.OwnerID.String(),
	}
	if j.Kind == service.JobExtract {
		out.Kind = JobKindExtractArchive
	} else {
		var p struct {
			URL string `json:"url"`
		}
		if json.Unmarshal(j.Params, &p) == nil && p.URL != "" {
			out.URL = &p.URL
		}
	}
	if j.FilesTotal != nil {
		n := int(*j.FilesTotal)
		out.FilesTotal = &n
	}
	if j.StartedAt.Valid {
		t := j.StartedAt.Time
		out.StartedAt = &t
	}
	if j.FinishedAt.Valid {
		t := j.FinishedAt.Time
		out.FinishedAt = &t
	}
	if j.ResultNodeID != nil {
		out.ResultID = j.ResultNodeID.String()
	}
	return out
}

func gqlJobs(rows []store.UserJob) []*Job {
	out := make([]*Job, 0, len(rows))
	for _, r := range rows {
		out = append(out, gqlJob(r))
	}
	return out
}

// jobResult 取任务结果节点;删掉了(或进了回收站)就当没有。
func (r *Resolver) jobResult(ctx context.Context, obj *Job) (*Node, error) {
	if obj.ResultID == "" {
		return nil, nil
	}
	owner, err := uuid.Parse(obj.OwnerID)
	if err != nil {
		return nil, nil
	}
	id, err := uuid.Parse(obj.ResultID)
	if err != nil {
		return nil, nil
	}
	n, err := r.getNodeFull(ctx, owner, id)
	if errors.Is(err, service.ErrNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if n.DeletedAt != nil {
		return nil, nil
	}
	return n, nil
}

// jobSource 提交渠道:安卓 App 的 UA 以 gopan-android 开头。
func jobSource(ctx context.Context) string {
	if strings.HasPrefix(strings.ToLower(service.ClientMetaFrom(ctx).UA), "gopan-android") {
		return "android"
	}
	return service.ChatSourceWeb
}
