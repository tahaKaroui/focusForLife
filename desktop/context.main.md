# FocusForLife - Main Context

## Big picture

Build a strict Ubuntu focus-enforcement system that blocks distracting websites at the system level, survives reboot, and is inconvenient to bypass even with sudo access.

This is a personal, machine-specific setup for this Ubuntu laptop. Hard-coded details are acceptable when they improve reliability.

## Product goals

- Enforce focus rules with:
  - daily quota
  - continuous session limit
  - cooldown after limit
  - hard blocked hours
  - gated free-time windows
- Block distracting domains at the system level.
- Force traffic through local enforcement and prevent easy DNS bypass.
- Show clear user feedback:
  - always-visible status/timer overlay
  - explicit blocked state/page
- Restore enforcement automatically after reboot or service disruption.

## Constraints

- No cloud backend.
- No multi-user product scope.
- Security goal is deterrence and friction, not absolute root-proofing.

## Current direction

- Local root daemon owns rule evaluation, tracking, and enforcement.
- DNS sinkhole plus firewall rules enforce network blocking.
- Local persistence stores config, usage, cooldowns, and prompt decisions.
- UI handles overlay and prompt interactions.
- Systemd keeps core services alive.

## Next work

- Finish end-to-end enforcement behavior so configured rules fully drive allow/block decisions.
- Complete prompt/free-time flow and connect it to daemon state and UI behavior.
- Finish firewall and DNS-bypass prevention path, including resolver routing and DoH/DoT blocking.
- Replace mock UI state with live daemon-backed status and prompts.
- Validate boot-time/service recovery behavior with systemd and watcher components.

## Relevant archive

Detailed implementation status and past progress live in `context.archive.md`.
