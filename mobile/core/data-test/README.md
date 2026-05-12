# `:core:data-test`

Hand-written fakes for repository interfaces (`FakeSettingsRepository`, `FakeStopSearchRepository`)
plus a `FakeDataModule` annotated with `@TestInstallIn(replaces = [DataModule::class])` so feature
androidTests inherit fakes by declaring `androidTestImplementation(project(":core:data-test"))` —
no per-test `@UninstallModules` boilerplate.

## Allowed dependencies

- `:core:data`, `:core:model`, `:core:common` (`api` — the production interfaces consumers stub
  against).
- `hilt-android-testing` (`implementation` — `@TestInstallIn` is an internal detail; consumers
  bring their own copy via `HiltAndroidRule`).
