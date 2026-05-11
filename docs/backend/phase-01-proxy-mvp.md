# Backend Phase 1 — Proxy MVP

> Goal: a Go binary that signs and proxies any GET to the PTV Timetable API v3, with structured logging and tests. No caching, no metrics, no edge integration yet.

**Depends on:** none.
**Blocks:** every other backend phase, and Mobile Phase 2 (mobile needs an endpoint).

## Scope

Minimum viable proxy. The mobile client makes `GET /api/v3/<path>?<query>` against this service; the service signs `https://timetableapi.ptv.vic.gov.au/v3/<path>?<query>&devid=DEVID&signature=SIG` and pipes the response back.

## Deliverables

### Module bootstrap
- [ ] `go.mod` (Go 1.22+).
- [ ] `Makefile` with `run`, `test`, `vet`, `build`.
- [ ] `cmd/openptvd/main.go` — wires config, signer, upstream client, handler, server. Graceful shutdown (`SIGINT`/`SIGTERM` → `Server.Shutdown`).

### `internal/config`
- [ ] `Load() (Config, error)` reads env vars, validates required ones, returns a struct.
- [ ] Zero log of secret values.
- [ ] Tests: missing required → error with the var name; valid env → struct fields populated.

### `internal/ptv`
- [ ] `Signer` struct with `Sign(rawPath string) (signedURL string, error)`.
- [ ] HMAC-SHA1, uppercase hex, appended as `signature=...`.
- [ ] Constructor `NewSigner(devID, key, baseURL string)` validates.
- [ ] `Client` wraps `*http.Client` for upstream GET; respects ctx; returns body bytes + status + headers.
- [ ] Tests:
  - Signature output matches a known fixture (paste a published example or compute one in the test setup).
  - `Sign` is path-only; trailing slash, query params, and special chars all encode correctly.
  - `Client.Get` propagates context cancellation.

### `internal/proxy`
- [ ] `Handler` implements `http.Handler`. Constructor takes `*ptv.Client`, `*ptv.Signer`, `*slog.Logger`.
- [ ] On `GET /api/v3/<rest>`:
  - Strip `signature` and `devid` from query (don't trust client).
  - Build the URL path `/v3/<rest>`, sign, fetch, copy response (status, content-type, body).
  - Map upstream `429`/5xx to `503` with `Retry-After`; pass 4xx through with cleaned body.
- [ ] Reject methods other than `GET` and `HEAD` with `405`.
- [ ] Reject paths not under `/api/v3/` with `404`.
- [ ] Tests with `httptest.NewServer` as fake upstream:
  - Happy path: 200 round-trip.
  - Upstream 4xx: passes through, body intact.
  - Upstream 5xx: returns 503.
  - Upstream 429: returns 503 with `Retry-After`.
  - Method not allowed.
  - Client supplies `signature` query → stripped before signing.

### `internal/observe`
- [ ] `Logger` constructor returning `*slog.Logger` with text/JSON handler driven by config.
- [ ] Request-logging middleware that adds a request id (`X-Request-Id` if present, else uuid), measures latency, and logs `{path, status, latency_ms, request_id, upstream_status}`.
- [ ] Tests for the middleware (latency populated, request id present, no panic on writer nil case).

### Health
- [ ] `/healthz` registered on the same mux; always 200.

## Out of scope

- Caching (Phase 2). Every request hits PTV.
- Metrics (Phase 3).
- Retry / hedging (Phase 4).
- Edge auth / Cloudflare (Phase 5).
- TLS termination — assume the runtime sits behind a TLS terminator.

## Acceptance criteria

- `OPENPTV_PTV_DEV_ID=... OPENPTV_PTV_KEY=... go run ./cmd/openptvd` starts and serves on `:8080`.
- `curl localhost:8080/healthz` → `200 OK`.
- `curl localhost:8080/api/v3/route_types` returns the same body as a hand-signed request to PTV directly.
- `curl localhost:8080/api/v3/route_types?signature=fake&devid=fake` still works (params stripped before signing).
- `curl -XPOST localhost:8080/api/v3/anything` → `405`.
- `go test -race ./...` is green; `go vet ./...` is clean.

## Test plan

- Unit: signer, config, request middleware as listed above.
- Integration: spin up a fake upstream with `httptest.NewServer` that asserts inbound signature is correct, and run the handler end-to-end.
- Manual: hit a real PTV endpoint with the binary running locally; compare response body to a hand-signed request from `curl`.

## Implementation notes

- HMAC sample for fixture in tests:
  ```
  key   = "9c132d31-6a30-4cac-8d8b-8a1970834799"   // public PTV doc example
  url   = "/v3/route_types?devid=3000176"
  sig   = HMACSHA1(key, url)                       // pre-known
  ```
  Use a real published example so that any future change in the signer surfaces immediately.
- The PTV docs say *"the URL must include `devid` before signing"*. Build the URL once, with `devid` already appended, then HMAC the path + query string, then append `signature=`.
- `http.ServeMux` supports `mux.HandleFunc("GET /api/v3/", handler)` from Go 1.22; no router needed.
- Don't allocate a fresh `http.Client` per request. One client, with a sensible `Transport` (max idle conns 100, 10 s timeout).

## References

- [PTV API authentication](https://timetableapi.ptv.vic.gov.au/swagger/docs/v3) → "How to use the API".
- Go 1.22 [net/http enhanced patterns](https://go.dev/blog/routing-enhancements).
