# `:core:data`

Repository interfaces (`StopSearchRepository`, `SettingsRepository`) and their default impls,
including `SettingsBackendUrlProvider` which adapts `SettingsRepository` to `:core:network`'s
`BackendUrlProvider` seam. Per NIA convention, interface and impl both live in the same data
module — use cases in `:core:domain` (when it lands) depend on the interface.

## Allowed dependencies

- `:core:model`, `:core:common` (`api` — exposed to consumers).
- `:core:network` (`implementation` — consumers don't need the data source surface).

Depends on this module: `:app`, every `:feature:*`. `:core:data-test` replaces this module's
`DataModule` via `@TestInstallIn` to swap fakes in for instrumented tests.
