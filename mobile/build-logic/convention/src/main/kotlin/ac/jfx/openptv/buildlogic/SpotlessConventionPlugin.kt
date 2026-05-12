package ac.jfx.openptv.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

// `openptv.spotless` — placeholder. Issue #12 is responsible for actually
// applying `com.diffplug.spotless` and configuring ktlint formats; doing it
// here would force JDK 17 on the convention classpath (Spotless 8 requires it),
// which doesn't pay off until the formats are wired. The plugin id is reserved
// so #12 can fill this in without churning every module's build script.
class SpotlessConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        // Intentionally empty. #12 fills this in.
    }
}
