# Backend Phase 5 — Deploy + edge (Cloudflare or nginx)

> Goal: pick a host, ship the container, put Cloudflare (or nginx) in front for TLS, rate-limit, and abuse mitigation. The application stays dumb.

**Depends on:** Phase 4 (image exists).
**Blocks:** none. After this, mobile-side production builds can target `https://api.openptv.app` (or whatever the canonical hostname becomes).

## Scope

This is a decision phase plus the operational glue. The spec doesn't pick a host yet — that decision was deferred. The phase delivers a recommendation, the docs to deploy on that host, and the edge config (Cloudflare-first; nginx as fallback for self-hosters).

## Deliverables

### Decision
- [ ] ADR `docs/adr/0002-deployment-target.md` recording the chosen host and why.
  - **Recommendation: Fly.io** — single-region (Sydney), dedicated VM, persistent in-process cache, ~free for this load. Predictable enough for a Spring developer. Cloud Run is the alternative if cold starts become acceptable.

### Fly.io path (if chosen)
- [ ] `backend/fly.toml`: app name, primary region (`syd`), 256 MiB memory, 1 dedicated CPU, internal port 8080, health check on `/healthz`.
- [ ] Secrets via `fly secrets set OPENPTV_PTV_DEV_ID=... OPENPTV_PTV_KEY=...`.
- [ ] Auto-stop disabled (we want the cache warm).
- [ ] Single instance to start; can scale later.

### Cloudflare in front
- [ ] CNAME or A record to the Fly app.
- [ ] Cloudflare WAF rule: block requests where `User-Agent` is empty or `cf.client.bot == true` (allow tagged search engines).
- [ ] Cloudflare Rate Limit Rule: 60 req/min/IP on `/api/v3/*`, 600 req/min/IP overall.
- [ ] Cloudflare Cache Rules: cache `GET /api/v3/*` honouring origin `Cache-Control` (TTL bounded to ≤ 24 h). Bypass cache when `Cache-Control: no-store` is sent.
- [ ] Optional: Turnstile-protected admin path if any introspection endpoint is ever added.

### nginx fallback (for self-hosters)
- [ ] `backend/deploy/nginx.conf` snippet:
  - TLS via Let's Encrypt (`certbot`).
  - `limit_req_zone` 60r/m, burst 10.
  - Proxy buffer sizes tuned for ~50 KiB JSON.
  - `proxy_set_header X-Forwarded-For $remote_addr` and configure `OPENPTV_TRUSTED_PROXIES` to the nginx IP.

### Smoke + monitoring
- [ ] Synthetic check: hit `/api/v3/route_types` from GitHub Actions every 15 min; alert on failure.
- [ ] Status page: minimal static page on GitHub Pages with last-success timestamp pulled from the synthetic check.

## Out of scope

- Multi-region failover.
- Per-user auth. Edge rate-limit is enough for "free public OSS".

## Acceptance criteria

- `fly deploy` (or equivalent) ships a green build; `curl https://<host>/healthz` returns 200 over TLS.
- Cloudflare cache shows `cf-cache-status: HIT` on repeat `route_types` calls.
- Hitting `/api/v3/*` 100 times in 1 minute from one IP triggers the rate-limit (`429`).
- Mobile release build (Phase 11 distribution) points to the new hostname and works end-to-end.

## Test plan

- Manual: deploy, smoke test from mobile.
- Synthetic check is the long-term test plan.

## Implementation notes

- Don't use Cloudflare's "cache everything" rule blindly — it'll cache the `Cache-Control: no-store` 4xx errors. Use Cache Rules with origin-controlled TTL.
- The mobile client should send `Cache-Control: max-age=<short>` so CDN caches don't get too far ahead of fast-moving departures data — but the in-app HTTP cache should also honour the headers we set.
- If `api.openptv.app` is the canonical hostname, register and renew it through Cloudflare to avoid a third-party DNS dependency.

## References

- [Fly.io Go quick start](https://fly.io/docs/languages-and-frameworks/golang/)
- [Cloudflare cache rules](https://developers.cloudflare.com/cache/how-to/cache-rules/)
- [nginx rate limiting](https://www.nginx.com/blog/rate-limiting-nginx/)
