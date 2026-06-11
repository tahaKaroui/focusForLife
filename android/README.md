# FocusForLife — Android

A self-hosted focus/distraction blocker for Android. It blocks distracting **apps**
(via an Accessibility Service) and distracting **domains** (via a local VPN that
sinkholes DNS), with an overlay that interrupts you when you open something on the
list.

This is a **build-from-source** app — there is no Play Store build. You compile the
APK yourself and install it. That is intentional: the blocklist is compiled into the
APK, so unblocking something requires editing one file and rebuilding, not just
toggling a setting in a weak moment. The friction is the point.

> Part of the [FocusForLife](../README.md) monorepo. The desktop/Linux companion
> lives in [`../desktop`](../desktop).

---

## Build & install

Requirements: Android Studio (or a standalone Android SDK) and a device running
**Android 15 (API 35)** or newer.

```bash
# Debug build (easiest for personal use)
./gradlew assembleDebug
# APK lands at: app/build/outputs/apk/debug/app-debug.apk

# Install onto a connected device
./gradlew installDebug
```

On first launch the app walks you through the permissions it needs. Grant them all
or enforcement won't work:

- **Accessibility Service** — detects and blocks foreground apps.
- **VPN** — runs a local DNS sinkhole to block domains (no traffic leaves your device).
- **Display over other apps** (overlay) — shows the "blocked" screen.
- **Device admin** — resists casual uninstall/disable.
- **Exact alarms / ignore battery optimization** — keeps enforcement alive in the background.

> Cloud sync is **optional and off by default**. If you don't configure Firebase
> credentials in `local.properties`, the app just runs standalone. See the sync
> notes at the bottom.

---

## Customize what gets blocked

All blocking targets live in **one file**:

```
app/src/main/java/dev/focusforlife/android/core/FocusTargets.kt
```

It has three lists:

| List | What it blocks | How to identify an entry |
| --- | --- | --- |
| `blockedAppPackages` | Apps, by **package name** | e.g. `com.google.android.youtube` |
| `blockedDomains`      | Websites, by domain      | e.g. `youtube.com` (no `https://`, no `www.` needed) |
| `browserPackages`     | Browsers whose history/usage is watched | package names |

The shipped defaults are common time-sinks (YouTube, Instagram, TikTok, Snapchat,
Twitter/X, Netflix). **Add your own** (site-a.example.com, Reddit, games, whatever) and
**remove any you don't care about**, then rebuild.

### Blocking a website

Easy — websites are just domains. Add the bare domain to `blockedDomains`:

```kotlin
val blockedDomains: List<String> = listOf(
    // ...existing entries...
    "site-a.example.com",
    "site-b.example.com",
    "reddit.com",
)
```

You don't need every subdomain — matching covers `site-a.example.com` **and** anything ending
in `.site-a.example.com`.

### Blocking an app — finding its package name

Apps are blocked by **package name** (e.g. `com.instagram.android`), not by the
friendly name you see on your home screen. To turn an app name into a package name,
use whichever of these is easiest:

1. **Google Play URL (no tools needed).** Open the app's page on
   [play.google.com](https://play.google.com), and look at the address bar. The
   package name is the `id=` part of the URL:

   ```
   https://play.google.com/store/apps/details?id=com.google.android.youtube
                                                  ^^^^^^^^^^^^^^^^^^^^^^^^^^^
   ```

2. **A "Package Name Viewer" app.** Install any package-name viewer from the Play
   Store; it lists the package name of every app already on your phone.

3. **adb (if you have it set up).**

   ```bash
   adb shell pm list packages | grep -i instagram
   # package:com.instagram.android
   ```

Then paste the package name into `blockedAppPackages`:

```kotlin
val blockedAppPackages: List<String> = listOf(
    // ...existing entries...
    "com.instagram.android",
)
```

### Rebuild after editing

Any change to `FocusTargets.kt` only takes effect after you rebuild and reinstall:

```bash
./gradlew installDebug
```

> **Formatting note:** keep each entry in quotes and comma-separated, exactly like the
> existing lines. A missing comma or quote will fail the build.

> **Leave the DNS resolvers alone** unless you know what you're doing. The
> `cloudflare-dns.com`, `dns.google`, etc. entries at the end of `blockedDomains` are
> there to stop apps from using their own encrypted DNS to dodge the VPN sinkhole.

---

## Optional: cloud sync

The app can mirror its focus state across devices through a single shared Firebase
account. This is **disabled unless you provide credentials**, which are read from
`local.properties` (gitignored, never committed):

```properties
ffl.firebase.email=you@example.com
ffl.firebase.password=your-shared-account-password
ffl.firebase.dbUrl=https://your-project-default-rtdb.firebaseio.com
```

With these empty/absent, `FocusSync` logs "running standalone" and the app works
fully on its own.
