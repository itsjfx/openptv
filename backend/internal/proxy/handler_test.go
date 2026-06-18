package proxy

import (
	"encoding/json"
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

// stopsLocationBody mirrors the real PTV /v3/stops/location shape closely
// enough to exercise the trim: each stop carries the fields the pins use plus
// the heavy `routes` array we strip, and the envelope carries sibling keys
// (disruptions, status) that must survive untouched.
const stopsLocationBody = `{` +
	`"stops":[` +
	`{"stop_id":2720,"stop_name":"Bourke St Mall","route_type":1,"stop_latitude":-37.81,"stop_longitude":144.96,` +
	`"routes":[{"route_type":1,"route_id":725,"route_name":"North Coburg - Flinders Street","route_number":"19","route_gtfs_id":"3-019","geopath":[]}]},` +
	`{"stop_id":2721,"stop_name":"Elizabeth St","route_type":3,"stop_latitude":-37.82,"stop_longitude":144.97,` +
	`"routes":[{"route_type":3,"route_id":1,"route_name":"Some Train","route_number":"","route_gtfs_id":"2-XYZ","geopath":[]}]}` +
	`],` +
	`"disruptions":{},` +
	`"status":{"version":"3.0","health":1}}`

// TestHandler_StopsLocationTrimsRoutes asserts that the nearby-stops endpoint
// response has the per-stop `routes` array stripped, while every other field
// (the pin fields plus the envelope's disruptions/status) round-trips, and the
// payload shrinks.
func TestHandler_StopsLocationTrimsRoutes(t *testing.T) {
	t.Parallel()

	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(stopsLocationBody))
	}))
	defer upstream.Close()

	h := newTestHandler(t, upstream)
	req := httptest.NewRequest(http.MethodGet, "/api/v3/stops/location/-37.81,144.96?max_results=100&max_distance=500", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}

	// The body must still be valid JSON with no `routes` key anywhere.
	out := rec.Body.String()
	if strings.Contains(out, `"routes"`) {
		t.Errorf("response still contains routes: %s", out)
	}
	if strings.Contains(out, "geopath") || strings.Contains(out, "route_gtfs_id") {
		t.Errorf("response still contains heavy route fields: %s", out)
	}

	var got map[string]any
	if err := json.Unmarshal([]byte(out), &got); err != nil {
		t.Fatalf("response is not valid JSON: %v\n%s", err, out)
	}

	// Envelope siblings survive.
	if _, ok := got["disruptions"]; !ok {
		t.Errorf("disruptions key dropped: %s", out)
	}
	if _, ok := got["status"]; !ok {
		t.Errorf("status key dropped: %s", out)
	}

	stops, ok := got["stops"].([]any)
	if !ok || len(stops) != 2 {
		t.Fatalf("stops not a 2-element array: %#v", got["stops"])
	}

	// Every pin field survives on each stop.
	wantKeys := []string{"stop_id", "stop_name", "route_type", "stop_latitude", "stop_longitude"}
	for i, s := range stops {
		stop, ok := s.(map[string]any)
		if !ok {
			t.Fatalf("stop %d not an object: %#v", i, s)
		}
		if _, has := stop["routes"]; has {
			t.Errorf("stop %d still has routes", i)
		}
		for _, k := range wantKeys {
			if _, has := stop[k]; !has {
				t.Errorf("stop %d missing pin field %q: %#v", i, k, stop)
			}
		}
	}

	// Sanity: the trimmed body is meaningfully smaller than upstream's.
	if len(out) >= len(stopsLocationBody) {
		t.Errorf("trimmed body not smaller: %d >= %d", len(out), len(stopsLocationBody))
	}
}

// TestHandler_NonStopsLocationPassthroughKeepsRoutes asserts the trim is
// scoped to stops/location only: another endpoint whose body happens to carry
// a `routes` array is copied through verbatim, byte-for-byte.
func TestHandler_NonStopsLocationPassthroughKeepsRoutes(t *testing.T) {
	t.Parallel()

	// A stops/{id}/route_type/{type} style body that legitimately carries routes.
	const detailBody = `{"stop":{"stop_id":2720},"routes":[{"route_id":725,"route_name":"North Coburg"}],"status":{"health":1}}`

	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(detailBody))
	}))
	defer upstream.Close()

	h := newTestHandler(t, upstream)
	req := httptest.NewRequest(http.MethodGet, "/api/v3/stops/2720/route_type/1", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	if got := rec.Body.String(); got != detailBody {
		t.Errorf("non-stops/location body was modified.\n got: %s\nwant: %s", got, detailBody)
	}
}

// TestTrimStopsLocation_Fallbacks covers the structural-surprise paths where
// the helper must return the body unchanged so ServeHTTP falls back to a
// verbatim copy.
func TestTrimStopsLocation_Fallbacks(t *testing.T) {
	t.Parallel()

	cases := []struct {
		name   string
		body   string
		wantOK bool
	}{
		{"malformed json", `{not json`, false},
		{"top level not object", `[1,2,3]`, false},
		{"no stops key", `{"status":{"health":1}}`, false},
		{"stops not array", `{"stops":{"oops":true}}`, false},
		{"stop not object", `{"stops":[1,2,3]}`, false},
		{"no routes present", `{"stops":[{"stop_id":1}],"status":{}}`, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			out, ok := trimStopsLocation([]byte(tc.body))
			if ok != tc.wantOK {
				t.Errorf("ok = %v, want %v", ok, tc.wantOK)
			}
			// On any non-trim path the body must be returned untouched.
			if string(out) != tc.body {
				t.Errorf("body mutated on fallback: got %q want %q", out, tc.body)
			}
		})
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
