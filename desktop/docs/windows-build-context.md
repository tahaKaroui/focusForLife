# Windows Build Context — FocusForLife

This file summarises every change made to bring the Windows daemon and UI to a
working state on this machine. Reference it before touching anything in
`crates/windows-daemon`.

---

## Branch
`feature/windows-app` on `https://github.com/tahaKaroui/focusforlife`

---

## Environment (this machine)

| Item | Value |
|---|---|
| OS | Windows 11 Home |
| User | HOSTNAME (`C:\Users\user`) |
| Rust toolchain | `stable-x86_64-pc-windows-gnu` (GNU, NOT MSVC) |
| Rust installed via | `winget install Rustlang.Rustup` |
| C compiler | MinGW-w64 GCC 15.2 via MSYS2 (`C:\msys64\mingw64\bin\gcc.exe`) |
| MSYS2 installed via | `winget install MSYS2.MSYS2` |
| SQLite dev lib | `pacman -S mingw-w64-x86_64-sqlite3` (MSYS2) |

### Why GNU toolchain (not MSVC)?
MSVC toolchain was installed by rustup but `link.exe` was not found (Visual
Studio Build Tools not installed). Switching to the GNU toolchain + MinGW GCC
was faster than installing VS Build Tools.

### Build command
```
export PATH="/c/msys64/mingw64/bin:$PATH"
/c/Users/user/.cargo/bin/cargo build -p ffl-windows --release
/c/Users/user/.cargo/bin/cargo build -p ffl-windows --bin ffl-windows-ui --features ui --release
```

---

## Code changes

### 1. `crates/windows-daemon/src/cdp_tracker.rs`
**Problem:** `hwnd.0 == 0` does not compile with `windows` crate 0.58 — `HWND.0`
is `*mut c_void`, not `usize`.

**Fix:** replaced both null checks:
```rust
// before
if hwnd.0 == 0 {
// after
if hwnd.0 == std::ptr::null_mut() {
```

### 2. `crates/windows-daemon/src/main.rs`
Added a status file write at the end of every tick so the UI can read it
without any IPC:
```rust
let daily_quota_s = config.rules.daily_quota_minutes * 60;
let hourly_limit_s = config.rules.hourly_limit_minutes * 60;
let status_json = format!(
    "{{\"state\":\"{}\",\"daily_remaining\":{},\"hourly_remaining\":{}}}",
    match result.state { ... },
    daily_quota_s.saturating_sub(combined_daily),
    hourly_limit_s.saturating_sub(combined_hourly),
);
let _ = fs::write(r"C:\ProgramData\FocusForLife\status.json", &status_json);
```

### 3. `crates/windows-daemon/Cargo.toml`
Added `eframe` as an optional dependency and declared the UI binary:
```toml
eframe = { version = "0.27", optional = true }

[[bin]]
name = "ffl-windows-ui"
path = "src/ui_main.rs"
required-features = ["ui"]

[features]
ui = ["eframe"]
```

### 4. `crates/windows-daemon/src/ui_main.rs` *(new file)*
Minimal egui window. Reads `C:\ProgramData\FocusForLife\status.json` every 2 s
and displays daily/hourly time remaining in `MM:SS` format. Shows a red label
when state is not `allowed`.

---

## Deployment (`C:\ProgramData\FocusForLife\`)

| File | Source |
|---|---|
| `ffl-windows.exe` | `target/release/ffl-windows.exe` |
| `ffl-windows-ui.exe` | `target/release/ffl-windows-ui.exe` |
| `config.toml` | `config/windows-config.toml` (already has `[sync]` section) |
| `blocked-domains.txt` | `config/blocked-domains.txt` |
| `libsqlite3-0.dll` | `C:\msys64\mingw64\bin\libsqlite3-0.dll` |

### Why libsqlite3-0.dll must be shipped
The GNU build dynamically links `libsqlite3-0.dll` from MSYS2. This DLL is not
present on a stock Windows system. It must sit next to `ffl-windows.exe` or be
on the system PATH. Future builds must keep this in sync.

---

## Windows service (Task Scheduler)

Registered as a scheduled task running as **SYSTEM** with **RunLevel Highest**,
triggered **at boot**. Re-register with:

```powershell
# Run in Admin PowerShell
$action = New-ScheduledTaskAction -Execute 'C:\ProgramData\FocusForLife\ffl-windows.exe' -Argument '--config C:\ProgramData\FocusForLife\config.toml'
$trigger = New-ScheduledTaskTrigger -AtStartup
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
Register-ScheduledTask -TaskName 'FocusForLife' -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force
```

### Brave DoH policy
Deployed to `C:\Program Files\BraveSoftware\Brave-Browser\Application\policies\managed\focusforlife.json`
(content: `{"DnsOverHttpsMode": "off"}`). Required so Brave doesn't bypass
hosts-file blocks via DNS-over-HTTPS.

---

## Known gotchas

- **Must build with GNU toolchain** — always set `PATH` to include
  `C:\msys64\mingw64\bin` before running cargo, or the linker won't be found.
- **libsqlite3-0.dll** — must be re-copied whenever MSYS2 updates sqlite3.
- **status.json written by SYSTEM** — on first boot the file may take ~15 s to
  appear (Firebase sync on first run is slow). The UI shows `00:00` until the
  file exists; this is cosmetic only.
- **UI is read-only** — `ffl-windows-ui.exe` is a viewer only, launched
  manually. It is not registered as a service or startup item.
- **Admin shell needed** for Task Scheduler operations — `Start-ScheduledTask`,
  `Register-ScheduledTask`, etc. all require elevation.
