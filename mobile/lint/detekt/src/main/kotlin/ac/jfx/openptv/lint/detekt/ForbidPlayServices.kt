package ac.jfx.openptv.lint.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * Forbids importing anything under `com.google.android.gms.*` or `com.google.firebase.*`.
 *
 * Rationale: the whole project ships to GrapheneOS, which has no Play Services. Any GMS /
 * Firebase dependency would either crash the app at runtime or force users onto the unofficial
 * Play Services compat layer (`microG`) — neither is acceptable for a "small, fast, ad-free,
 * private" app. The runtime baseline (`:app:dependencyGuard`) catches transitive churn at the
 * artifact level; this detekt rule catches direct source-level imports earlier, before a PR even
 * gets to CI's dependency-guard task.
 *
 * Foundational seam for issue #36 (`LocationProvider` must use `android.location.LocationManager`,
 * not `FusedLocationProviderClient`).
 *
 * Implementation mirrors [ForbidAndroidLog]: import-level detection is enough because no Kotlin
 * production code reaches GMS via fully-qualified names — Android Studio always offers the import.
 * If drive-by FQN usage ever appears, this can be extended with a `visitDotQualifiedExpression`
 * visitor the same way that file describes.
 */
class ForbidPlayServices(config: Config) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = ID,
            severity = Severity.Defect,
            description =
                "Google Play Services / Firebase imports are forbidden. " +
                    "GrapheneOS does not ship Play Services, so anything under " +
                    "com.google.android.gms.* or com.google.firebase.* would crash at runtime. " +
                    "Use AOSP-friendly alternatives (LocationManager, UnifiedPush, MapLibre, etc.).",
            debt = Debt.TEN_MINS,
        )

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val fqName = importDirective.importedFqName?.asString() ?: return
        if (FORBIDDEN_PREFIXES.any { prefix -> fqName == prefix || fqName.startsWith("$prefix.") }) {
            report(CodeSmell(issue, Entity.from(importDirective), issue.description))
        }
    }

    companion object {
        const val ID: String = "ForbidPlayServices"
        private val FORBIDDEN_PREFIXES: List<String> =
            listOf(
                "com.google.android.gms",
                "com.google.firebase",
            )
    }
}
