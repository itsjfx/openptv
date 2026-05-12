/*
 * Copyright 2026 OpenPTV contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ac.jfx.openptv.core.data.test

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.StopSearchRepository
import ac.jfx.openptv.core.model.Stop
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hand-written fake for [StopSearchRepository]. Enqueue results with [enqueueResult] (or
 * [enqueueSuccess] / [enqueueError]); each call to [searchStops] dequeues the next one. An
 * empty queue returns an empty Success — never throws, never blocks.
 *
 * `@Inject constructor` so Hilt can build it inside the test graph. `@Singleton` so the same
 * instance backs every consumer in a single test — otherwise an `enqueueSuccess` from a
 * setUp() method would land on a different instance than the ViewModel ends up using.
 */
@Singleton
class FakeStopSearchRepository @Inject constructor() : StopSearchRepository {
    private val queue: ArrayDeque<Result<List<Stop>>> = ArrayDeque()
    val requestedTerms: MutableList<String> = mutableListOf()

    fun enqueueResult(result: Result<List<Stop>>) {
        queue.addLast(result)
    }

    fun enqueueSuccess(stops: List<Stop>) {
        queue.addLast(Result.Success(stops))
    }

    fun enqueueError(throwable: Throwable) {
        queue.addLast(Result.Error(throwable))
    }

    override suspend fun searchStops(term: String): Result<List<Stop>> {
        requestedTerms += term
        return queue.removeFirstOrNull() ?: Result.Success(emptyList())
    }
}
