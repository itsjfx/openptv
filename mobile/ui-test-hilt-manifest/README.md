# `:ui-test-hilt-manifest`

Empty `HiltComponentActivity` host for Compose UI tests that need Hilt injection. Sits in its own
Gradle module because the activity must be declared in an `AndroidManifest.xml` reachable from
every consumer's androidTest classpath, but the host can't live in `:app` (Compose UI tests in
`:feature:*` need to wrap their content in it without depending on `:app`).

## Allowed dependencies

- Activity + Compose runtime only.
- `dagger.hilt.android.AndroidEntryPoint` annotation (consumers pull `hilt-android-testing` via
  their own `androidTest` classpath).
