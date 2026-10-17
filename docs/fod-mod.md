# Lock screen FOD customization (experimental, One UI 9)

System UI → Lock screen → **Hide FOD icon** hides the native fingerprint glyph,
its scan visual effect, and fingerprint help/error text. With the switch on, tap
the row to expand **Replace FOD icon**, choose a PNG, and set its size from
50–150%. 100% uses the native fingerprint ImageView's layout dimensions, falling
back to its laid-out bounds when necessary. No sensor-info getter or fixed dp
size is required. The PNG retains its proportions, colors, and transparency. Transparent
padding in the file also scales. Remove clears the replacement selection; OFF
restores native visuals and retains the selection for later.

## Install the debug build

The **FOD debug APK** Actions workflow on `feat/fodmod` runs tests, hook lint, and
assembles **OneUIX-FOD-Debug.apk**. It uses application ID
`io.github.cybr47.oneuix.fodmod` and the fork's existing `P12_BASE64` /
`P12_PASSWORD` signing secrets. It installs alongside the normal module. Signing
keys are never cached or uploaded. Missing secrets fail the build instead of
falling back to a disposable or publicly shared signing identity.

**Upgrade from the earlier test APK:** that APK used a cached debug key. Back up
any settings you need, uninstall **OneUIX FOD debug**, then install this build and
select your PNG again. This one-time signature change does not affect the normal
OneUIX app. Later builds use the repository signing key and can update in place.

1. Install the APK from the successful Actions run.
2. Disable your other OneUIX module in LSPosed before enabling **OneUIX FOD debug**.
3. Enable **System UI** (`com.android.systemui`) and **Biometrics**
   (`com.samsung.android.biometrics.app.setting`) in its scope, plus any other
   scopes needed for your existing features. Open the debug app once and reboot.
4. Configure the feature. Subsequent FOD settings changes refresh both processes
   automatically; there is no polling or background service.

## Rendering and lifecycle

The icon belongs to Samsung's biometric UI, not SystemUI. Only
`UdfpsKeyguardSensorWindow` instances receive a transparent replacement ImageView
inside the native icon container. Its layout stays at the native icon size;
visual scale changes do not modify sensor bounds or touch routing. The original
glyph and effect remain in place with zero alpha, allowing native callbacks to
finish normally. Samsung still controls visibility, position, rotation, AOD
movement, and when the container is dismissed. Native help views are hidden
without changing authentication results or lockout timers.

Samsung shares the base fingerprint layout with app authentication and enrollment.
All changed alpha/clipping values are restored and the replacement removed before
any client reinitializes it, and when the keyguard window is removed. Turning OFF
also restores active windows. Face unlock, app biometric prompts, PIN validation,
and the fingerprint service/HAL are not hooked.

SystemUI hooks target fingerprint-only presentation callbacks in
`KeyguardSecIndicationController`, `KeyguardIndicationController`, the fingerprint
guide popup, and fingerprint failure/error/help toast types. Toast animations and
haptic callbacks run normally. Credential requirements and lockout enforcement
remain native; unrelated lock screen messages are not filtered by text matching.

## PNG storage and updates

The document picker accepts PNGs up to 8 MiB and 8192 pixels on either side.
Headers and dimensions are checked before decoding; images are downsampled to a
maximum side of 512 pixels, preserving aspect ratio and alpha. Normalized data is
written through libxposed's remote-file service to alternating slots. Preferences
only select a slot after writing completes and store a SHA-256 digest; readers
reject incomplete/mismatched files and keep the glyph hidden. There are at most
two stored PNG slots. Remove clears the selection, not those reusable slots.

Both host processes read module-owned remote files. A package-targeted broadcast
only requests a reload, never supplies settings, filenames, commands, or image
data. Android enforces a module-defined signature permission before delivery;
the receiver additionally checks the sender package and UID supplied by Android
(API 34+) against the installed module. The sender explicitly shares that
identity. Missing identity, permission, or package information never enables an
unprotected fallback. Initial loading still works without broadcasts.

Reloads are coalesced with one active read, one pending follow-up, and a 250 ms
delay between batches. FOD preference reads are limited to 1 MiB and 32 levels
of JSON nesting and decode only the FOD settings. The Biometrics process does not
load unrelated module preferences. Invalid settings restore native visuals;
parser errors are logged without preference contents. PNG decoding happens off
the UI thread and rendering
uses a cached bitmap. Unselected/disabled hooks do not decode images. Existing JSON
backups contain the PNG reference, not the PNG bytes; reselect the file after
restoring a backup on a different installation/device.

## Evidence and device validation

The supplied S25 SystemUI APK was inspected for the indication callbacks, guide
popup, and toast signatures/types. The Samsung biometric window contract was
checked against the extracted source at
https://github.com/488315/samsung_framework/tree/main/BiometricSettings/sources/com/samsung/android/biometrics/app/setting/fingerprint
because the matching One UI 9 Biometrics APK was not supplied. The hook checks
required classes, fields, and methods before installation and logs compatibility
failures under `[OneUIX FOD]`. This is a testable implementation, not a claim that
the missing One UI 9 biometric binary has been validated.

The first device logs exposed two compatibility failures: newer
`FingerprintSensorInfo` lacks `getImageSize()`, and the toast lookup based on
`Class.simpleName` found no match. Icon sizing now reads the native view directly
and retries after layout. Hiding does not depend on obtaining a size. Toast
methods are matched by the enum's binary type name, including firmware classes
without inner-class metadata. Logs report hook installation, PNG loading, and
attachment separately so any remaining device issue can be located.

On-device acceptance checks:

- OFF: normal glyph/hints; ON without PNG: invisible glyph and fingerprint errors,
  with successful scans still unlocking normally.
- PNG: alpha and colors preserved; 50/100/150% stays centred, with no clipping.
- Partial finger, mismatch, repeated failure, and lockout: no fingerprint visual
  flash; PIN entry still works when required; normal haptics remain.
- Wake/sleep, AOD, rotation, wallpaper/theme changes, and repeated unlocks: native
  visibility lifecycle; no image left behind after unlocking.
- Open app fingerprint prompts and enrollment while enabled: stock visuals and
  normal operation; return to keyguard and check customization again.
- Remove, replace, resize, disable/re-enable, process restart, and reboot: correct
  restored state; no crash on an absent or invalid PNG.

CI covers preference compatibility, PNG header/allocation limits, bounded reads,
digest checks, native icon size selection, toast lookup without inner-class
metadata, sender rejection, refresh burst coalescing, malformed/deep preferences,
signing setup, the packaged permission declaration, compile errors, and hook lint.
Application/common lint reports are also collected separately without changing
unrelated features. These checks cannot substitute for
the device tests above.

## Settings icon provenance

The settings icon is Google's **Material Symbols Outlined / fingerprint** with
FILL 0, weight 400, grade 0, optical size 24. The Android vector retains the exact
upstream path and translates the SVG's negative-y viewport by 960; Compose applies
the normal settings icon tint. It does not affect the chosen lockscreen PNG.

Source (pinned):
https://github.com/google/material-design-icons/blob/6d7ca43bd6e6668531a00fcaca06d921b63dd716/symbols/web/fingerprint/materialsymbolsoutlined/fingerprint_24px.svg

License: `licenses/MaterialSymbols-APACHE-2.0.txt` (Google, Apache-2.0).

## Security review scope

Reviewed against the device-tested `eb5f292` baseline. The native view, animation,
positioning, size and hint hooks are unchanged; hardening affects communication,
configuration reads, and the test-build pipeline. FOD adds no authentication-result
overrides, credential access, shell commands, network endpoint, writable host-side
preferences, or dynamically loaded code. The trusted base remains Android's
biometric stack, LSPosed, the installed module, and the repository signing key.
Root compromise, malicious module updates, firmware/native image-decoder bugs,
and other enabled modules remain outside this feature's guarantees.

The automated guards are regression checks, not proof that all vulnerabilities
have been eliminated. On a device, verify that toggle/PNG/size changes still apply,
an ordinary app cannot cause a refresh, a wrong finger never unlocks, and native
PIN requirements/lockout remain enforced. Unrelated repository findings are
reported separately and are not changed by this feature patch.
