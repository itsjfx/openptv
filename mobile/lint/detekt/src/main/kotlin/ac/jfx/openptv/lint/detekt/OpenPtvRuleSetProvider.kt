package ac.jfx.openptv.lint.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * SPI entry point that registers OpenPTV's custom rules with detekt. Discovered via the
 * `META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider` resource — without that
 * file, detekt will not load the rules even if this class is on its plugin classpath.
 */
class OpenPtvRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = RULE_SET_ID

    override fun instance(config: Config): RuleSet =
        RuleSet(
            ruleSetId,
            listOf(
                ForbidAndroidLog(config),
                ForbidPlayServices(config),
            ),
        )

    companion object {
        const val RULE_SET_ID: String = "openptv"
    }
}
