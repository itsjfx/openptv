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

import com.google.common.truth.Truth.assertThat
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import org.junit.Test

/**
 * Unit tests for [ForbidAndroidLog]. Uses detekt's `lint(...)` test harness to feed snippets
 * through the rule without standing up a full Gradle compile.
 */
class ForbidAndroidLogTest {
    private val rule = ForbidAndroidLog(Config.empty)

    @Test
    fun `flags android util Log import`() {
        val findings =
            rule.lint(
                """
                package sample
                import android.util.Log
                fun bark() { Log.d("tag", "msg") }
                """.trimIndent(),
            )
        assertThat(findings).hasSize(1)
        assertThat(findings.first().id).isEqualTo(ForbidAndroidLog.ID)
    }

    @Test
    fun `flags wildcard import of android util Log members`() {
        val findings =
            rule.lint(
                """
                package sample
                import android.util.Log.d
                fun bark() { d("tag", "msg") }
                """.trimIndent(),
            )
        assertThat(findings).hasSize(1)
    }

    @Test
    fun `does not flag unrelated android imports`() {
        val findings =
            rule.lint(
                """
                package sample
                import android.os.Bundle
                fun untouched(bundle: Bundle?) {}
                """.trimIndent(),
            )
        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not flag a Log class from another package`() {
        val findings =
            rule.lint(
                """
                package sample
                import com.example.Log
                fun bark(log: Log) { }
                """.trimIndent(),
            )
        assertThat(findings).isEmpty()
    }
}
