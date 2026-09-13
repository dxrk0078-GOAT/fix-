# Biz Logger — personal Instagram DM text logger

## What it does
- Uses an Android **AccessibilityService** (the same OS API screen readers use) to read
  on-screen *text* in Instagram DM threads — never screenshots, never touches media files.
- Writes one plain-text file per conversation to:
  `/Android/data/com.anil.igbizlogger/files/IGBizLogs/<username>.txt`
- Skips anything Instagram flags as view-once / disappearing / vanish mode entirely.
- Media (photos, voice notes, etc.) is logged only as a placeholder tag, e.g. `[Photo]`.
- No internet permission requested — nothing leaves the device. Google Drive sync can be
  added later as a separate step whenever you want it.
- No foreground service, no persistent notification. The service only wakes for
  Instagram (enforced at the OS level by `accessibility_service_config.xml`), and even
  then only re-reads the screen after a short debounce, so idle battery draw is ~0.

## How to build
1. Install **Android Studio** (free, from developer.android.com).
2. Open this folder (`IGBizLogger/`) as a project.
3. Let Gradle sync (needs internet the first time, to download build tools — the *app itself* never uses internet).
4. Connect your F34 via USB with USB debugging on, hit Run.
5. On first launch, tap "Open Accessibility Settings" and turn on "Biz Logger". This is
   a one-time system prompt Android requires for any accessibility-based app — I can't
   remove that step, it's an OS safeguard.

## Things you'll likely need to tweak after real-world testing
Instagram doesn't publish a stable UI schema, and it changes slightly between app
updates. `ConversationParser.kt` uses structural heuristics (left/right position,
content-description keywords) rather than hardcoded IDs specifically so it survives
most IG updates, but two things are worth watching in your first week of use:

1. **Username detection** — currently picks the first short text near the top of the
   screen. If Instagram's thread header ever includes something else up there (e.g. a
   promo banner), you may see a wrong "username" for a session. Easy fix once you see
   it happen — tell me what showed up and I'll tighten the heuristic.
2. **Per-message timestamps** — Instagram only shows timestamps as occasional separator
   rows ("Today 3:45 PM"), not on every message. The log always records the *time we
   captured it* (`[logged_at]`), and adds `(shown: ...)` only when IG happened to display
   one. That's a UI limitation, not something we can extract if IG isn't rendering it.

## Not included yet (by your choice)
Google Drive auto-upload/auto-delete — say the word when you want it added; it's a
self-contained addition on top of this.

## One honest flag, not a lecture
Logging the other person's side of a DM without their knowledge may brush up against
Instagram's Terms of Service and, depending on where your customers are located,
consent-to-record rules. Keeping this strictly for internal dispute records (not
redistributing exports) is the safer lane.

---

## v1.1 additions

### Deleted-message highlighting
Detected when Instagram shows an "unsent"/"removed" placeholder in place of a real
message. Tagged in the log as `[DELETED-SELF]` (you unsent your own message) or
`[DELETED-OTHER]` (the other person unsent theirs) — this is inferred from which side
the placeholder appears on, since only a message's sender can unsend it. Heuristic, not
guaranteed 100% precise if two messages from the same side land back-to-back with
identical text, but correct in the overwhelming majority of real conversations.

### Encrypted daily export
`EncryptedExporter.kt` zips the day's `.txt` logs and encrypts with AES-256-GCM,
key derived from a passphrase you set in the app (stored only in Android's
Keystore-backed encrypted prefs, never in plaintext). Nobody — including you, on a
random device — can open the `.encz` file without that passphrase. Keep it somewhere
safe; there's no recovery if you lose it.

### Daily Drive backup (optional)
`DailyExportWorker` runs every 24h via WorkManager and, if you've connected Drive,
uploads that day's `.encz` file to a `IGBizLogger Backups` folder, then deletes the
local copy (per your request). This step needs a **one-time setup you'll have to do
yourself** in Google Cloud Console, since it's tied to your own project and signing key:
1. Create a project at console.cloud.google.com, enable the "Google Drive API".
2. Configure an OAuth consent screen (internal/testing is fine for personal use).
3. Create an Android OAuth client ID using this app's package name
   (`com.anil.igbizlogger`) and your debug/release keystore's SHA-1 fingerprint
   (get it via `keytool -list -v -keystore <your keystore>`).
4. Back in the app, tap "Connect Google Drive" and sign in.

I can't generate that OAuth client for you — Google requires it be tied to your own
verified project.

### Hide app icon
The launcher icon is now a separate `activity-alias`. Tapping "Hide app icon from home
screen" disables just that alias — the app is untouched and still fully visible in
Settings → Apps, it just won't clutter your home screen. Re-enable it there anytime.

### Web viewer (`index.html`)
Fully static, no server, no data ever leaves the browser tab. Open it locally (or host
it anywhere — even a free static host), upload the day's `.encz` file, enter your
passphrase, and it decrypts + renders client-side using the same AES-GCM scheme as the
Android exporter. Distinct icons per media type, deleted-self vs deleted-other
highlighting, filters for direction/media/deleted/search text, responsive for phone or
desktop. No Instagram logos or branding assets used — generic chat-bubble styling only.

---

## v1.2 — fixes from code review

### The "asks for permission every time" issue — platform limitation, explained
Android will not let any app silently re-enable itself as an Accessibility Service —
that's an intentional, unbypassable security boundary (it's the exact mechanism that
would make an app dangerous if it *could* self-grant). So this can't be fully "fixed"
in code. What you're almost certainly hitting instead is Samsung's Device Care putting
the app to sleep because it looks "unused" between sessions, which then requires you to
reopen it and can look like the permission reset even though the Settings toggle itself
usually didn't actually change. Fix, one time:
1. Settings → Apps → Biz Logger → Battery → set to **"Unrestricted"**.
2. Settings → Device care → Battery → Background usage limits → make sure Biz Logger is
   **not** in "Sleeping apps" or "Deep sleeping apps".
3. Settings → Apps → Biz Logger → Permissions → turn OFF "Remove permissions if app isn't
   used" (Android's auto-revoke for unused apps).
After that, the Accessibility toggle and WorkManager's daily job both survive reboots on
their own — no extra code can make Android grant it for you the first time.

### 1. Message identity / deduplication
Rebuilt around an ordered "anchor window" alignment (`MessageStore.appendBatch`)
instead of a plain content hash-set, so two separate genuine "okay" messages are no
longer collapsed into one. Documented fallback/edge case in the code comments — see
`MessageStore.kt`.

### 2. Historical conversation archiving
New `HistoryArchiver.kt`: on first opening a conversation, performs a bounded,
checkpointed scroll-back (`ACTION_SCROLL_BACKWARD`) to pull in older messages, stopping
when it detects no new content for a few scrolls in a row (top reached) or hits a safety
cap per session. A `.scrollstate` file per conversation means an interrupted archive run
resumes rather than restarting or looping. Limitation: Instagram exposes no "jump to
oldest" API — this can only scroll the way a human would, so very long threads may take
several app-opens to fully backfill.

### 3. Timestamp state
`pendingTimestamp` moved from an object-level field into a local variable inside
`parse()` — one accessibility event can no longer leak its timestamp into an unrelated
one.

### 4. Username / conversation detection
Added a blocklist for chrome text ("Search", "Camera", "Direct", etc.), restricted
matching to TextView nodes only, and added a short-lived fallback to the last
confidently-identified conversation so a single missed header doesn't drop messages or
misattribute them elsewhere. Group chats are handled as-is (their multi-name header
string is just sanitized for the filename); there's no stable per-thread ID exposed by
the Accessibility tree, so a renamed group/user will start a *new* log file rather than
continuing the old one — flagging this as a real limitation rather than papering over it.

### 5. Media / message types
Added reaction and system-notice detection (kept out of the plain-text dedup stream so
they can't corrupt anchor alignment), plus a catch-all `[Unknown/Unsupported: ...]`
category instead of silently dropping unrecognized bubble-shaped content. This catch-all
is the heuristic most likely to need tuning once you see it running against real chats.

### 6. Storage reliability
Every append is now `fsync`'d immediately after writing. Full-file rewrites (delete
tagging, index trimming, history prepends) go through a temp-file-then-rename pattern,
which is atomic on the same filesystem — a crash mid-write leaves the original file
untouched. Startup now clears any stray `.tmp` file left by an interrupted rewrite. Note:
this is durable-append + atomic-replace, not full database-grade ACID; a future move to
SQLite would close the remaining gap but is a bigger change than this pass.

### 7. Accessibility event handling
Unchanged debounce approach (already correct), now paired with the username-fallback
logic so a conversation switch resets state cleanly instead of bleeding into the next
thread.

### 8. Privacy / transparency
Added a "Stop logging and delete all stored data" button and a plain-language summary of
exactly what's captured, right in the app's main screen.

---

## v1.3 — tamper protection, app lock, settings, Drive polish

### On "device registration" and obfuscation (read this before asking again)
Implemented as **local-only** protections instead of a remote licensing server:
- `SignatureGuard.kt` checks the app's own signing certificate at runtime and refuses
  to activate logging if it doesn't match — this is what actually stops someone from
  quietly modifying and redistributing a working copy under your setup, since Android
  itself guarantees a modified/re-signed APK has a different certificate. No network
  call, nothing that could deactivate a legitimate install, nothing that gives any
  third party (including me, including a future server) control over your installs.
- Release builds now use R8 shrinking + identifier obfuscation
  (`minifyEnabled true` + `proguard-rules.pro`) — the standard, built-in Android
  mechanism for this, not custom anti-analysis code.

**One-time setup for SignatureGuard** (needed once you have a real signed build):
1. Build a signed release APK (see Codespaces steps below, or Android Studio's
   Build > Generate Signed Bundle/APK).
2. Get its certificate hash:
   `keytool -printcert -jarfile app-release.apk` (look for the SHA-256 line under
   "Certificate fingerprints"), or via `apksigner verify --print-certs app-release.apk`.
3. Paste that hash into `SignatureGuard.EXPECTED_SHA256`, rebuild.
Until you do this, the check is a no-op (so you're never locked out of your own dev
builds) — logged as a reminder in the app logs.

### App lock
Gated by your phone's own fingerprint/face/PIN (`BiometricGate.kt`) — no separate
password for the app itself, so there's nothing to "forget" or reset; if you can
unlock your phone, you can open the app. Toggle it in Settings.

### Export passphrase — show/change now biometric-gated
Both actions in Settings require your phone's own lock first.

### Drive backup
"Select Google Drive account" now builds the sign-in client lazily (only when tapped)
instead of at app launch — this was very likely your launch crash (see below). Added a
configurable delay before the local copy is deleted after upload (Immediately / 1 day /
3 days / 7 days).

### Settings additions
Version, log directory, and a local "App logs" viewer (operational events only —
service connected, export ran, errors — never message content) are now on the main
screen.

---

## Debugging your launch crash (compiled via Replit)
Most likely cause: `GoogleSignInOptions`/`GoogleSignInClient` being built eagerly in
`onCreate()` without Google Play Services properly resolvable in that build
environment — now fixed by making it lazy (only built when you tap the button).
If it still crashes on launch after pulling this version:
1. Connect the phone and run `adb logcat *:E` right as you open the app — the actual
   Java exception + stack trace will tell us exactly which line, far faster than guessing.
2. Double-check `compileSdk 34` / build tools are actually what Replit's Android
   environment installed — cloud IDEs sometimes default to older SDKs unless you pin
   versions explicitly.
3. Try a debug build first (`assembleDebug`) before a release build — release adds R8
   minification (now enabled), which is a separate thing that could need
   `proguard-rules.pro` tweaks if it strips something it shouldn't.

## Building via GitHub Codespaces (no PC needed)
1. Push this `IGBizLogger/` folder to a GitHub repo (create one, e.g. via the GitHub
   mobile app or web upload — a private repo is fine and recommended).
2. Open that repo on github.com, click **Code > Codespaces > Create codespace on main**.
   This spins up a full Linux dev environment in your browser, usable from your phone.
3. In the Codespace terminal:
   ```
   sudo apt-get update && sudo apt-get install -y openjdk-17-jdk unzip
   wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
   unzip commandlinetools-linux-11076708_latest.zip -d android-sdk/cmdline-tools
   mkdir android-sdk/cmdline-tools/latest && mv android-sdk/cmdline-tools/{bin,lib,source.properties} android-sdk/cmdline-tools/latest/ 2>/dev/null
   export ANDROID_HOME=$PWD/android-sdk
   export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
   yes | sdkmanager --licenses
   sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
   ```
4. Build it:
   ```
   ./gradlew assembleDebug
   ```
   The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.
5. Download that file from the Codespaces file explorer (right-click it > Download),
   then transfer it to your phone (email it to yourself, or Google Drive) and open it
   there to install — Android will ask you to allow installs from that source once.
6. For a **signed release build** (needed for step "One-time setup for SignatureGuard"
   above): generate a keystore once with
   `keytool -genkey -v -keystore release.keystore -keyalg RSA -keysize 2048 -validity 10000 -alias biz`,
   then `./gradlew assembleRelease` after wiring signing config into `app/build.gradle`
   (say the word and I'll add that block with placeholders for your keystore path/passwords).

---

## UI/UX pass
Redesigned the settings screen to match the card-based style you liked: teal palette,
rounded MaterialCardView rows with icon badges, a proper app lock switch, and a
staggered fade+slide-up entrance animation for the whole list (`res/anim/layout_stagger.xml`).
The unlock overlay now fades out smoothly instead of snapping away.

Honest scope note: this is still the classic Android View system (XML layouts), not
Jetpack Compose — so it can look clean and modern (which it now does), but the kind of
fluid, physics-based spring animations in the reference screenshots is a lot more
natural to build in Compose. If you want to go further on animation specifically at
some point, migrating this screen to Compose would be the bigger, better follow-up
(a real rewrite of this screen, not a quick patch) — say the word if/when you want that.

Didn't rebuild the reference "Device Registration" screen itself — that's the
device-licensing feature that was declined earlier for the reasons explained then.

---

## v1.4 — bug fixes, export scheduling, and a phone-friendly build path

### Fixed: live logging vs. history archiving race condition
`HistoryArchiver`'s scroll-back was triggering the normal live-logging path on the same
screen updates, risking corrupted ordering. The service now tracks which conversation
the archiver currently "owns" and the live path steps aside for it entirely — see
`InstaLoggerService.archivingUsername`.

### Fixed: SecurePrefs had no crash guard
`EncryptedSharedPreferences.create()` can throw on some devices/Android versions —
this was called unguarded from `MainActivity.onCreate()` and is a very plausible cause
of your launch crash (more likely than the Google Sign-In issue fixed earlier). Now
every call degrades gracefully (logs the failure, returns null/default) instead of
crashing.

### New: export scheduling
Settings > "Export schedule": every 1h / 6h / 24h, every time Instagram opens (capped
at once per 30 minutes so reopening the app repeatedly doesn't spam exports), or manual
only. Plus an "Export now" button for on-demand exports regardless of schedule.

### Known residual risks (can't fully verify without a real device/build)
- The Google API client + Drive library combo is somewhat heavy for modern Android and
  occasionally needs extra ProGuard keep rules beyond what's in `proguard-rules.pro` —
  if a release build crashes specifically on Drive upload, that's the first place to look.
- Emoji icons in the new UI are inline unicode escapes — vanishingly unlikely, but if any
  render as a blank box on a specific device, it's cosmetic only, not a functional bug.
- `SignatureGuard`, `androidx.security-crypto`, and biometric libraries are all only as
  reliable as their AAR-bundled ProGuard rules under R8 minification — untested on a real
  minified build. If a *release* (not debug) build misbehaves, try `minifyEnabled false`
  first to isolate whether R8 is the cause.

### Building without a working Codespaces
Codespaces failing to load on mobile is a known rough edge for GitHub's web IDE on some
phone browsers. Options, roughly best-to-worst for your situation:
1. **GitHub Actions (new, recommended)** — `.github/workflows/build.yml` is now part of
   this project. You don't need an interactive dev environment at all:
   - Create a new (private is fine) GitHub repo from your phone.
   - Add file > Upload files > upload this whole `IGBizLogger.zip` as a single file into
     the repo root (much easier on mobile than uploading a folder tree).
   - Go to the repo's **Actions** tab > select "Build APK" > **Run workflow**.
   - Wait a few minutes, refresh, open the finished run, download the `app-debug-apk`
     artifact — that's your installable APK, no terminal or IDE ever touched.
2. **Retry Replit** — you already got it partially working there; the two crash causes
   found today (Sign-In eagerness, SecurePrefs) were probably the real blockers, not
   the Replit environment itself.
3. **Gitpod.io** — same idea as Codespaces, sometimes more forgiving on mobile browsers;
   worth a try if you want an interactive terminal rather than push-and-wait.
4. If Codespaces itself is the only thing you want fixed: try forcing "Desktop site" in
   your phone browser's menu, or a different browser (Chrome vs. the phone's built-in
   one) — Codespaces' web UI is heavy and some mobile browsers' background-tab throttling
   causes exactly this stuck-loading symptom.

Note for all of the above: this project has no `gradlew` wrapper file (I can't generate
the binary `gradle-wrapper.jar` without network access in this environment). Any
environment building it needs Gradle installed directly and invoked as `gradle
assembleDebug`, not `./gradlew assembleDebug` — the Actions workflow above already does
this correctly.
