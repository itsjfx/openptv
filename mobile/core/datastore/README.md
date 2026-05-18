# `:core:datastore`

Typed `Preference` DSL on top of [Preferences DataStore][datastore]. Owns:

- the sealed `Preference<T>` hierarchy + per-setting subtypes (`ThemeModePreference`,
  `DynamicColourPreference`, `FavouritesSortPreference`),
- one `compositionLocalOf { default }` per preference (`LocalThemeMode`, `LocalDynamicColour`,
  `LocalFavouritesSort`),
- standalone preference types that don't live behind a composition local
  (`MapRouteTypeFilterPreference` — read/written directly by `:feature:nearby`'s ViewModel,
  not a UI-wide setting),
- the `SettingsProvider` Composable that wraps app content at the root and pushes every
  collected value down through `CompositionLocalProvider`,
- the Hilt-bound `UserPreferencesDataStore` singleton for non-Compose consumers (workers,
  ViewModels that need to write).

Pattern ported from ReadYou's `infrastructure/preference/` (Apache 2.0).

## To add a new setting

It's three steps. The whole module is shaped so that adding one preference means **one new
file + one line** in `SettingsProvider`.

1. **New file**: `src/main/kotlin/ac/jfx/openptv/core/datastore/preference/MyPreference.kt`.
   - Add a sealed `MyPreference` subclass of `Preference<T>` with one `data object` per case.
   - Add a `Preferences.Key<*>` entry in `PreferenceKeys.kt` (the central registry).
   - Add a companion `default` and a `fromValue(stored: String?)` parser. `fromValue` must
     tolerate `null` (no stored value yet) and unknown strings (forward-compat — a newer build
     wrote a case this build doesn't know).
   - Add a `compositionLocalOf { MyPreference.default }` named `LocalMyPreference` next to the
     sealed class. The fallback default means previews and tests without a `SettingsProvider`
     still render.

2. **Wire on the facade**: add a `val myPreference: Flow<MyPreference>` to
   `UserPreferencesDataStore` that reads the new key and decodes via `fromValue`.

3. **Wire on the provider**: add one line to `SettingsProvider` that collects the flow and
   pushes the value through the local:
   ```kotlin
   val myPreference by userPreferences.myPreference.collectAsStateWithLifecycle(
       initialValue = remember { MyPreference.default },
   )
   CompositionLocalProvider(
       …existing locals…,
       LocalMyPreference provides myPreference,
       content = content,
   )
   ```

## Don'ts

- **Don't rename a `PreferenceKeys` constant once it ships.** The wire format is the public API
  of the on-disk preferences file. If a rename is truly needed, write a DataStore migration
  that reads the old key and writes the new one.
- **Don't add raw `Preferences.Key` reads outside this module.** Repositories that need a
  preference get a typed accessor on `UserPreferencesDataStore`; Compose code reads the local.
  Keeping the wire format private means a future Proto DataStore migration is one module's job.
- **Don't make `:core:designsystem` depend on this module.** The designsystem stays
  decoupled; the call site in `:app` maps the local to the designsystem's `ThemeMode` enum.

## Tests

- `UserPreferencesDataStoreTest` — write each preference, re-open the DataStore against the
  same on-disk file, read back. Catches wire-format breakage.
- `PreferenceFromValueTest` — every known case round-trips through `fromValue`, and
  unknown / null fall back to `default`.
- `SettingsProviderTest` — composes each preference's child reader and asserts the collected
  value reaches the composition local (not just the fallback default).

[datastore]: https://developer.android.com/topic/libraries/architecture/datastore
