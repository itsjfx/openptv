// Package proxy holds the http.Handler that signs and forwards PTV requests.
package proxy

import (
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
