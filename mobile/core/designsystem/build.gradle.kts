// `:core:designsystem` — owns the Compose theme entry point (`OpenPtvTheme`)
// and (eventually) the palette + typography. Applies both the library and
// library-compose convention plugins so it gets the Compose BOM pinning.
//
// Also applies `openptv.android.library.roborazzi` — this module owns the
// screenshot baseline for the theme tokens (the visual contract every feature
// module renders against). See `README.md` for the record/verify workflow.
plugins {
    id("openptv.android.library")
    id("openptv.android.library.compose")
    id("openptv.android.library.roborazzi")
}

android {
    namespace = "ac.jfx.openptv.core.designsystem"
}

dependencies {
    // Compose deps come from `openptv.android.library.compose`; nothing extra
    // needed here yet. ReadYou palette / typography land in the phase that
    // ships them.
}
