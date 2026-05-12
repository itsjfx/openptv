/*
 * Copyright 2026 OpenPTV contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ac.jfx.openptv

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Confirms the JUnit 4 + Truth test harness is wired. Once Phase 02 lands the multi-module split,
 * proper unit tests will live in `:core:*` and `:feature:*` modules; this file is intentionally trivial.
 */
class ExampleTest {
    @Test
    fun `harness is wired`() {
        assertThat(2 + 2).isEqualTo(4)
    }
}
