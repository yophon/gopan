package mcpserver

import (
	"context"
	"github.com/yophon/gopan/server/internal/service"
	"sync"
	"testing"
)

func TestMCPConcurrentUploadReservations(t *testing.T) {
	e := setupEnv(t)
	ctx := context.Background()
	if _, err := e.pool.Exec(ctx, `UPDATE users SET quota_bytes=16 WHERE id=$1`, e.alice); err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	results := make(chan error, 8)
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := e.uploads.PrepareAgentUpload(ctx, e.alice, nil, "reserve.txt", 8, "text/plain", nil, "single_put", nil)
			results <- err
		}()
	}
	wg.Wait()
	close(results)
	success := 0
	for err := range results {
		if err == nil {
			success++
		} else if e, ok := err.(*service.Error); !ok || e.Code != "QUOTA_EXCEEDED" {
			t.Fatal(err)
		}
	}
	if success != 2 {
		t.Fatalf("concurrent reservations exceeded quota: %d", success)
	}
}
