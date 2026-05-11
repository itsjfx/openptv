package ptv

import (
	"context"
	"fmt"
	"net/http"
	"strings"
	"time"
)

// Client is a thin wrapper around *http.Client tuned for upstream GETs to the
// PTV Timetable API. A single shared transport is used per process; do not
// allocate a fresh Client per request.
type Client struct {
	baseURL string
	http    *http.Client
}

// NewClient returns a Client pointed at the given base URL (no trailing
// slash). The base URL typically comes from config.
func NewClient(baseURL string) *Client {
	transport := &http.Transport{
		MaxIdleConns:        100,
		MaxIdleConnsPerHost: 10,
		IdleConnTimeout:     90 * time.Second,
	}
	return &Client{
		baseURL: strings.TrimRight(baseURL, "/"),
		http: &http.Client{
			Timeout:   10 * time.Second,
			Transport: transport,
		},
	}
}

// Get issues an upstream GET against baseURL+signedPath. The caller owns
// closing the returned response body.
func (c *Client) Get(ctx context.Context, signedPath string) (*http.Response, error) {
	if !strings.HasPrefix(signedPath, "/") {
		return nil, fmt.Errorf("ptv: signedPath must start with /, got %q", signedPath)
	}
	url := c.baseURL + signedPath
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("ptv: build request: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("ptv: upstream GET: %w", err)
	}
	return resp, nil
}
