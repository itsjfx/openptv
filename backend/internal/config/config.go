// Package config loads runtime configuration from environment variables.
//
// Phase 01 (barebones) only wires the subset needed to sign and proxy a
// request. Later phases extend the set per docs/backend/00-conventions.md.
package config

import (
	"errors"
	"fmt"
	"os"
	"strings"
)

// Defaults applied when the matching env var is empty.
const (
	DefaultListenAddr = ":8080"
	DefaultLogFormat  = "text"
	DefaultBaseURL    = "https://timetableapi.ptv.vic.gov.au"
)

// Config holds the resolved runtime configuration.
//
// Secret values (Key) must never be logged. Stringer is intentionally not
// implemented to avoid accidental logging via %v / %+v.
type Config struct {
	DevID      string
	Key        string
	BaseURL    string
	ListenAddr string
	LogFormat  string
}

// Load reads env vars, validates required fields, and returns Config.
//
// Required: OPENPTV_PTV_DEV_ID, OPENPTV_PTV_KEY.
// Optional: OPENPTV_PTV_BASE_URL, OPENPTV_LISTEN_ADDR, OPENPTV_LOG_FORMAT.
func Load() (Config, error) {
	return loadFrom(os.Getenv)
}

// loadFrom is the testable seam; production callers use Load().
func loadFrom(get func(string) string) (Config, error) {
	cfg := Config{
		DevID:      strings.TrimSpace(get("OPENPTV_PTV_DEV_ID")),
		Key:        get("OPENPTV_PTV_KEY"),
		BaseURL:    strings.TrimSpace(get("OPENPTV_PTV_BASE_URL")),
		ListenAddr: strings.TrimSpace(get("OPENPTV_LISTEN_ADDR")),
		LogFormat:  strings.TrimSpace(get("OPENPTV_LOG_FORMAT")),
	}

	var missing []string
	if cfg.DevID == "" {
		missing = append(missing, "OPENPTV_PTV_DEV_ID")
	}
	if cfg.Key == "" {
		missing = append(missing, "OPENPTV_PTV_KEY")
	}
	if len(missing) > 0 {
		return Config{}, fmt.Errorf("config: missing required env var(s): %s", strings.Join(missing, ", "))
	}

	if cfg.BaseURL == "" {
		cfg.BaseURL = DefaultBaseURL
	}
	if cfg.ListenAddr == "" {
		cfg.ListenAddr = DefaultListenAddr
	}
	if cfg.LogFormat == "" {
		cfg.LogFormat = DefaultLogFormat
	}
	switch cfg.LogFormat {
	case "text", "json":
	default:
		return Config{}, fmt.Errorf("config: OPENPTV_LOG_FORMAT must be 'text' or 'json', got %q", cfg.LogFormat)
	}
	return cfg, nil
}

// ErrMissing is returned (wrapped) when a required env var is missing.
var ErrMissing = errors.New("missing required env var")
