# `:core:designsystem`

Owns the Material 3 theme (`OpenPtvTheme { content() }`) — dynamic colour on Android 12+, hand-tuned
fallback below. Every screen wraps its content in this theme; feature modules pull tokens from here
rather than declaring colours directly.

## Allowed dependencies

- Compose runtime + material3 (via the BOM).
- No `:core:data` / `:core:network` / `:feature:*` deps — this is a presentation-only leaf.
