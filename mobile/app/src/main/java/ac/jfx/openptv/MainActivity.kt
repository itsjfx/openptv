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

import ac.jfx.openptv.core.designsystem.OpenPtvTheme
import ac.jfx.openptv.ui.App
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-Activity Compose host. All UI lives inside [App]; this class exists only to
 * bridge the platform Activity lifecycle into Compose and to satisfy Hilt's entry-point contract.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenPtvTheme {
                App()
            }
        }
    }
}
