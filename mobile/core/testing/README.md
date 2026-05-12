# `:core:testing`

Shared test infrastructure: Object Mothers for domain types (`StopMother.aStop().build()`), the
`MainDispatcherRule` JUnit rule for ViewModel coroutine tests, and `OpenPtvTestRunner` (the
`AndroidJUnitRunner` subclass that swaps `HiltTestApplication` in for instrumented tests). Lives in
`src/main/` rather than `src/test/` so consumers pull it into both unit and instrumented test
classpaths via `testImplementation(project(":core:testing"))`.

## Allowed dependencies

- `:core:model` (`api` — Object Mothers build domain types).
- `kotlinx-coroutines-test` (`api` — `MainDispatcherRule` exposes `TestDispatcher`).
- `androidx-test-runner`, `hilt-android-testing` (`implementation` — for the test runner).
