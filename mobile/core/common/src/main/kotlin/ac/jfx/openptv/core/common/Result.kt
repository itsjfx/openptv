package ac.jfx.openptv.core.common

/**
 * Three-state result wrapper that flows from repositories up through ViewModels. Same shape as
 * Now-in-Android's `core/common/.../result/Result.kt`. Promoted to `:core:common` in the
 * multi-module follow-up.
 *
 *  - [Loading] — work is in flight; UI typically shows a spinner.
 *  - [Success] — work completed; carries the typed payload.
 *  - [Error] — work failed; carries the underlying [Throwable] so the ViewModel can decide on
 *    a user-facing reason. ViewModels never expose the throwable directly.
 *
 * Repositories return this; ViewModels map it onto a screen-specific `UiState`.
 */
sealed interface Result<out T> {
    data object Loading : Result<Nothing>
    data class Success<T>(val data: T) : Result<T>
    data class Error(val throwable: Throwable) : Result<Nothing>
}
