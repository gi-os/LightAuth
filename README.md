# LightAuth

TOTP two-factor authenticator for the **Light Phone III**. Shows up on the phone as
**Authenticator** (`com.gios.lightauth`).

**Current version: v1.0.4.** See [Version history](#version-history).

Scan the QR code a site gives you for 2FA setup; the six-digit code is there when you
need it. Codes are computed on the phone from the stored secret — the app requests no
`INTERNET` permission at all, so nothing can leave it even in principle.

## What this is and why

This is the [light-sdk `authenticator` example](https://github.com/lightphone/light-sdk/tree/main/examples/authenticator)
rebuilt as a plain sideloadable APK. Light's SDK tools cannot be installed on a LightOS
build in the wild today — community tools are meant to be built and signed by Light
from a public git commit, and the SDK's own README says as much — so this keeps the
example's logic (base32 decoder, RFC 6238 generator, `otpauth://` parser, AndroidKeyStore
cipher, Room schema) and replaces the `LightScreen` chrome with Compose/Material3 in the
same monochrome idiom as the rest of the [gi-os Light App collection](#the-gi-os-light-app-collection).
A plain APK installs over `adb` now and updates through Obtainium later.

One deliberate behavioural change over the SDK original: **removing an account asks
first.** `REMOVE` on a code opens a confirm screen naming the account, with `CANCEL` and
`REMOVE` given equal weight — cancel on the left, so the destructive button is never
where the previous screen's `REMOVE` just was. The SDK example already had a confirm
screen, but its bottom bar held only `CONFIRM` (cancelling meant the back arrow); this
makes both outcomes equally reachable. The confirmation exists because the delete is
genuinely unrecoverable — the secret is sealed behind a non-exportable AndroidKeyStore
key and exists in exactly one place, so getting the code back means re-enrolling with
the provider.

## Quick start

Grab the newest signed APK from [Releases](../../releases/latest) and sideload it:

```bash
adb install -r LightAuth-v1.0.4.apk
```

Or track `https://github.com/gi-os/LightAuth` in **Obtainium** for updates in place.

1. **ADD NEW** → point the camera at the QR code on the site's 2FA setup page.
2. The account lands in the list, issuer on top, account name underneath.
3. Tap it for the current code and the time left on it.
4. **REMOVE** → confirm.

Re-scanning a QR for an account already in the list overwrites it rather than adding a
duplicate — which is what rotating a secret at the provider looks like from this end.

No setup, no permissions to grant, no server to configure. This is the fastest app in
the portfolio to get to a working state from a cold install.

## Configuration and usage

### Scanning

The scanner is [LightQR](https://github.com/gi-os/LightQR)'s, added in v1.0.2: a CameraX
preview with frames decoded in-process by ZXing core off the luminance plane
(`QrAnalyzer`), hints narrowed to `QR_CODE` only — a 2FA page never shows any other kind
of barcode, and every other format left enabled is work done on every frame for nothing.
Pure Java, no Google Play Services, which matters because LightOS ships without GMS and
ML Kit's barcode scanner cannot run at all. It replaced `zxing-android-embedded`, which
launched its own full-colour `CaptureActivity` with its own top bar, unrestyleable from
here; the scanner is now an ordinary screen in the app, in the same monochrome idiom as
everything else. Two things that port needed: `PreviewView.ImplementationMode.COMPATIBLE`
forced, because the window sets `FLAG_SECURE` and a `SurfaceView` preview inside a secure
window can render black; and popping back to home *before* enrolling, since a bad QR
surfaces as an error dialog that lives on the home screen, not the scanner.

`digits`, `period` and `algorithm` are all read off the `otpauth://` URI, so eight-digit
codes, 60-second windows, and SHA-256 or SHA-512 accounts work as well as the usual
six-digit SHA-1 ones. `otpauth://hotp/` counter-based accounts are rejected on the spot
rather than stored as something that will never produce a working code, and a
base32-undecodable secret is rejected at scan time for the same reason.

**Two parser fixes over the SDK original**, both in `OtpAuthUriParser.kt`:
`URI.getPath()` already un-escapes percent triplets, so the SDK's `URLDecoder.decode` on
top of it double-decoded a label — this decodes `uri.rawPath` exactly once. And
`URLDecoder` is a form decoder that turns a literal `+` into a space, so a label of
`user+2fa@example.com` came out with a space in it; a literal `+` is escaped to `%2B`
before decoding to stop that. Both were present in the upstream SDK example and are
fixed here.

### The wheel

Turning the wheel scrolls the account list — the only screen here long enough to need
it. It works because Light relabelled the wheel sensor's scancodes in
`/system/usr/keylayout/Generic.kl`; nothing in the system intercepts them, so they land
in the focused window as ordinary key events and `MainActivity` reads them in
`dispatchKeyEvent` before anything on screen can claim them. No companion service, no
permission, no root. Notches are paid off a fraction per frame rather than applied on
arrival, so a spin reads as one sweep instead of a stack of jumps, and the first notch
after a pause is held until a second confirms it — the wheel sits under a thumb, and a
stray brush shouldn't move the code you're reading. Only the turns are handled here; the
wheel click and camera button do nothing in LightAuth.

### Optional: LightControl

[LightControl](https://github.com/gi-os/LightControl) is a separate, optional app that
gives the wheel click and camera button a job phone-wide: hold the wheel and turn for
brightness, tap it for the flashlight, the camera button for the camera, each rebindable
— tap and hold as two separate gestures — to any app on the phone. It deliberately passes
bare turns through to `com.gios.*` (LightAuth included), because per-notch scrolling
inside the app beats anything reachable from outside it, so installing it does not take
LightAuth's scrolling away.

```bash
adb install -r LightControl-v1.0.x.apk

# NOTE: this setting is a list, and this command REPLACES it — if you also run
# LightVoice's push-to-talk, colon-join both components instead.
adb shell settings put secure enabled_accessibility_services \
  com.gios.lightcontrol/com.gios.lightcontrol.keys.ControlService
adb shell settings put secure accessibility_enabled 1

adb shell appops set com.gios.lightcontrol WRITE_SETTINGS allow
adb shell appops set com.gios.lightcontrol SYSTEM_ALERT_WINDOW allow
```

Latest APK: https://github.com/gi-os/LightControl/releases/latest

## Where the secrets live

- Room database, one row per account, holding an **AES-GCM blob** — never the base32
  secret.
- The wrapping key is generated inside **AndroidKeyStore** and cannot be exported, so
  pulling `totp_accounts.db` off the phone yields ciphertext and nothing else.
- No user authentication gates the key. LightOS has no biometrics, and gating it on the
  lock screen would mean no codes at all on a phone with no lock set.
- `allowBackup` is off: a restored database on another device would hold blobs nothing
  can decrypt, which is worse than starting empty.
- The window is `FLAG_SECURE`, so codes stay out of screenshots and the recents
  thumbnail.
- The decrypted secret is read when a code screen opens and lives only in that
  composition — never held in a `StateFlow`, never handed to the account list.

## Building

```bash
./gradlew :app:assembleDebug
```

The TOTP maths, base32 decoder and URI parser are free of Android imports and covered by
unit tests CI runs *before* it will build an APK (14 tests) — a wrong code is invisible
in a screenshot, so it has to be caught here:

```bash
./gradlew :app:testDebugUnitTest
```

The launcher icon is generated, not hand-drawn — geometry lives in
`scripts/generate_icon.py`, emitted as both adaptive vector layers and raster fallbacks.
Edit it there and re-run `python3 scripts/generate_icon.py`; the script asserts the mark
stays inside the adaptive safe zone.

## Contributing

Solo repo, no PR workflow: commits go straight to `main`, and every push to `main`
triggers CI, which builds, signs, and publishes a GitHub Release. **A push is a release,
not a cosmetic action** — verify before pushing, not after.

The keystore is committed at `keystore/lightauth.jks` (alias/passwords all `lightauth`),
so every build carries the same signing certificate and upgrades install over the top.
CI pins that certificate's SHA-256 in `signing-fingerprint.txt` and fails the build if it
ever drifts, because a changed cert surfaces in Obtainium only as an opaque
`Failure: Invalid`. Exactly one APK is attached per release; the debug build stays a
workflow artifact only. `versionCode` is the workflow run number; `versionName` in the
committed `build.gradle.kts` (currently `1.0.0`) is only the `major.minor` base — CI
stamps the released `major.minor.RUN` (e.g. `1.0.4`) at build time and tags it `vX.Y.Z`.

## Version history

Real tags, oldest to newest:

| Version | What changed |
| --- | --- |
| v1.0.1 | Initial release — light-sdk authenticator example ported to a plain APK |
| v1.0.2 | In-app QR scanning via LightQR's CameraX + ZXing-core reader, replacing `zxing-android-embedded` |
| v1.0.3 | Hardware wheel scrolls the account list |
| v1.0.4 | README: documents the wheel and the optional LightControl integration |

## Why this isn't a LightOS SDK tool

The SDK example is a fine piece of code, but SDK tools cannot be installed on a LightOS
build in the wild today. A plain APK installs over `adb` now and updates through
Obtainium later — the same reason [LightPass](https://github.com/gi-os/LightPass) and
[LightTip](https://github.com/gi-os/LightTip) are plain APKs. The SDK's own
`LightQrCodeScanner` has no equivalent outside the sandbox, so LightQR's scanner does
that job instead.

## Notes for the LPIII panel

- The screen is **greyscale on matte glass**, so the palette is luminance only and the
  code is set large and tracked out — six digits read as three pairs, the gaps are what
  stop a transcription slip.
- Surfaces are true black with no tonal elevation, so 1dp rules separate regions and the
  error dialog gets an explicit dark-grey fill — a scrim over black tints nothing.
- Text uses Akkurat when LightOS provides it, so the app matches the system UI.

## The gi-os Light App collection

Tools for the Light Phone III, all open source.

| Tool | What it does | Built on |
| --- | --- | --- |
| **LightAuth** (this repo) | TOTP two-factor codes, with a confirm step before a delete | Plain Android, ports the light-sdk example |
| [LightPass](https://github.com/gi-os/LightPass) | Photograph a movie ticket, keep the stub | Plain Android |
| [LightTip](https://github.com/gi-os/LightTip) | Tip calculator, plus a receipt splitter that reads the line items | Plain Android |
| [LightNoise](https://github.com/gi-os/LightNoise) | Twelve synthesized sounds, a two-layer mixer and a sleep timer | Plain Android |
| [LightPods](https://github.com/gi-os/LightPods) | AirPods battery, in-ear and lid status | Plain Android |
| [LightQR](https://github.com/gi-os/LightQR) | QR scanner, plus a browser generator | Plain Android |
| [LightRSS](https://github.com/gi-os/LightRSS) | RSS and Atom reader with images and QR subscribe | light-sdk fork |
| [LightControl](https://github.com/gi-os/LightControl) | The wheel and camera button, working phone-wide | Plain Android |
| [LightGlance](https://github.com/gi-os/LightGlance) | Ambient notification dots | Plain Android |
| [LightChat](https://github.com/gi-os/LightChat) | iMessage over a self-hosted BlueBubbles server | Fork of [craigeley/chat](https://github.com/craigeley/chat) |
| [LightNYCSubway](https://github.com/gi-os/LightNYCSubway) | Live MTA subway arrivals | light-sdk fork |
| [LightFastread](https://github.com/gi-os/LightFastread) | RSVP speed reader for EPUB and MOBI | Fork of [fluffyspace/FastRead](https://github.com/fluffyspace/FastRead) |

The Light Phone does not sponsor or endorse any of these. Licences vary per repo.

## Licence

MIT.
