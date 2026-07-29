# LightAuth

Two-factor authenticator for the **Light Phone III**. Shows up on the phone as
**Authenticator**.

Scan the QR code a site gives you, and the six digits are there when you need them. Codes
are computed on the phone from the stored secret — the app has no `INTERNET` permission at
all, so nothing can leave it.

This is the [light-sdk `authenticator` example](https://github.com/lightphone/light-sdk/tree/main/examples/authenticator)
rebuilt as a plain sideloadable APK, with one behavioural change: **removing an account asks
first.**

## The confirm step

`REMOVE` on a code no longer deletes anything. It opens a screen that names the account and
asks *"Are you sure you'd like to remove this account?"*, with `CANCEL` and `REMOVE` given
equal weight in the action bar — cancel on the left, so the destructive button is never
where the previous screen's button just was.

The confirmation is there because the delete is genuinely unrecoverable. The secret is
sealed with a non-exportable AndroidKeyStore key and exists in exactly one place; getting
the code back means going to the provider and enrolling again. There is no undo to offer,
so the question comes first.

## Install

Every push to `main` publishes one signed APK as a GitHub Release. Grab the newest from
[Releases](../../releases/latest):

```
adb install -r LightAuth-v<version>.apk
```

Or track `https://github.com/gi-os/LightAuth` in **Obtainium**.

The keystore is committed at `keystore/lightauth.jks`, so every build carries the same
certificate and upgrades install over the top. CI pins that certificate's SHA-256 in
`signing-fingerprint.txt` and fails the build if it ever drifts, because a changed cert
surfaces in Obtainium only as an opaque `Failure: Invalid`. Exactly one APK is attached per
release so there is nothing for an updater to pick wrongly; the debug build stays a
workflow artifact.

## Using it

1. **ADD NEW** → point the camera at the QR code on the site's 2FA setup page.
2. The account lands in the list, issuer on top, account name underneath.
3. Tap it for the current code and the time left on it.
4. **REMOVE** → confirm.

Re-scanning a QR for an account already in the list overwrites it rather than adding a
duplicate, which is what rotating a secret at the provider looks like from this end.

`digits`, `period` and `algorithm` are all read off the `otpauth://` URI, so eight-digit
codes, 60-second windows and SHA-256 or SHA-512 accounts work as well as the usual
six-digit SHA-1 ones. `otpauth://hotp/` counter-based accounts are rejected on the spot
rather than stored as something that will never produce a working code.

## Where the secrets live

- Room database, one row per account, holding an **AES-GCM blob** — never the base32 secret.
- The wrapping key is generated inside **AndroidKeyStore** and cannot be exported, so
  pulling `totp_accounts.db` off the phone yields ciphertext and nothing else.
- No user authentication is required to use the key. LightOS has no biometrics, and gating
  it on the lock screen would mean no codes at all on a phone with no lock set.
- `allowBackup` is off: a restored database on another device would hold blobs nothing can
  decrypt, which is worse than starting empty.
- The window is `FLAG_SECURE`, so codes stay out of screenshots and the recents thumbnail.
- The decrypted secret is read when a code screen opens and lives only in that composition.
  It is never held in a `StateFlow` or handed to the account list.

## Notes for the LPIII panel

- The screen is **greyscale on matte glass**, so the palette is luminance only and the code
  is set large and tracked out — six digits read as three pairs, and the gaps are what stop
  a transcription slip.
- Surfaces are true black with no tonal elevation, so 1dp rules separate regions and the
  error dialog gets an explicit dark-grey fill — a scrim over black tints nothing.
- Text uses Akkurat when LightOS provides it, so the app matches the system UI.

## Why this isn't a LightOS SDK tool

The SDK example is a fine piece of code, but SDK tools cannot be installed on a LightOS
build in the wild today — community tools are meant to be built and signed by Light from a
public commit, and the SDK's own README says as much. A plain APK installs over `adb` now
and updates through Obtainium later, which is the same reason
[LightPass](https://github.com/gi-os/LightPass) and
[LightTip](https://github.com/gi-os/LightTip) are plain APKs.

The port keeps the SDK example's logic — base32 decoder, RFC 6238 generator, `otpauth://`
parser, keystore cipher, Room schema — and replaces the `LightScreen` chrome with Compose
and Material3 in the same monochrome idiom as the sibling apps.

## Building

```
./gradlew :app:assembleDebug
```

The TOTP maths, the base32 decoder and the URI parser are free of Android imports and are
covered by unit tests that CI runs before it will build an APK — a wrong code is invisible
in a screenshot:

```
./gradlew :app:testDebugUnitTest
```

The launcher icon is generated, not hand-drawn — geometry lives in
`scripts/generate_icon.py` and is emitted as both adaptive vector layers and raster
fallbacks. Edit it there and re-run `python3 scripts/generate_icon.py`; the script asserts
the mark stays inside the adaptive safe zone.

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
| [LightNYCSubway](https://github.com/gi-os/LightNYCSubway) | Live MTA subway arrivals | light-sdk fork |
| [LightNonogram](https://github.com/gi-os/LightNonogram) | Picross, plus a generator that only ships solvable puzzles | light-sdk |
| [LightSolitaire](https://github.com/gi-os/LightSolitaire) | Klondike, draw one, unlimited redeals | light-sdk |
| [LightFastread](https://github.com/gi-os/LightFastread) | RSVP speed reader for EPUB and MOBI | Fork of [fluffyspace/FastRead](https://github.com/fluffyspace/FastRead) |
| [LightFog](https://github.com/gi-os/LightFog) | Fog of World companion, GPS recorder and fog map | Fork of [garado/light-topographic](https://github.com/garado/light-topographic) |
| [chat](https://github.com/gi-os/chat) | iMessage over a self-hosted BlueBubbles server | Fork of [craigeley/chat](https://github.com/craigeley/chat) |

The Light Phone does not sponsor or endorse any of these. Licences vary per repo.

## Licence

MIT.
