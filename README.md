[🇨🇳 中文](README.zh-CN.md) · [⬇ Download](https://github.com/zyq1223334444/SmsForwarder/releases)
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
- **Never loses a message** — compensation scan: on app open, every 15 minutes, or the manual
  **Scan now** button, all SMS received in the last 30 minutes are re-queued; duplicate emails are
  impossible (dedup by message key).
- **Long SMS reassembly** — multi-part SMS (verification codes > 70 chars) are joined back into the
  full body before sending.
- **Dual-SIM aware** — slot and carrier are detected live via `SubscriptionManager`; gracefully
  degrades to "unknown slot" when the ROM's SMS view lacks the column.
- **Mailbox presets** — 126 / 163 / QQ / Gmail / Outlook / iCloud etc. one tap to fill
  (server, port, SSL), custom SMTP supported.
- **Dry-run mode** — default ON: everything is processed locally but nothing is ever sent over the
  network. Flip it off when you are ready for real forwarding.
- **Reliable delivery** — serial worker, at-least-once semantics, crash recovery, exponential retry
  with backoff (up to 5 attempts), permanent-failure classification (auth / address / 5xx vs.
  network / timeout / 4xx), 9-minute hard limit per cycle with chained continuation.
- **Privacy by default** — sender number is masked (`138****1234`) unless you opt in to full
  numbers; the SMTP credential is stored with AES-256 (EncryptedSharedPreferences), never in
  plaintext, `allowBackup=false`.
- **MIUI guide** — in-app step-by-step keep-alive setup for MIUI / HyperOS.

## Download

Get the APK from the [Releases](https://github.com/zyq1223334444/SmsForwarder/releases) page.

| Asset | Notes |
|---|---|
| `SmsForwarder-v1.0.0.apk` | signed release APK, install directly on the phone |

Side-load only — this app reads SMS / phone state, so it is **not** published on any app store.

## Quick start (3 steps)

1. **Install & grant permissions.** Open the app → **Settings** → enable **Forwarding** → Save.
   Grant all requested permissions (Receive SMS / Read SMS / Phone state / Notifications).
2. **Configure a mailbox.** Pick the **126.com** preset, enter your 126 address and **SMTP
   authorization code** (not the login password), keep **Dry-run** on and hit **Test email** to
   verify the whole chain without sending anything real, then turn **Dry-run** off.
3. **Keep it alive (MIUI).** Open the in-app **MIUI keep-alive guide** and complete the 3 core
   items: **Auto-start allowed**, **Battery → no restriction**, **Lock in recent tasks**.

### Getting a 126.com SMTP authorization code

1. Log in at https://mail.126.com on a PC browser;
2. **Settings → POP3/SMTP/IMAP** → enable **SMTP service** (a security notice appears, click
   continue);
3. A one-time **authorization code** is generated once; it looks like a random 16-char string;
   copy it into the app — this is what the app uses to send mail, not your login password.

> Any mailbox with SMTP works. 126/163/QQ use a 16-char authorization code; Gmail/Outlook use an
> app password. If your mailbox requires additional verification on web login, you will need it
> when reading forwarded mail from school.

## Verification code use case (school)

1. Leave the phone (with this app running) at home — battery unlimited + auto-start + task lock;
2. At school, request a verification code anywhere (login pages, payment, etc.);
3. The phone forwards it to your 126 inbox within seconds;
4. Open webmail on a school computer/tablet — **126 web login does not need an SMS code**, so you
   get the code and finish the login.

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
| JDK | 17 (Microsoft OpenJDK) |
| Gradle | 8.13 (Tencent mirror; official CDN times out in CN) |
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
| `…/work/ReconcileWorker.kt` | compensation scanner (baseline + last-30-min catch-up) |
| `…/work/SendQueueWorker.kt` | serial send queue, retries, at-least-once |
| `…/mail/MailSender.kt` | JavaMail SMTP (SSL/STARTTLS) |
| `…/sim/SimInfoHelper.kt` | live dual-SIM detection |
| `…/ui/` | Main / Settings / Status / MIUI guide activities |
| `app/src/main/res/` | layouts, themes, adaptive icon |

## License

[MIT](LICENSE) © 2026 zyq1223334444
