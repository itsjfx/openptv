# `:core:navigation`

Holds the typed `AppNavKey` route definitions (Navigation 3) used by the top-level nav graph. Lives
in its own module so `:feature:*` modules can navigate to each other's destinations without taking
a dependency on one another — they all reference the route through `:core:navigation`.

## Allowed dependencies

- `kotlinx-serialization` (route keys are `@Serializable`).
- No `:core:data` / `:core:network` / `:feature:*` deps.
