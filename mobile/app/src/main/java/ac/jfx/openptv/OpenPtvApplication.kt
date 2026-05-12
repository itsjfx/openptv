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

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. `@HiltAndroidApp` triggers Hilt's compile-time code generation,
 * making this the root of the dependency graph (analogous to `@SpringBootApplication`).
 */
@HiltAndroidApp
class OpenPtvApplication : Application()
