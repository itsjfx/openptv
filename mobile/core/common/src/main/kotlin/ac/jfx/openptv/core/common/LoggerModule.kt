/*
 * Copyright 2026 OpenPTV contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ac.jfx.openptv.core.common

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the project-wide [Logger] to its Android-backed implementation. `@Binds` rather than
 * `@Provides` because the binding is a plain interface-to-impl pair — Hilt generates less code
 * for `@Binds`.
 *
 * Lives in `:core:common` because that's the only module that knows about both the interface
 * and the implementation. Consumers (every feature, every repo) depend on `:core:common` and
 * pick this up automatically through Hilt's `SingletonComponent`.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class LoggerModule {
    @Binds
    @Singleton
    internal abstract fun bindLogger(impl: AndroidLogger): Logger
}
