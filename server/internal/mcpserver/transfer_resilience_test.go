package mcpserver

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"sync"
	"testing"
	"time"

	"github.com/yophon/gopan/server/internal/service"
)

func TestMCPMultipartDisconnectResumeAndInstantRetry(t *testing.T) {
	e := setupEnv(t)
	ctx := context.Background()
	token, _, err := e.tokens.Create(ctx, e.alice, "resilience", service.MCPScopes)
	if err != nil {
		t.Fatal(err)
	}
	sess := e.connect(t, token)
	data := make([]byte, 65<<20)
	for i := range data {
		data[i] = byte((i*31 + i/251) % 251)
	}
	args := map[string]any{"name": "large.bin", "size": len(data), "transport": "auto", "idempotency_key": "large-disconnect"}
	var up UploadOutput
	callTool(t, sess, "prepare_upload", args, &up)
	if up.TotalParts < 3 {
		t.Fatal("test requires multiple parts")
	}
	partSize := int(up.PartSize)
	httpPutBytes(t, up.PartURLs[0].URL, data[:partSize])
	// Close a TCP connection after sending only a prefix of the declared part.
	u, _ := url.Parse(up.PartURLs[1].URL)
	conn, err := net.DialTimeout("tcp", u.Host, 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	_, err = fmt.Fprintf(conn, "PUT %s HTTP/1.1\r\nHost: %s\r\nContent-Length: %d\r\nConnection: close\r\n\r\n", u.RequestURI(), u.Host, partSize)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = conn.Write(data[partSize : partSize+4096])
	_ = conn.Close()
	// Reconnect a fresh protocol client and recover from the persisted key.
	restarted := e.connect(t, token)
	var resume UploadOutput
	callTool(t, restarted, "prepare_upload", args, &resume)
	if resume.UploadID != up.UploadID || len(resume.UploadedParts) != 1 || resume.UploadedParts[0] != 1 {
		t.Fatalf("resume lost completed part: %+v", resume.UploadedParts)
	}
	callToolErr(t, restarted, "complete_upload", map[string]any{"upload_id": up.UploadID})
	var parts UploadOutput
	callTool(t, restarted, "get_upload_parts", map[string]any{"upload_id": up.UploadID, "first_part": 2, "limit": 100}, &parts)
	var wg sync.WaitGroup
	failures := make(chan error, len(parts.PartURLs))
	for _, part := range parts.PartURLs {
		wg.Add(1)
		go func() {
			defer wg.Done()
			start := (part.PartNumber - 1) * partSize
			end := min(start+partSize, len(data))
			req, _ := http.NewRequest(http.MethodPut, part.URL, bytes.NewReader(data[start:end]))
			res, err := http.DefaultClient.Do(req)
			if err != nil {
				failures <- err
				return
			}
			io.Copy(io.Discard, res.Body)
			res.Body.Close()
			if res.StatusCode != 200 {
				failures <- fmt.Errorf("part %d status %d", part.PartNumber, res.StatusCode)
			}
		}()
	}
	wg.Wait()
	close(failures)
	for err := range failures {
		t.Fatal(err)
	}
	callTool(t, restarted, "complete_upload", map[string]any{"upload_id": up.UploadID}, &up)
	if _, err = e.uploads.ProcessNextAgentTransfer(ctx); err != nil {
		t.Fatal(err)
	}
	hash := sha256Hex(data)
	blob, err := e.q.GetBlobBySha256(ctx, hash)
	if err != nil {
		t.Fatal(err)
	}
	if err = e.q.MarkBlobVerified(ctx, blob.ID); err != nil {
		t.Fatal(err)
	}
	if err = e.q.MarkTransferReadyBySha(ctx, &hash); err != nil {
		t.Fatal(err)
	}
	callTool(t, restarted, "wait_upload", map[string]any{"upload_id": up.UploadID}, &up)
	if up.Status != "ready" || up.Node == nil {
		t.Fatal("resume finalization failed")
	}
	var dl DownloadOutput
	callTool(t, restarted, "prepare_download", map[string]any{"node_id": up.Node.ID}, &dl)
	res, err := http.Get(dl.URL)
	if err != nil {
		t.Fatal(err)
	}
	got := sha256.New()
	_, err = io.Copy(got, res.Body)
	res.Body.Close()
	if err != nil || hex.EncodeToString(got.Sum(nil)) != hash {
		t.Fatal("resumed download differs")
	}
	// Concurrent instant uploads must create one logical file and charge once.
	key := "instant-once"
	ids := make(chan string, 6)
	errs := make(chan error, 6)
	for range 6 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			out, err := e.uploads.PrepareAgentUpload(ctx, e.alice, nil, "instant.bin", int64(len(data)), "application/octet-stream", &hash, "auto", &key)
			if err != nil {
				errs <- err
				return
			}
			ids <- out.Node.ID.String()
		}()
	}
	wg.Wait()
	close(ids)
	close(errs)
	for err := range errs {
		t.Fatal(err)
	}
	var id string
	for current := range ids {
		if id != "" && current != id {
			t.Fatal("instant retry duplicated node")
		}
		id = current
	}
	urow, err := e.q.GetUserByID(ctx, e.alice)
	if err != nil || urow.UsedBytes != int64(len(data))*2 {
		t.Fatalf("duplicate quota charge: %d, %v", urow.UsedBytes, err)
	}
	if _, err = e.uploads.PrepareAgentUpload(ctx, e.alice, nil, "different-name.bin", int64(len(data)), "application/octet-stream", &hash, "auto", &key); err == nil {
		t.Fatal("name change reused idempotency key")
	}
	t.Logf("Verified %d bytes over %d parts, interrupted TCP upload, reconnect, concurrent part upload, download equality and concurrent instant retries", len(data), resume.TotalParts)
}
