# Samsung Smart Capture recording investigation

This change is an experimental compatibility fix for black screen recordings in
ordinary `FLAG_SECURE` windows. A successful build or a secure display in the logs
does not establish that a particular One UI firmware will record those windows.
The Galaxy device still needs to be tested.

## APK findings

The supplied `SmartCapture.apk` identifies itself as:

| Item | Value |
| --- | --- |
| Package | `com.samsung.android.app.smartcapture` |
| Version | `6.2.44.29` (`624429000`) |
| Target SDK | 37 |
| Minimum SDK | 35 |
| SHA-256 | `f275d533b8e13b1e76c5f1f2f4b241fd88b321702e6cb37f4594ae61c713604a` |
| Recording process | `com.samsung.android.app.smartcapture:screenrecorder` |

Its manifest declares both the legacy
`com.samsung.android.app.screenrecorder.ScreenRecorderService` and
`com.samsung.android.app.smartcapture.screenrecorder.ScreenRecorderService` in
that process. It also declares Samsung's Screen recorder Quick Settings tile.
This APK is the recorder, not just a screenshot editor.

The implementation in
`com.samsung.android.app.smartcapture.screenrecorder.recorder.core.MP4Recorder`
obtains a projection through Samsung's `semGetMediaProjection` wrapper, then calls
the public eight-argument `MediaProjection.createVirtualDisplay` overload with
name `ScreenRecorder`, density 160 and flags 0. Full-screen capture uses the video
codec's surface; partial-area capture uses an intermediate input surface. Both
go through that same virtual display creation method.

Flags 0 alone is not proof of the failure: the framework changes projection flags,
and OneUIX already tries to add secure-output support in system_server.

## Changes

- Add Samsung Capture to OneUIX's recommended LSPosed scope and route its
  `:screenrecorder` process through the existing `disableFlagSecure` setting.
- Observe the recorder's exact MediaProjection overload. Log creation failures
  and whether the returned display reports `Display.FLAG_SECURE`.
- For Samsung Capture projections, add `VIRTUAL_DISPLAY_FLAG_SECURE` in
  `DisplayManagerService.createVirtualDisplayLocked`, after the service's initial
  permission checks and before logical/native display creation. This also keeps
  the service's display flags consistent with the adapter's flags.
- Retain the existing adapter hook, but identify the flags parameter by a known
  signature rather than searching for an arbitrary integer.
- Hook both `DisplayControl.createVirtualDisplay(String, boolean)` and
  `DisplayControl.createVirtualDisplay(String, boolean, String, float)`, along
  with the older `createDisplay(String, boolean)` variant. The previous predicate
  omitted the four-argument overload used by current AOSP's virtual display
  adapter. The new four-argument hook applies only to displays named
  `ScreenRecorder`; existing two-argument behavior is retained. Deoptimize the
  relevant server callers and log unsupported signatures
  instead of silently ignoring them.

The recorder hook deliberately leaves its client-side flags unchanged: adding
the secure-output bit there can cause a `CAPTURE_SECURE_VIDEO_OUTPUT` permission
failure before the adapter hook is reached. The change preserves the existing
stack-aware `WindowState.isSecureLocked` behavior during secure surface creation.
It does not clear app window flags or change Smart Capture's installed APK.

Framework signatures were checked against current AOSP source, not a matching
Samsung `services.jar`. Unknown Samsung signatures are logged and skipped.

## Test on the phone

1. Install the updated OneUIX module. In **OneUIX's** LSPosed scope, select System
   Framework, System UI and Samsung Capture / Smart Capture
   (`com.samsung.android.app.smartcapture`). Other OneUIX scopes can stay as they
   were. Selecting the package covers its `:screenrecorder` process.
2. Enable **Android > Disable FLAG_SECURE** in OneUIX, then use OneUIX's
   **Soft reboot** button to restart Android userspace and reload system_server
   and the recorder. This does not reboot the kernel. For a clean first test,
   disable any separate module providing the same FLAG_SECURE hooks.
   Do not add Smart Capture to the standalone LSPosed DisableFlagSecure module's
   scope: this change belongs to OneUIX.
3. Record the home screen, then an ordinary app window that sets `FLAG_SECURE`.
   Start a fresh recording and reopen the test app after the soft reboot. Test full
   screen first, then selected-area capture if used. Confirm screenshots still
   work and verify stopping and starting another recording.
4. Look for these entries in LSPosed/Vector's module logs:
   - `Secure recording: hooked ...DisplayManagerService (1)`
   - `Secure recording: hooked ...VirtualDisplayAdapter (1)`
   - `Secure recording: hooked DisplayControl (...)`
   - `Smart Capture: screen recorder hook installed`
   - `Smart Capture: system display flags ...`
   - `Smart Capture: ScreenRecorder display created=true, secure=true`

If the installation entries are missing, verify the setting and scope, then
repeat the soft reboot.
If a signature is unavailable, obtain the device's matching `services.jar` and
`framework.jar` to locate its actual server methods. If all hooks install and
the display reports `secure=true` but an ordinary secure window is still black,
the remaining cause is further down Samsung's recording/compositor path; the
logs and matching framework files are needed before claiming a fix.

Hardware-protected DRM video and app-specific blanking are outside this fix.
Internal audio capture is a separate path and is not modified. Do not use DRM
playback as the initial test for ordinary `FLAG_SECURE` capture.

## References

- [AOSP VirtualDisplayAdapter](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/display/VirtualDisplayAdapter.java)
- [AOSP DisplayControl](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/display/DisplayControl.java)
- [AOSP DisplayManagerService](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/display/DisplayManagerService.java)
- [AOSP MediaProjection](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/media/java/android/media/projection/MediaProjection.java)
- [DisableFlagSecure PR 115](https://github.com/LSPosed/DisableFlagSecure/pull/115)
  currently preserves secure surfaces and explicitly no longer enables recording,
  according to its author's update in the discussion.
