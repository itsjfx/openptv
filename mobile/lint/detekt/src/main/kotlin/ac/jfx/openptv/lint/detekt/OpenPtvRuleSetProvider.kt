/*
 * Copyright 2026 OpenPTV contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
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

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            ForbidAndroidLog(config),
        ),
    )

    companion object {
        const val RULE_SET_ID: String = "openptv"
    }
}
