// Package proxy holds the http.Handler that signs and forwards PTV requests.
package proxy

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"

	"github.com/itsjfx/openptv/internal/ptv"
)

// apiPrefix is the inbound path prefix the handler responds to. Anything
// outside this returns 404.
const apiPrefix = "/api/v3/"

// stopsLocationPrefix is the upstream path prefix for the nearby-stops
// endpoint (/v3/stops/location/{lat},{lng}). For this endpoint we strip the
// per-stop `routes` array from the response — see trimStopsLocation.
const stopsLocationPrefix = "/v3/stops/location/"

// maxUpstreamBytes caps how much of PTV's response we'll copy back to the
// client. PTV responses are JSON and well under this in practice; the cap is
// a belt-and-braces against a misbehaving or compromised upstream.
const maxUpstreamBytes = 5 << 20 // 5 MiB

// Handler is the HTTP handler that signs and proxies to PTV.
type Handler struct {
	client *ptv.Client
	signer *ptv.Signer
	logger *slog.Logger
}

// NewHandler wires the proxy handler. All arguments are required.
func NewHandler(client *ptv.Client, signer *ptv.Signer, logger *slog.Logger) (*Handler, error) {
	if client == nil {
		return nil, errors.New("proxy: client is required")
	}
	if signer == nil {
		return nil, errors.New("proxy: signer is required")
	}
	if logger == nil {
		return nil, errors.New("proxy: logger is required")
	}
	return &Handler{client: client, signer: signer, logger: logger}, nil
}

// ServeHTTP implements http.Handler.
func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// 404 for paths not under /api/v3/. The mux already routes /api/v3/ here,
	// but defence in depth is cheap.
	if !strings.HasPrefix(r.URL.Path, apiPrefix) {
		http.NotFound(w, r)
		return
	}

	// PTV is a read-only API; only GET and HEAD make sense upstream.
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// Build the upstream path /v3/<rest> from the inbound /api/v3/<rest>.
	rest := strings.TrimPrefix(r.URL.Path, apiPrefix)
	upstreamPath := "/v3/" + rest

	// Strip client-supplied auth params; never trust them.
	q := r.URL.Query()
	q.Del("signature")
	q.Del("devid")

	pathWithQuery := upstreamPath
	if encoded := q.Encode(); encoded != "" {
		pathWithQuery = upstreamPath + "?" + encoded
	}

	signedPath, err := h.signer.Sign(pathWithQuery)
	if err != nil {
		h.logger.ErrorContext(r.Context(), "sign failed", slog.String("path", upstreamPath), slog.String("err", err.Error()))
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	resp, err := h.client.Get(r.Context(), signedPath)
	if err != nil {
		h.logger.ErrorContext(r.Context(), "upstream fetch failed", slog.String("path", upstreamPath), slog.String("err", err.Error()))
		http.Error(w, "bad gateway", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	// Map upstream 429 + 5xx to 503 with Retry-After. Pass 4xx (except 429)
	// through verbatim.
	switch {
	case resp.StatusCode == http.StatusTooManyRequests, resp.StatusCode >= 500:
		h.logger.WarnContext(r.Context(), "upstream unavailable",
			slog.String("path", upstreamPath),
			slog.Int("upstream_status", resp.StatusCode),
		)
		// Propagate Retry-After if the upstream supplied one; otherwise a
		// short fixed value.
		ra := resp.Header.Get("Retry-After")
		if ra == "" {
			ra = "5"
		}
		w.Header().Set("Retry-After", ra)
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.WriteHeader(http.StatusServiceUnavailable)
		fmt.Fprintln(w, "upstream unavailable")
		return
	}

	// Happy / 4xx path: copy content-type, status, body.
	if ct := resp.Header.Get("Content-Type"); ct != "" {
		w.Header().Set("Content-Type", ct)
	}

	// For the nearby-stops endpoint, trim the heavy per-stop `routes` array the
	// mobile pins never read. We only attempt this on a 2xx JSON response; on
	// any decode failure we fall back to a verbatim copy so the endpoint can
	// never be broken by an unexpected upstream shape. PTV has no query param
	// to omit `routes` upstream, so trimming here is the only lever. This is
	// the dominant bytes-on-wire cost for that endpoint (~39 KB -> ~7.5 KB).
	if resp.StatusCode == http.StatusOK && strings.HasPrefix(upstreamPath, stopsLocationPrefix) {
		body, err := io.ReadAll(io.LimitReader(resp.Body, maxUpstreamBytes))
		if err != nil {
			h.logger.WarnContext(r.Context(), "response read interrupted", slog.String("err", err.Error()))
			w.WriteHeader(resp.StatusCode)
			_, _ = w.Write(body)
			return
		}
		if trimmed, ok := trimStopsLocation(body); ok {
			body = trimmed
		} else {
			h.logger.WarnContext(r.Context(), "stops/location trim skipped; passing through verbatim",
				slog.String("path", upstreamPath),
			)
		}
		w.WriteHeader(resp.StatusCode)
		_, _ = w.Write(body)
		return
	}

	w.WriteHeader(resp.StatusCode)
	n, err := io.Copy(w, io.LimitReader(resp.Body, maxUpstreamBytes))
	if err != nil {
		// At this point the headers and status are flushed; we can only log.
		h.logger.WarnContext(r.Context(), "response copy interrupted", slog.String("err", err.Error()))
	}
	if n == maxUpstreamBytes {
		h.logger.WarnContext(r.Context(), "upstream response truncated at cap",
			slog.String("path", upstreamPath),
			slog.Int64("bytes", n),
		)
	}
}

// trimStopsLocation removes the per-stop `routes` array from a
// /v3/stops/location response body. The nearby map pins consume only
// stop_id/stop_name/stop_latitude/stop_longitude/route_type, so `routes`
// (each carrying route_name, route_gtfs_id, geopath, etc.) is dead weight on
// the wire.
//
// It is deliberately surgical: it decodes the top level and the `stops` array
// as raw messages, deletes only the `routes` key from each stop object, and
// leaves every other field — top-level (disruptions, status) and per-stop —
// byte-for-byte intact. It returns (body, false) on any structural surprise
// (not an object, stops not an array, a stop that isn't an object, or a
// re-marshal error) so the caller can fall back to a verbatim copy.
func trimStopsLocation(body []byte) ([]byte, bool) {
	var top map[string]json.RawMessage
	if err := json.Unmarshal(body, &top); err != nil {
		return body, false
	}
	rawStops, ok := top["stops"]
	if !ok {
		// No stops key at all (e.g. an error envelope) — nothing to trim.
		return body, false
	}
	var stops []map[string]json.RawMessage
	if err := json.Unmarshal(rawStops, &stops); err != nil {
		return body, false
	}

	changed := false
	for _, stop := range stops {
		if _, has := stop["routes"]; has {
			delete(stop, "routes")
			changed = true
		}
	}
	if !changed {
		// Nothing to strip; avoid re-marshalling (which would also reorder
		// keys for no benefit).
		return body, true
	}

	newStops, err := json.Marshal(stops)
	if err != nil {
		return body, false
	}
	top["stops"] = newStops

	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(top); err != nil {
		return body, false
	}
	// Encoder appends a trailing newline; trim it to keep the body tight.
	return bytes.TrimRight(buf.Bytes(), "\n"), true
}
