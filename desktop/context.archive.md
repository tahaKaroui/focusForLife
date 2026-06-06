# FocusForLife - Archive Context

This file keeps implementation detail that is no longer useful in the main context, but may still matter for future work.

## Previously established target assumptions

- OS: Ubuntu 24.01
- Hardware: HP Victus
- Initial rules in config:
  - Daily quota: 60 minutes
  - Continuous limit: 10 minutes
  - Cooldown: 60 minutes
  - Free-time windows:
    - 15:00 to 16:00
    - 21:00 to 23:00
  - Hard block window:
    - 23:00 to 11:00
- YouTube is treated like any other blocked site.

## Implemented so far

- Rust workspace exists with:
  - daemon
  - shared
  - ui
- Daemon can:
  - load and validate config
  - open SQLite state
  - evaluate hard-block, cooldown, and quota states
  - simulate tracking
  - tail a hit stream for live activity
  - write an Unbound blocklist file
- Storage layer already tracks:
  - daily usage
  - session count
  - cooldown end
  - prompt decision state
  - session history
- Session tracker already:
  - increments usage
  - ends sessions on idle
  - triggers cooldown after the continuous limit
- UI exists as a mock shell with:
  - fixed status labels
  - simulated prompt window
  - prompt test mode
- Shared IPC/config types exist for daemon/UI integration.

## Still incomplete when archived

- Free-time prompt decisions are not yet fully wired into real allow/block behavior.
- UI is not yet driven by live daemon state.
- Rust-side enforcement currently covers blocklist generation more than full firewall control.
- End-to-end systemd/watcher recovery behavior still needs validation.

## Why this remains relevant

These notes explain why the next work focuses on integration and completion rather than initial scaffolding.
