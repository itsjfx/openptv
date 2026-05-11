# Backend Phase 3 — Observability

> Goal: see what the proxy is doing in production: request rate, error rate, cache hit ratio, latency distribution. Plus pprof when something goes wrong.

**Depends on:** Phase 2.
**Blocks:** none.

## Scope

Prometheus metrics + structured logs + optional pprof. No tracing yet — the proxy is one hop, traces add complexity for marginal value at this scale.

## Deliverables

### `internal/observe/metrics.go`
- [ ] Decision: stdlib-only Prometheus exposition (write the format manually) **or** take the single dep `github.com/prometheus/client_golang`. Recommendation: take the dep here. Prometheus exposition format is simple but evolves; the client library is mature, widely vetted, and worth the import.
- [ ] Counters: `openptv_requests_total{path_class,status,cache}` (path_class is the prefix bucket from Phase 2's TTL table).
- [ ] Histogram: `openptv_request_duration_seconds{path_class,cache}` with buckets `[0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10]`.
- [ ] Counters: `openptv_upstream_failures_total{kind}` (`4xx`, `5xx`, `timeout`, `network`).
- [ ] Gauge: `openptv_cache_bytes`, `openptv_cache_entries`.

### `internal/observe/middleware.go` updates
- [ ] Wrap `ResponseWriter` to capture status; record latency; emit metrics on each request.
- [ ] Continue emitting structured logs at INFO; downgrade health-check logs to DEBUG.

### Endpoints
- [ ] `/metrics` registered on the same mux. Optional: bind on a separate port (`OPENPTV_METRICS_ADDR=:9090`) so Cloudflare doesn't see it.
- [ ] `/debug/pprof/*` registered only if `OPENPTV_PPROF=true`.

### Smoke
- [ ] After a few proxy hits in dev, `curl :9090/metrics | grep openptv_` shows expected series.

## Out of scope

- OpenTelemetry tracing. Add later if a span across cache → upstream → response would actually help debug.
- Log shipping. The runtime is responsible (stdout → logging stack).

## Acceptance criteria

- Hitting `/api/v3/route_types` 5 times produces:
  - `openptv_requests_total{path_class="route_types",status="200",cache="miss"} = 1`
  - `openptv_requests_total{path_class="route_types",status="200",cache="hit"} = 4`
- Histogram has at least one observation in a `<= 1` bucket.
- pprof off by default; `OPENPTV_PPROF=true` enables `/debug/pprof/profile`.

## Test plan

- Unit: middleware emits the right metric labels; status capture works.
- Integration: scrape `/metrics` in test, parse, assert series exist.
- Manual: load-test with `hey` or `vegeta`, observe metrics.

## Implementation notes

- Path classification is the cache TTL table reused — extract `internal/proxy.PathClass(p string) string` once.
- Avoid metric cardinality blow-ups: don't tag with raw path; tag with `path_class` only.
