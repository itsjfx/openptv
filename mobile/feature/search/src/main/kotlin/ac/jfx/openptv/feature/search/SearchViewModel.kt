/*
 * Copyright 2026 OpenPTV contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ac.jfx.openptv.feature.search

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.StopSearchRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/**
 * Search-screen ViewModel. Owns a [MutableStateFlow] of the current query and exposes a
 * [StateFlow] of [SearchUiState]. The reactive pipeline is:
 *
 *   query → debounce(300 ms) → distinctUntilChanged → flatMapLatest { fetch(it) }
 *
 *  - `debounce` waits for keystrokes to settle so a fast typist only hits the network once.
 *  - `distinctUntilChanged` skips re-fetches when the same term is submitted twice in a row
 *    (e.g. after a config change).
 *  - `flatMapLatest` cancels the in-flight fetch the moment the query changes — exactly the
 *    behaviour the acceptance criteria call out ("backspacing cancels the in-flight call").
 *
 * Errors are mapped to short user-facing strings here so the Compose layer stays free of
 * `throwable.message` formatting. Strings live in code (not `strings.xml`) for the barebones
 * cut; they move to localized resources alongside the multi-module split.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: StopSearchRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val uiState: StateFlow<SearchUiState> = _query
        .debounce(DEBOUNCE_MILLIS)
        .distinctUntilChanged()
        .flatMapLatest { current ->
            if (current.length < MIN_QUERY_LENGTH) {
                flowOf<SearchUiState>(SearchUiState.Idle)
            } else {
                flow<SearchUiState> {
                    emit(SearchUiState.Loading)
                    emit(toUiState(repository.searchStops(current)))
                }
            }
        }
        .onEach { /* leave a hook here for future analytics. */ }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STATE_TIMEOUT_MILLIS),
            initialValue = SearchUiState.Idle,
        )

    /** Called by the Compose text field on every keystroke. */
    fun onQueryChanged(newQuery: String) {
        _query.value = newQuery
    }

    private fun toUiState(result: Result<List<ac.jfx.openptv.core.model.Stop>>): SearchUiState = when (result) {
        is Result.Loading -> SearchUiState.Loading
        is Result.Success -> if (result.data.isEmpty()) {
            SearchUiState.Empty
        } else {
            SearchUiState.Results(result.data)
        }
        is Result.Error -> SearchUiState.Error(result.throwable.toUserFacingReason())
    }

    private fun Throwable.toUserFacingReason(): String = when (this) {
        is HttpException -> when (code()) {
            in 400..499 -> "Search request was rejected (${code()})."
            in 500..599 -> "The proxy is having a bad time (${code()}). Try again."
            else -> "Unexpected HTTP error (${code()})."
        }
        is IOException -> "Couldn't reach the network. Check your connection."
        is kotlinx.serialization.SerializationException ->
            "Search response was malformed. The backend may be out of date."
        else -> message ?: "Something went wrong."
    }

    companion object {
        private const val DEBOUNCE_MILLIS: Long = 300
        private const val STATE_TIMEOUT_MILLIS: Long = 5_000
    }
}
