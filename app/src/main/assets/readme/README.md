[🇨🇳 中文](README.zh-CN.md) · [⬇ Download](https://github.com/zyq1223334444/sms-forwarder/releases)
# SmsForwarder
**Every SMS your phone receives, forwarded to your mailbox — one email per message, in real time.**

A native Android app (Kotlin) that forwards incoming text messages to your email, one mail per SMS,
subject and body included. Built for places where you cannot bring your phone — school, for example:
leave the phone at home, read the verification codes in your mailbox during class.

The typical setup: a **126.com mailbox** as the receiver — 126 lets you log in from any browser
without a second code, so at school you just open webmail and grab the verification code that landed
in your inbox.

## How it works

```
phone receives SMS ──► SmsReceiver (broadcast, instant) ─┐
                        ReconcileWorker (compensation,   ├─► Room DB ─► SendQueueWorker ─► SMTP ─► your mailbox
                        every 15 min / on app open /      │        (at-least-once, retries)
                        manual "Scan now") ───────────────┘
```

Two independent paths feed the queue:
- **Instant path** — the system `SMS_RECEIVED` broadcast is handled the moment an SMS arrives
  (works while the app is running or the process is kept alive).
- **Compensation path** — a periodic scanner (every 15 minutes) and the **Scan now** button re-check
  the system SMS database and pick up anything missed in the last 30 minutes, so **no SMS is ever
  lost** even if the process was killed.

## Features

- **One email per SMS** — each message becomes its own mail, subject like
  `[SMS][卡1·中国移动] 138****1234 · 10-24 09:30` (full number optional), full body + receive time +
  slot + 8-char message ID.
- **Real-time forwarding** — broadcast receiver + foreground keep-alive service; on MIUI/HyperOS,
  follow the in-app setup guide (auto-start, no battery restriction, task lock) and the app behaves
  like a system app.
- **Never loses a message** — compensation scan on app open, every 15 minutes, or the manual
  **Scan now** button re-queues all SMS received in the last 30 minutes; duplicate emails are
  impossible (dedup by message key).
- **Long SMS reassembly** — multi-part SMS (long verification codes) are joined back into the full
  body before sending.
- **Dual-SIM aware** — slot and carrier are detected live via `SubscriptionManager`; gracefully
  degrades to "unknown slot" when the ROM's SMS view lacks the column.
- **Mailbox presets** — 126 / 163 / QQ / Gmail / Outlook / iCloud etc. one tap to fill
  (server, port, SSL), custom SMTP supported.
- **Auto network (optional, off by default)** — when an SMS arrives with no usable network, the app
  turns WiFi on automatically; **mobile data is enabled only if WiFi cannot connect**, then the
  network is restored to its original state. See below.
- **Long-term resend for network failures** — a network/SMTP connection failure is marked
  "network failure" and kept waiting; it is resent automatically once the network is back, and does
  not consume the 5-retry limit.
- **Reliable delivery** — serial worker, at-least-once semantics, crash recovery, exponential retry
  with backoff (up to 5 attempts), permanent-failure classification (auth / address / 5xx vs.
  network / timeout / 4xx), 9-minute hard limit per cycle with chained continuation.
- **Privacy by default** — sender number is masked (`138****1234`) unless you opt in to full
  numbers; the SMTP credential is stored with AES-256 (EncryptedSharedPreferences), never in
  plaintext, `allowBackup=false`.
- **Dry-run (off by default)** — when enabled, messages are listened to/parsed/stored locally but
  never sent; once off, real forwarding is active.
- **Shizuku guardian (v1.3.2, off by default)** — while the auto-network switch is on, checks every
  5 minutes and pins `shizuku_server` to the system's "unkillable" level (`oom_score_adj = -1000`),
  so background cleaners (MIUI/HyperOS) can no longer reclaim it; notifies you if Shizuku stops.
- **About screen** — Settings → About shows the version and offline Chinese/English READMEs.

## Download

Get the APK from the [Releases](https://github.com/zyq1223334444/sms-forwarder/releases) page.

| Asset | Notes |
|---|---|
| `SmsForwarder-v1.3.2.apk` | latest, install directly on the phone (Shizuku guardian) |

Side-load only — this app reads SMS / phone state, so it is **not** published on any app store.

## Quick start (3 steps)

1. **Install & grant permissions.** Open the app → **Settings** → enable **Forwarding** → Save.
   Grant all requested permissions (Receive SMS / Read SMS / Phone state / Notifications).
2. **Configure a mailbox.** Pick the **126.com** preset, enter your 126 address and **SMTP
   authorization code** (not the login password), hit **Test email** to verify the chain, then turn
   **Dry-run** off.
3. **Keep it alive (MIUI).** Open the in-app **MIUI keep-alive guide** and complete the 3 core
   items: **Auto-start allowed**, **Battery → no restriction**, **Lock in recent tasks**.

### Getting a 126.com SMTP authorization code

1. Log in at https://mail.126.com on a PC browser;
2. **Settings → POP3/SMTP/IMAP** → enable **SMTP service** (a security notice appears, click
   continue);
3. A one-time **authorization code** is generated once; it looks like a random 16-char string;
   copy it into the app — this is what the app uses to send mail, not your login password.

> Any mailbox with SMTP works. 126/163/QQ use a 16-char authorization code; Gmail/Outlook use an
> app password.

## Auto network on SMS arrival (optional)

Enable "Auto turn on network when an SMS arrives (turn off after sending)" in Settings — **off by
default**. Strategy:

- Before sending it turns **WiFi** on first; **mobile data is enabled only when WiFi cannot
  connect / there is no usable network** (with WiFi present, data is left alone to save traffic);
- After sending it **restores the original state** — it only turns off the switch it temporarily
  opened, never a network you already had on.

**System limitation (important):** Android 11+ forbids third-party apps from toggling WiFi/mobile
data directly (`setWifiEnabled` always fails for third-party apps; there is no public API for
mobile data). The app uses **Shizuku** (no root) to run `svc wifi/data enable` with shell
capability after a one-time adb authorization.

**Since v1.3.0 the official Shizuku installer is bundled — no download needed:** when you turn the
switch on, the app auto-detects Shizuku — if not installed it shows a dialog and installs the
bundled Shizuku in one tap (you will be asked to "allow installing unknown apps" the first time);
if installed but not activated, it shows the activation steps.

1. Turn on the "Auto network" switch → tap **Install** in the dialog → allow → continue, Shizuku
   is installed;
2. Enable **Developer options → USB debugging** on the phone, connect to a PC;
3. Run the activation command (Shizuku 13.6 uses `libshizuku.so` inside its install dir; **the
   path changes per install** — the in-app dialog reads and shows the real path, or tap
   "View instructions" in the Shizuku app):
   ```
   adb shell <Shizuku install dir>/lib/arm64/libshizuku.so
   ```
   Example (the path differs per install, follow the dialog):
   ```
   adb shell /data/app/~~XXXX==/moe.shizuku.privileged.api-XXXX==/lib/arm64/libshizuku.so
   ```
4. Open the Shizuku app, tap Start, then allow "SmsForwarder" under "Authorized apps";
5. Keep the switch on in the app settings. (adb mode stops after a reboot — rerun the command once
   after restarting the phone.)

> Note: the old `adb shell sh /sdcard/Android/data/.../start.sh` was removed in Shizuku 13.6 and
> now reports No such file.

Without Shizuku (or root) auto-network cannot work; in that case a network failure is kept waiting
and the message is resent automatically when the network returns — no SMS is lost.

### Shizuku guardian (v1.3.2, keeps Shizuku alive)

Some systems (especially MIUI/HyperOS) reclaim the Shizuku process in the background, which breaks
auto-network. **v1.3.2 ships a Shizuku guardian**:

- Enabled automatically while the "auto network" switch is on; checks every 5 minutes;
- While Shizuku is running, it uses Shizuku's shell capability to write `shizuku_server`'s
  `oom_score_adj` to `-1000` (the system's "unkillable" level), so LMKD no longer reclaims it
  (verified on-device, stays applied);
- The pid is resolved dynamically (`pidof shizuku_server`) — no installation path probing needed,
  works on every device;
- If Shizuku is fully stopped (e.g. after a reboot before re-activation), the guardian cannot
  restart it by itself (the activation binary must run as adb/shell; without Shizuku the app has
  no shell channel) — instead it shows an **ongoing notification** to remind you to re-activate,
  which disappears once Shizuku is back.

Together with the MIUI keep-alive table below, Shizuku stays resident and auto-network keeps working.

## MIUI / HyperOS keep-alive (core 3, do all of them)

| # | Setting | Path |
|---|---|---|
| 1 | **Auto-start allowed** | Settings → Apps → Manage apps → Authorizations → Auto-start → allow this app |
| 2 | **Battery: no restriction** | Settings → Apps → Manage apps → SmsForwarder → battery → **no restriction** (choose "allow background activity") |
| 3 | **Lock in recent tasks** | Recents screen → long-press the app card → lock icon |

Then enable **Notifications** (the foreground service depends on it) and keep WLAN connected while
asleep. The in-app guide lists the full set.

> Why WeChat-style "works after being force-stopped" is not possible here: WeChat is a
> system-preinstalled app with a vendor push channel — messages are delivered by the system push
> service and do not need the app process. A third-party app cannot join that channel. What this app
> offers instead is **two layers**: foreground keep-alive so it is (almost) never killed, and the
> compensation scan so a killed process never loses an SMS — the mail goes out up to 15 minutes
> later (or immediately on next app open / "Scan now").
> The one thing no Android app can do: after **Force stop** in system settings, broadcasts cannot
> wake the process; open the app once to resume (same for every app, WeChat included).

## FAQ

**No forwarded email?** Enable forwarding + dry-run off + correct authorization code (regenerate it
in 126 webmail); check the status page — `failed (permanent)` = credential/configuration problem
(fix and press **Retry failed**), `failed (retry N)` = temporary network issue (auto-retried); check
the spam folder — some mailboxes flag "self-sent" mail as spam.

**Duplicate emails?** Extremely rare (only when the mail went out but the phone missed the
confirmation) — the 8-char message ID in the body identifies duplicates; this is the unavoidable
cost of at-least-once semantics.

**Sent SMS are not forwarded** — by design: only **received** SMS are forwarded.

**"Unknown slot"?** Some ROMs do not expose the slot column in the SMS view; normal degradation,
does not affect forwarding.

**Long SMS?** Multi-part bodies are reassembled into one mail; extreme reordering may mark the body
"incomplete", content is sent as received.

## Building

| Component | Version |
|---|---|
| Android SDK | compileSdk 36, build-tools 37, minSdk 26, targetSdk 35 |
| JDK | 17 |
| Gradle | 8.13 |
| AGP / Kotlin / KSP | 8.13.0 / 2.1.0 / 2.1.0-1.0.29 |
| Room / WorkManager / JavaMail | 2.7.2 / 2.10.1 / com.sun.mail:android-mail:1.6.7 |

```bat
cd SmsForwarder
.\gradlew.bat assembleRelease
rem output: app\build\outputs\apk\release\app-release.apk
```

Signing: the release build signs with `key/smsforwarder.jks` configured in `local.properties`
(gitignored — generate your own keystore for a public release).

## Repository layout

| Path | What lives there |
|---|---|
| `app/src/main/java/com/sms/forward/` | all Kotlin sources |
| `…/SmsReceiver.kt` | SMS broadcast receiver (instant path) |
| `…/util/NetworkAutoToggler.kt` | auto network (Shizuku svc wifi/data + restore) |
| `…/util/ShizukuGuard.kt` + `…/ui/ShizukuGuardReceiver.kt` | Shizuku guardian (5-min keep-alive, alert on stop) |
| `…/work/ReconcileWorker.kt` | compensation scanner (baseline + last-30-min catch-up) |
| `…/work/SendQueueWorker.kt` | serial send queue, retries, network-failure resend |
| `…/mail/MailSender.kt` | JavaMail SMTP (SSL/STARTTLS) |
| `…/ui/AboutActivity.kt` | About screen (version + Chinese/English README) |
| `app/src/main/assets/shizuku/` | bundled Shizuku installer + license files |
| `app/src/main/assets/readme/` | offline Chinese/English READMEs shown in the app |

## License

[MIT](LICENSE) © 2026 zyq1223334444.

The auto-network feature bundles **Shizuku** (Apache License 2.0, © RikkaApps) as an **unmodified**
official installer; the full license and notice ship with the app
(`app/src/main/assets/shizuku/LICENSE`, `NOTICE.txt`).
