# `:core:network`

The Retrofit/OkHttp graph plus the wire DTOs (`StopDto`, `SearchResponseDto`) and their domain
mappers. `BackendApiService` is `internal` so wire types never leak past this module's boundary —
consumers in `:core:data` see only `StopSearchDataSource` (a public interface) and the domain
models from `:core:model`. Owns `BackendUrlProvider`, a `fun interface` whose impl lives in
`:core:data` so URL composition doesn't appear in the network seam's signatures.

## Allowed dependencies

- `:core:model` (DTO mappers target domain types).
- `:core:common` (transitive).

Depends on this module: `:core:data` (via `implementation`).
