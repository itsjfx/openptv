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
 * Forbids importing `android.util.Log` outside `:core:common.AndroidLogger`.
 *
 * `:core:common.Logger` is the project's single sanctioned logging seam (see the type's KDoc for
 * the rationale: testability, JVM-test variants, no Timber dep). `AndroidLogger` is the one
 * implementation allowed to call into the platform `Log` API directly; every other caller MUST
 * inject `Logger`. This rule keeps that boundary enforceable in CI.
 *
 * Implementation note: import-level detection is sufficient because production Kotlin never reaches
 * `android.util.Log` through fully-qualified names — the moment someone tries to log they import
 * the class. If we ever see drive-by fully-qualified usage, this can be extended with a
 * `visitDotQualifiedExpression` visitor.
 */
class ForbidAndroidLog(config: Config) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = ID,
            severity = Severity.Defect,
            description =
                "Direct android.util.Log usage is forbidden. " +
                    "Inject ac.jfx.openptv.core.common.Logger instead. The only sanctioned " +
                    "Android Log call site is :core:common.AndroidLogger.",
            debt = Debt.FIVE_MINS,
        )

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val fqName = importDirective.importedFqName?.asString() ?: return
        if (fqName == ANDROID_LOG_FQN || fqName.startsWith("$ANDROID_LOG_FQN.")) {
            report(CodeSmell(issue, Entity.from(importDirective), issue.description))
        }
    }

    companion object {
        const val ID: String = "ForbidAndroidLog"
        private const val ANDROID_LOG_FQN: String = "android.util.Log"
    }
}
