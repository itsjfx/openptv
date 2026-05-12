// `:core:model` — pure-Kotlin domain types (`Stop`, `StopId`, `RouteType`,
// `AppSettings`). No Android deps, so we use the JVM library convention plugin
// instead of `openptv.android.library`. That keeps the consumer classpath
// honest: anyone importing a model type can't accidentally drag in `android.*`.
plugins {
    id("openptv.jvm.library")
}
