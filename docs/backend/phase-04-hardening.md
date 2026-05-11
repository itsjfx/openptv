# Backend Phase 4 — Hardening, Dockerfile, CI

> Goal: make the proxy production-shaped — graceful shutdown, retries, container image, CI builds.

**Depends on:** Phase 3.
**Blocks:** Phase 5 (deploy expects a tagged image).

## Scope

Operational maturity. Nothing user-visible; all of this is for the operator and the edge layer.

## Deliverables

### Resilience
- [ ] Graceful shutdown: signal handler in `main.go`, `Server.Shutdown(ctx)` with a 15 s drain.
- [ ] Upstream retry: on `5xx` or transport error, retry up to 2 times with exponential backoff (250 ms × 2^n + jitter). Don't retry `4xx`.
- [ ] Honour PTV `Retry-After` on `429` — don't retry, surface to client as `503` with the same header.
- [ ] Per-request upstream timeout: 10 s default, `OPENPTV_UPSTREAM_TIMEOUT` override.
- [ ] Trusted proxy handling: when a request arrives from a CIDR in `OPENPTV_TRUSTED_PROXIES`, use the leftmost `X-Forwarded-For` IP for logging; otherwise log `RemoteAddr`. Never trust `X-Forwarded-For` from an untrusted source.

### Container
- [ ] Multi-stage `Dockerfile`:
  - Stage 1: `golang:1.22-alpine` builds a static binary (`CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" ./cmd/openptvd`).
  - Stage 2: `gcr.io/distroless/static-debian12:nonroot`, copy binary, expose 8080, `USER nonroot`.
- [ ] Image size budget: < 25 MB.
- [ ] `.dockerignore` excludes `docs/`, `.git`, `*.md`, tests.

### CI
- [ ] `.github/workflows/backend-ci.yml`:
  - Triggers on PR + push affecting `backend/**`.
  - Steps: `gofmt -l`, `go vet`, `go test -race -count=1 ./...`, `go build`.
  - Coverage report uploaded as artifact.
- [ ] `.github/workflows/backend-release.yml`:
  - Tag-triggered (`backend-v*`).
  - Builds container, pushes to GHCR (`ghcr.io/<owner>/openptv-backend:<tag>`).
  - Image is signed with cosign keyless OIDC.

### Operational docs
- [ ] `backend/README.md`: how to run, env var reference, common troubleshooting.
- [ ] `backend/RUNBOOK.md`: "PTV is returning 5xx" → backoff visible in metrics; "cache hit ratio dropped" → check upstream latency.

## Out of scope

- Helm charts / k8s manifests. Phase 5.
- HSM / Vault for the PTV key. Env var + restricted access on the host is enough at this scale.
- Multi-region. Single instance behind Cloudflare is fine.

## Acceptance criteria

- Sending `SIGTERM` while requests are in-flight: in-flight requests complete; new requests get connection refused; process exits within 15 s.
- Stopping the upstream PTV mid-request: backoff visible in logs and metrics; eventual `503` after retries exhausted.
- Container image runs with no privileges, no shell, < 25 MB.
- CI runs the full test suite on PR; release workflow produces a signed image on tag.

## Test plan

- Integration test for shutdown: start server, send long-running mock upstream call, signal `SIGTERM`, assert 15 s drain holds the response.
- Unit: backoff scheduler emits the right delays for given attempts.
- Manual: `docker run --read-only --network host` exercise.
