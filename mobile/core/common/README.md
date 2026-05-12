# `:core:common`

Cross-cutting types that every layer needs: `Result<T>` (sealed type with `Success`/`Error`/`Loading`
flowing from repository to UI) and `Logger` (the only API in the app allowed to call
`android.util.Log`). Also provides a Hilt `LoggerModule` so `AndroidLogger` is reachable from every
consumer's `SingletonComponent` graph without re-binding.

## Allowed dependencies

- No upstream `:core:*` deps — this is the leaf module.
- Android dep is permitted (only) because `AndroidLogger` calls `android.util.Log` directly.

Depends on this module: every `:core:*` and `:feature:*` module transitively.
