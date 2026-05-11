// Package observe wires the slog logger used across the binary.
//
// Phase 01 keeps this to just the logger constructor. The request-id
// middleware and metrics live in later phases per docs/backend.
package observe

import (
	"io"
	"log/slog"
	"os"
)

// NewLogger returns a *slog.Logger configured per the supplied format.
// Supported formats: "text" (default-ish) and "json". Any other value falls
// back to text so misconfiguration never crashes the binary.
func NewLogger(format string) *slog.Logger {
	return newLoggerTo(os.Stderr, format)
}

// newLoggerTo is the testable seam.
func newLoggerTo(w io.Writer, format string) *slog.Logger {
	opts := &slog.HandlerOptions{Level: slog.LevelInfo}
	var h slog.Handler
	switch format {
	case "json":
		h = slog.NewJSONHandler(w, opts)
	default:
		h = slog.NewTextHandler(w, opts)
	}
	return slog.New(h)
}
