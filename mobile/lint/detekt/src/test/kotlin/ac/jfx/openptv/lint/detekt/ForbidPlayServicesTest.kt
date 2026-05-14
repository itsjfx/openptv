package ac.jfx.openptv.lint.detekt

import com.google.common.truth.Truth.assertThat
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import org.junit.Test

/**
 * Unit tests for [ForbidPlayServices]. Mirrors the snippet-based testing strategy [ForbidAndroidLogTest] uses.
 */
class ForbidPlayServicesTest {
    private val rule = ForbidPlayServices(Config.empty)

    @Test
    fun `flags fused location provider import`() {
        val findings =
            rule.lint(
                """
                package sample
                import com.google.android.gms.location.FusedLocationProviderClient
                fun build() { val c: FusedLocationProviderClient? = null }
                """.trimIndent(),
            )
        assertThat(findings).hasSize(1)
        assertThat(findings.first().id).isEqualTo(ForbidPlayServices.ID)
    }

    @Test
    fun `flags firebase analytics import`() {
        val findings =
            rule.lint(
                """
                package sample
                import com.google.firebase.analytics.FirebaseAnalytics
                fun ping() { FirebaseAnalytics.getInstance(null) }
                """.trimIndent(),
            )
        assertThat(findings).hasSize(1)
    }

    @Test
    fun `flags wildcard import of gms member`() {
        val findings =
            rule.lint(
                """
                package sample
                import com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY
                fun use() = PRIORITY_BALANCED_POWER_ACCURACY
                """.trimIndent(),
            )
        assertThat(findings).hasSize(1)
    }

    @Test
    fun `does not flag unrelated google imports`() {
        // `com.google.common.truth.Truth` (Truth assertion library) and Guava live under
        // `com.google.*` but are NOT Play Services / Firebase — they ship as plain JARs and run
        // fine on GrapheneOS.
        val findings =
            rule.lint(
                """
                package sample
                import com.google.common.truth.Truth
                fun assertSomething() { Truth.assertThat(1).isEqualTo(1) }
                """.trimIndent(),
            )
        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not flag android location manager`() {
        // The whole point of this rule is to drive callers toward `android.location` instead of
        // GMS — make sure the AOSP import is allowed.
        val findings =
            rule.lint(
                """
                package sample
                import android.location.LocationManager
                fun mgr(m: LocationManager) {}
                """.trimIndent(),
            )
        assertThat(findings).isEmpty()
    }
}
