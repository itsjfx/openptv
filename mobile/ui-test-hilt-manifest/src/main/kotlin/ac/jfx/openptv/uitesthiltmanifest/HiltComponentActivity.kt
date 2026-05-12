/*
 * Copyright 2026 OpenPTV contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ac.jfx.openptv.uitesthiltmanifest

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Empty Hilt-aware `ComponentActivity` used by feature androidTests as the host for
 * `createAndroidComposeRule<HiltComponentActivity>()`. Without an `@AndroidEntryPoint`
 * activity declared in *some* manifest, Hilt can't inject anything into a Compose UI test;
 * production manifests declare `MainActivity`, but that drags the whole app into every test.
 * This module exists to give Hilt the smallest possible activity to attach to. Borrowed verbatim
 * from Now in Android.
 */
@AndroidEntryPoint
class HiltComponentActivity : ComponentActivity()
