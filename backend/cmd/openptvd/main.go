// Command openptvd is the OpenPTV proxy: signs and forwards GETs to the PTV
// Timetable API v3.
//
// Wiring only. Behaviour lives in internal/{config,ptv,proxy,observe}.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/itsjfx/openptv/internal/config"
	"github.com/itsjfx/openptv/internal/observe"
	"github.com/itsjfx/openptv/internal/proxy"
	"github.com/itsjfx/openptv/internal/ptv"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "fatal:", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}

	logger := observe.NewLogger(cfg.LogFormat)
	// Intentional: log the dev id (it's not secret) but never the key.
	logger.Info("starting openptvd",
		slog.String("listen_addr", cfg.ListenAddr),
		slog.String("log_format", cfg.LogFormat),
		slog.String("base_url", cfg.BaseURL),
		slog.String("dev_id", cfg.DevID),
	)

	signer, err := ptv.NewSigner(cfg.DevID, cfg.Key)
	if err != nil {
		return fmt.Errorf("ptv signer: %w", err)
	}
	client := ptv.NewClient(cfg.BaseURL)

	handler, err := proxy.NewHandler(client, signer, logger)
	if err != nil {
		return fmt.Errorf("proxy handler: %w", err)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok\n"))
	})
	// Go 1.22 pattern matching: register for the prefix so anything under
	// /api/v3/ reaches the handler.
	mux.Handle("/api/v3/", handler)

	srv := &http.Server{
		Addr:              cfg.ListenAddr,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	// Graceful shutdown: SIGINT/SIGTERM trigger Server.Shutdown with a
	// bounded deadline.
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	errCh := make(chan error, 1)
	go func() {
		logger.Info("listening", slog.String("addr", cfg.ListenAddr))
		err := srv.ListenAndServe()
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
			return
		}
		errCh <- nil
	}()

	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
		logger.Info("shutdown signal received")
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		return fmt.Errorf("shutdown: %w", err)
	}
	logger.Info("shutdown complete")
	return nil
}
