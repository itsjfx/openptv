# Backend Phase 2 — In-memory caching

> Goal: cache PTV responses by path+query, with per-prefix TTLs and a bounded byte budget. Correctness first, then performance.

**Depends on:** Backend Phase 1.
**Blocks:** Phase 3 (metrics will surface cache hit ratio).

## Scope

Add an in-process LRU cache between the proxy handler and the upstream client. Cache is keyed by the canonical path + sorted query (sans `signature`, `devid`). TTLs are per-prefix; sane defaults baked in but overridable via env.

## Deliverables

### `internal/cache`
- [ ] `Entry { Body []byte; ContentType string; ExpiresAt time.Time }`.
- [ ] `Cache` interface: `Get(key string) (Entry, bool)`, `Set(key string, e Entry)`, `Bytes() int64`.
- [ ] `MemoryCache` implementation:
  - Bounded by total stored bytes (`OPENPTV_CACHE_BYTES`).
  - LRU eviction via a doubly-linked list + map (or `container/list`).
  - Concurrency-safe (`sync.Mutex` is fine; bench before reaching for `sync.Map`).
  - Lazy expiry on `Get`; periodic janitor on a goroutine for cold entries.
- [ ] Tests: TTL expiry, LRU eviction order, byte budget enforcement, concurrent Get/Set under `-race`.

### `internal/proxy` updates
- [ ] `TtlPolicy` table (sorted prefix → TTL):
  | Prefix                          | TTL     |
  | ------------------------------- | ------- |
  | `/v3/route_types`               | `24h`   |
  | `/v3/stops/`                    | `1h`    |
  | `/v3/routes/`                   | `1h`    |
  | `/v3/directions/`               | `1h`    |
  | `/v3/search/`                   | `10m`   |
  | `/v3/patterns/`                 | `5m`    |
  | `/v3/disruptions/`              | `1m`    |
  | `/v3/departures/`               | `15s`   |
  | (anything else)                 | `0` (bypass) |
- [ ] On request: build canonical key, lookup. On hit: serve from cache, set `X-Openptv-Cache: hit`, set `Cache-Control: public, max-age=<remaining-ttl>`.
- [ ] On miss: fetch upstream, store, set `X-Openptv-Cache: miss`.
- [ ] Cache only `200 OK` responses (don't cache 4xx, even though PTV may answer with cacheable error bodies).
- [ ] Single-flight: concurrent identical misses share one upstream call (`golang.org/x/sync/singleflight` would be ideal — if we don't take the dep, write a tiny mutex map).

### Canonical key
- [ ] Drop `signature`, `devid` from query.
- [ ] Sort remaining query keys lexically; URL-decode + re-encode consistently.
- [ ] Lowercase the path (PTV is case-insensitive).
- [ ] Tests: equivalent inputs produce equal keys; `?b=2&a=1` ≡ `?a=1&b=2`.

## Out of scope

- Distributed cache (Redis, Memcached). Maybe Phase 5+ if traffic justifies it. The edge layer (Cloudflare) covers most of this concern anyway.
- Stale-while-revalidate. Nice-to-have for departures; defer.

## Acceptance criteria

- Hit ratio for repeated requests against `/api/v3/route_types` is 100% after the first call.
- Departures (`/api/v3/departures/...`) miss after 15 s.
- Setting `OPENPTV_CACHE_BYTES=1024` and flooding distinct keys evicts in LRU order; total cache bytes never exceed 1024.
- Two concurrent identical cold requests result in exactly one upstream call.
- `go test -race ./internal/cache/...` is green.

## Test plan

- Unit: TTL expiry under fake clock; LRU order across N inserts; byte budget enforcement; concurrent access (`-race`).
- Integration: end-to-end via the handler — assert `X-Openptv-Cache` header and that upstream is called the expected number of times (`httptest` + counter).
- Bench: `go test -bench=.` on the cache to confirm sub-microsecond Get on a warm 10k-key cache.

## Implementation notes

- Don't store `*http.Response`; copy out `Body` to bytes once and keep `ContentType`. Streaming back from a cached entry is just `w.Write(entry.Body)`.
- Locking: a single mutex around the LRU is fine for this load. Re-evaluate at Phase 3 if metrics show contention.
- Single-flight without a dep: a `map[string]*sync.WaitGroup` guarded by the same mutex. Caller registers, fetches, broadcasts.
- Be defensive about `Cache-Control: no-store` requests from clients — the mobile client never sends that, but if a debug tool does, bypass cache.
