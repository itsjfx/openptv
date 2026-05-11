# Backend conventions

Shared rules every backend phase assumes.

## Project conventions

- **Language**: Go 1.22+ (uses `net/http` pattern matching).
- **Dependencies**: standard library only for application code. Allowed test deps: `github.com/stretchr/testify` (optional — `testing` is fine), `github.com/google/go-cmp` for nice diffs. Allowed runtime deps: zero. If a phase needs a dep, it must justify itself in that phase's doc.
- **Module path**: `github.com/<org>/openptv` (TBD; placeholder for now).
- **Layout**: `cmd/openptvd/` for the main binary, `internal/` for everything else. No public `pkg/` — nothing here is intended for external import.
- **Logging**: `log/slog` (stdlib). JSON handler in production (`OPENPTV_LOG_FORMAT=json`), text in dev.
- **Config**: env vars only, validated at boot. No flags, no config files.
- **Errors**: wrap with `fmt.Errorf("...: %w", err)`. Define sentinel errors for upstream failures (`ErrUpstream4xx`, `ErrUpstream5xx`, `ErrUpstreamRateLimit`).
- **Concurrency**: every long-lived goroutine takes a `context.Context` and exits on `<-ctx.Done()`. No `sync.WaitGroup` without a context.

## Layout

```
backend/
├── go.mod
├── go.sum                            (only if test deps creep in)
├── Makefile
├── Dockerfile                        (Phase 4)
├── cmd/
│   └── openptvd/
│       └── main.go                   (~50 lines, wiring only)
└── internal/
    ├── config/
    │   ├── config.go
    │   └── config_test.go
    ├── ptv/
    │   ├── signer.go                 (HMAC; the only file that touches PTV_KEY)
    │   ├── signer_test.go
    │   └── client.go                 (http.Client wrapper for upstream calls)
    ├── proxy/
    │   ├── handler.go                (http.Handler, takes Signer + Cache)
    │   └── handler_test.go
    ├── cache/
    │   ├── cache.go                  (interface)
    │   ├── memory.go                 (LRU impl)
    │   └── memory_test.go
    └── observe/
        ├── log.go                    (slog setup)
        ├── middleware.go             (request middleware: id, latency, log)
        ├── metrics.go                (Phase 3 — prometheus)
        └── observe_test.go
```

## Configuration (env vars)

| Var                       | Default            | Purpose                                                  |
| ------------------------- | ------------------ | -------------------------------------------------------- |
| `OPENPTV_PTV_DEV_ID`      | (required)         | PTV developer ID, included in upstream URLs              |
| `OPENPTV_PTV_KEY`         | (required, secret) | HMAC signing key. Never logged.                          |
| `OPENPTV_PTV_BASE_URL`    | `https://timetableapi.ptv.vic.gov.au` | Override for tests / fakes |
| `OPENPTV_LISTEN_ADDR`     | `:8080`            | HTTP listener                                            |
| `OPENPTV_LOG_FORMAT`      | `text`             | `text` or `json`                                         |
| `OPENPTV_LOG_LEVEL`       | `info`             | `debug` / `info` / `warn` / `error`                      |
| `OPENPTV_TRUSTED_PROXIES` | (none)             | CIDR list of edge IPs whose `X-Forwarded-For` is trusted |
| `OPENPTV_CACHE_BYTES`     | `67108864` (64 MiB)| In-memory cache budget                                   |
| `OPENPTV_PPROF`           | `false`            | Enable `/debug/pprof/*`                                  |

## HTTP

- One handler `proxy.Handler` registered at `/api/v3/`. Strips the `/api` prefix, signs, forwards.
- `/healthz` returns `200` immediately if the process is up.
- `/metrics` from Phase 3.
- Strict host check optional — leave to the edge.
- Request body forwarding: PTV is a read-only API, so reject all non-`GET`/`HEAD` upstream methods with 405. Keeps the surface tiny.
- Default response headers: `Cache-Control: public, max-age=<TTL>` matching the cache TTL for that prefix; `X-Openptv-Cache: hit|miss|bypass`.

## Tests

- Standard library testing. Table-driven where natural.
- `httptest.NewServer` for fake PTV upstream; verify the inbound URL has a correct signature.
- Always run `go test -race ./...` in CI.
- Coverage gate: ≥80% on `internal/ptv` and `internal/cache`; ≥70% overall.

## CI

- GitHub Actions `backend-ci.yml`, triggered on PRs touching `backend/**`.
- Steps: `go vet`, `gofmt -d`, `go test -race -count=1 ./...`, `go build -o /dev/null ./cmd/openptvd`.
- Container build (Phase 4) on tag.

## Style

- `gofmt`-clean, `go vet`-clean. No additional linters required for v1; consider golangci-lint at Phase 4 if churn justifies it.
- Public API in `internal/` is still small — keep doc comments where the type / function is non-obvious.
- Don't introduce a router framework. `http.ServeMux` (Go 1.22+ pattern matching) is enough for this surface.
