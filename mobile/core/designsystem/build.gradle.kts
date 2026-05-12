// `:core:designsystem` — owns the Compose theme entry point (`OpenPtvTheme`)
// and (eventually) the palette + typography. Applies both the library and
// library-compose convention plugins so it gets the Compose BOM pinning.
plugins {
    id("openptv.android.library")
    id("openptv.android.library.compose")
}

android {
    namespace = "ac.jfx.openptv.core.designsystem"
}

dependencies {
    // Compose deps come from `openptv.android.library.compose`; nothing extra
    // needed here yet. ReadYou palette / typography land in the phase that
    // ships them.
}
