# Lock screen FOD visibility (One UI 9)

The **Hide FOD icon** switch hides Samsung's lock screen fingerprint glyph and
its fingerprint-specific hints. Turning the switch off lets Samsung show the
native glyph again. Fingerprint authentication and PIN validation remain native.

## How it works

OneUIX hooks SystemUI's Samsung FingerprintManager show call. When hiding is
enabled, it requests the paired native hide call; when disabled, the original
show call proceeds. Fingerprint-specific help, failure, and guide presentation
continues to be handled by the existing SystemUI hooks.

The FOD feature runs in **System UI only**. OneUIX does not need LSPosed scope
for the Biometrics app, and it does not modify biometric authentication
callbacks, sensor results, enrollment, or lockout behavior. Custom icon import
and replacement are not part of this option.

## Device checks

After enabling the module in LSPosed, enable System UI in scope and reboot once.
Check the switch both ways, then verify fingerprint unlock, PIN entry,
screen-off/AOD behavior, wake/sleep, and repeated lock/unlock. Test full reboot
and soft reboot separately. Native visibility behavior can vary by One UI
version, so these device checks are required for the target firmware.
