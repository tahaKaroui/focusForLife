# FocusForLife — Desktop (Linux)

A self-hosted focus/distraction blocker for the desktop. A background daemon enforces
time quotas and blocks distracting **domains** via DNS, driven by a configurable rule
engine (daily quota, hourly limits, hard-block windows, free-time windows).

This is a **build-from-source** project. Real configuration (including any Firebase
credentials) lives in gitignored local files, never in the repo.

> Part of the FocusForLife project. The Android companion lives in a separate
> repository.

---

## Build

Requirements: a recent stable Rust toolchain (`cargo`).

```bash
cargo build --release
# daemon binary: target/release/ffl-daemon
```

Run it against a config file:

```bash
./target/release/ffl-daemon --config config/example.toml
```

If you pass no `--config`, it defaults to `config/example.toml` (the tracked
example). For real use, make your own local config (see below).

For running at boot / as a service, see `docs/systemd-install.md` and
`docs/boot-install.md`.

---

## Customize what gets blocked

Blocked websites live in **one plain-text file**:

```
config/blocked-domains.txt
```

One domain per line, no scheme. Lines starting with `#` are comments. The shipped
list is example defaults — **edit it to match the sites you want blocked**:

```
# config/blocked-domains.txt
youtube.com
instagram.com
site-a.example.com
site-b.example.com
reddit.com
```

You don't need every subdomain — `site-a.example.com` also matches anything ending in
`.site-a.example.com`. Full format notes: `docs/blocklist-format.md`.

---

## Configuration

Rules, time windows, and logging are set in a TOML config. **Don't edit
`config/example.toml` for real use** — it's tracked in git. Instead:

```bash
cp config/example.toml config/linux.local.toml   # *.local.toml is gitignored
# edit config/linux.local.toml
./target/release/ffl-daemon --config config/linux.local.toml
```

Key sections (see `docs/config.md` for the full reference):

- `[rules]` — `daily_quota_minutes`, `hourly_limit_minutes`, grace period.
- `[windows.*]` — hard-block and free-time time ranges.
- `[prompts]` — free-time prompt defaults and timeout.
- `[logging]` — log level.

---

## Optional: cloud sync

The daemon can mirror focus state across devices through a shared Firebase Realtime
Database account. It is **disabled unless you fill in credentials**. Put them in your
`*.local.toml` (never in `example.toml`):

```toml
[sync]
firebase_db_url = "https://your-project-default-rtdb.firebaseio.com"
firebase_api_key = "..."
firebase_email = "you@example.com"
firebase_password = "your-shared-account-password"
```

With these empty, the daemon runs standalone. Setup walkthrough:
`docs/firebase-setup.md`.

---

## Docs

The `docs/` folder covers enforcement internals, the rule engine, the state machine,
systemd/boot installation, and the UI contract.
