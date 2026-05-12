# `:core:model`

Pure-Kotlin domain types: `Stop`, `RouteType`, `StopId`, `AppSettings`. No Android dependencies,
no serialisation, no Hilt. The architecture rule is one-way: DTOs in `:core:network` may know
about these types and map onto them; these types never know about DTOs.

## Allowed dependencies

- None. JVM-only Kotlin library.

Depends on this module: `:core:data`, `:core:network`, `:core:testing`, every feature.
