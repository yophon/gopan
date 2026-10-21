package service

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/yophon/gopan/server/internal/store"
)

type pushMessage struct {
	ID        int64
	Kind      string
	Level     string
	Title     string
	Body      string
	URL       string
	CreatedAt time.Time
}

// send 按渠道类型拼请求发出去(doc/22 三.2)。2xx 算成功,其余把状态码和回应开头带回去给界面看。
func (n *Notifier) send(ctx context.Context, ch store.NotifyChannel, m pushMessage) error {
	ctx, cancel := context.WithTimeout(ctx, notifySendTimeout)
	defer cancel()
	req, err := buildPush(ctx, ch, m)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", "gopan-notify")
	resp, err := n.client.Do(req)
	if err != nil {
		return fmt.Errorf("%s", describeNetErr(err))
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 == 2 {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
		return nil
	}
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
	msg := strings.TrimSpace(string(b))
	if ch.Secret != "" {
		msg = strings.ReplaceAll(msg, ch.Secret, "***") // 别把令牌回显到界面上
	}
	return fmt.Errorf("HTTP %d %s", resp.StatusCode, truncateRunes(msg, 200))
}

func buildPush(ctx context.Context, ch store.NotifyChannel, m pushMessage) (*http.Request, error) {
	text := m.Body
	switch ch.Kind {
	case ChWebhook:
		body, _ := json.Marshal(map[string]any{
			"id": m.ID, "kind": m.Kind, "level": m.Level, "title": m.Title, "body": m.Body,
			"url": m.URL, "created_at": m.CreatedAt.UTC().Format(time.RFC3339),
		})
		req, err := jsonRequest(ctx, ch.Url, body)
		if err != nil {
			return nil, err
		}
		if ch.Secret != "" {
			mac := hmac.New(sha256.New, []byte(ch.Secret))
			mac.Write(body)
			req.Header.Set("X-Gopan-Signature", "sha256="+hex.EncodeToString(mac.Sum(nil)))
		}
		return req, nil
	case ChNtfy:
		prio := map[string]int{LvInfo: 3, LvWarn: 4, LvAlert: 5}[m.Level]
		p := map[string]any{"topic": ch.Topic, "title": m.Title, "message": orDash(text), "priority": prio, "tags": []string{"gopan"}}
		if m.URL != "" {
			p["click"] = m.URL
		}
		body, _ := json.Marshal(p)
		req, err := jsonRequest(ctx, ch.Url, body) // JSON 发布:POST 到服务器根地址,topic 写在正文里
		if err != nil {
			return nil, err
		}
		if ch.Secret != "" {
			req.Header.Set("Authorization", "Bearer "+ch.Secret)
		}
		return req, nil
	case ChBark:
		p := map[string]any{"device_key": ch.Secret, "title": m.Title, "body": orDash(text), "group": "gopan"}
		if m.URL != "" {
			p["url"] = m.URL
		}
		if m.Level == LvAlert {
			p["level"] = "timeSensitive"
		}
		body, _ := json.Marshal(p)
		return jsonRequest(ctx, ch.Url+"/push", body)
	case ChTelegram:
		msg := m.Title
		if text != "" {
			msg += "\n" + text
		}
		if m.URL != "" {
			msg += "\n" + m.URL
		}
		body, _ := json.Marshal(map[string]any{"chat_id": ch.Topic, "text": msg, "disable_web_page_preview": true})
		return jsonRequest(ctx, ch.Url+"/bot"+url.PathEscape(ch.Secret)+"/sendMessage", body)
	}
	return nil, fmt.Errorf("未知渠道类型 %s", ch.Kind)
}

func jsonRequest(ctx context.Context, u string, body []byte) (*http.Request, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("地址不对")
	}
	req.Header.Set("Content-Type", "application/json")
	return req, nil
}

func orDash(s string) string {
	if strings.TrimSpace(s) == "" {
		return "-"
	}
	return s
}
