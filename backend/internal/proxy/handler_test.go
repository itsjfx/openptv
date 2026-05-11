package proxy

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/itsjfx/openptv/internal/ptv"
)

// newTestHandler wires a proxy handler backed by a caller-controlled fake
// upstream.
func newTestHandler(t *testing.T, upstream *httptest.Server) *Handler {
	t.Helper()
	signer, err := ptv.NewSigner("3000176", "9c132d31-6a30-4cac-8d8b-8a1970834799")
	if err != nil {
		t.Fatalf("NewSigner: %v", err)
	}
	client := ptv.NewClient(upstream.URL)
	h, err := NewHandler(client, signer, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatalf("NewHandler: %v", err)
	}
	return h
}

// TestHandler_HappyPath asserts that:
//   - the inbound upstream URL carries a signature query param
//   - client-supplied signature/devid are stripped before signing
//   - the response body, content-type, and status round-trip
func TestHandler_HappyPath(t *testing.T) {
	t.Parallel()

	var seenURL string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seenURL = r.URL.String()
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	defer upstream.Close()

	h := newTestHandler(t, upstream)

	// Client deliberately includes a fake signature / devid; the handler
	// must drop them.
	req := httptest.NewRequest(http.MethodGet, "/api/v3/route_types?signature=fake&devid=fake", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	if got := rec.Header().Get("Content-Type"); got != "application/json" {
		t.Errorf("content-type = %q, want application/json", got)
	}
	if got := rec.Body.String(); got != `{"ok":true}` {
		t.Errorf("body = %q, want %q", got, `{"ok":true}`)
	}

	// Upstream URL must be path /v3/route_types with devid=3000176 and a
	// signature param — and must not contain the client's "fake" value.
	if !strings.HasPrefix(seenURL, "/v3/route_types") {
		t.Errorf("upstream path = %q, want prefix /v3/route_types", seenURL)
	}
	if !strings.Contains(seenURL, "signature=") {
		t.Errorf("upstream URL missing signature= param: %q", seenURL)
	}
	if !strings.Contains(seenURL, "devid=3000176") {
		t.Errorf("upstream URL missing devid=3000176: %q", seenURL)
	}
	if strings.Contains(seenURL, "fake") {
		t.Errorf("upstream URL leaked client-supplied params: %q", seenURL)
	}
}

func TestHandler_RejectsPOST(t *testing.T) {
	t.Parallel()
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Fatalf("upstream should not be called; got %s %s", r.Method, r.URL)
	}))
	defer upstream.Close()

	h := newTestHandler(t, upstream)
	req := httptest.NewRequest(http.MethodPost, "/api/v3/anything", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want 405", rec.Code)
	}
	if got := rec.Header().Get("Allow"); !strings.Contains(got, "GET") {
		t.Errorf("Allow header = %q, want it to include GET", got)
	}
}
