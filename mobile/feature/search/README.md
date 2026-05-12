# `:feature:search`

Stop-search screen: text field plus a list of results. The `SearchViewModel` consumes
`StopSearchRepository` from `:core:data` and emits a `SearchUiState` (`Idle` / `Loading` / `Empty`
/ `Results` / `Error`) that `SearchScreen` renders. UI tests use the Hilt-aware
`HiltComponentActivity` from `:ui-test-hilt-manifest`.

## Allowed dependencies

- `:core:data` (search repository interface).
- `:core:model` (`Stop`, `RouteType`).
- `:core:common` (`Result`).
- `:core:designsystem` (theme + components).
- `:core:navigation` (route key consumed by the host nav graph).
- No other `:feature:*` deps — features don't depend on each other.
