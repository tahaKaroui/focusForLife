# Contributing to FocusForLife

Thanks for your interest! This is a small personal project that went open
source, so the process is informal.

## Ground rules

- **Never commit credentials.** Real Firebase credentials belong in gitignored
  files only: `desktop/config/*.local.toml`, `android/local.properties`, and
  `android/app/google-services.json`. PRs containing secrets will be closed.
- **Keep the philosophy.** Blocklists stay compiled-in; features that make the
  blocker trivially easy to bypass at runtime defeat the purpose.
- **Match the surrounding style.** Rust follows `rustfmt` defaults; Kotlin
  follows the existing code's conventions.

## Building

- Desktop: `cd desktop && cargo build` (Linux daemon: crate `ffl-daemon`;
  Windows daemon `ffl-windows` needs a Windows target).
- Android: `cd android && ./gradlew assembleDebug` — needs an Android SDK and a
  `google-services.json` from your own Firebase project (see
  [`docs/firebase-setup.md`](docs/firebase-setup.md)).

## Submitting changes

1. Fork, branch from `main`.
2. Keep commits scoped with clear messages (`feat(android): …`,
   `refactor(daemon): …`).
3. Make sure `cargo build` / `gradlew compileDebugKotlin` pass.
4. Open a PR describing what and why.

## Cross-platform invariants

If you touch sync or the timer model, keep these identical on every platform:

- the data schema `/users/{uid}/devices/{device_id}` →
  `{ date, daily_seconds, hourly_used_seconds, hourly_stamp }`
- the hour-stamp formula: `year * 1_000_000 + day_of_year * 100 + hour`
