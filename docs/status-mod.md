# Status mod (One UI 9)

This branch includes the accepted FOD changes. Both new Home options default to off.
After changing Adaptive status bar, enable **System UI** and **One UI Home** in
LSPosed scope and reboot (both processes read the option at startup). For the other
status-bar options, use **Restart SystemUI** in the app. When trying
the separate `OneUIX Status debug` module, disable other OneUIX modules first and
reboot after enabling its System UI scope.

## Home appearance

- Adaptive status bar uses Samsung's existing `RegionSamplingHelper` to sample
  the composition below the status-bar surface, independently on the left and
  right. It receives luminance callbacks; it does not capture or store screenshots.
  Native sampling thresholds avoid reacting to insignificant brightness changes.
- Samples are supplied to Samsung's existing
  `StatusBarModePerDisplayRepositoryImpl._sampledAppearanceRegions` input.
  Its normal `LightBarControllerImpl` / `LightBarTransitionsController` path owns
  the icon colour and app-transition animation together. The hook no longer
  intercepts `DarkIconDispatcherImpl` or writes its final intensity/regions.
- The clock fades over 250 ms using transition alpha, preserving its layout space
  and Samsung's native alpha. Rapid navigation reverses from the current opacity.
  System animation-disable settings are respected.
- Task events, screen state and the status-bar state controller limit both options
  to unlocked, interactive One UI Home with the shade closed.
- The app drawer retains Samsung's stock white icons, matching its white app labels.
  A launcher hook observes `AppscreenPot.updateSystemUIColor()` and
  `HomescreenPot.updateSystemUIColor(boolean)`, the calls used by Samsung's
  `WhiteBgColorUpdater`. It releases the sampled input in the drawer, allowing
  the existing native appearance request to take effect. It does not recolour labels.
- Completed/cancelled screen transitions are reconciled with
  `HoneyScreenManager.getCurrentHoneyScreen()`. The hook does not depend on the
  search bar being visible. Only the main display and main launcher process participate.
- Launcher state uses event-driven, package-targeted broadcasts protected by the
  existing platform `STATUS_BAR_SERVICE` permission and Android's sender package/UID.
  There is no polling, service, persistent setting or per-frame broadcast. SystemUI
  asks for the current state after restarting, and retains stock Home colours until
  the launcher replies. If the launcher hook is unavailable, Home stays stock.
- Adaptive colour sampling also applies to an identified Recents task/launcher overview
  when unlocked and the shade is closed. The clock option remains Home-only.
  If Samsung's overview is not exposed as a distinct task, the stock tint remains;
  confirm the Recents result on the device.
- Identical samples do not produce another state-flow update. Both sides must have
  a sample before publishing; view rebinds retain the current appearance input.
- The native `onSystemBarAttributesChanged` callback identifies the window owner,
  so a new app can stop Home sampling before its task notification arrives. New
  app attributes are forwarded before releasing the sampled input. Task changes,
  lock, shade and screen-off also release it through the native pipeline.
  There is no cached final tint replay or 300 ms restoration timer.
- Releasing the input uses compare-and-set so it cannot replace a newer value
  written by Samsung's sampler. The repository, callback and flow methods were
  checked against the supplied One UI 9 SystemUI APK; visual validation is pending.
- Sampling stops away from Home/Recents and listeners are destroyed with the status view.
  If the native sampling contract is unavailable, stock colours remain in use.

## Battery compatibility

The supplied SystemUI APK uses `SamsungBatteryKt`, `BatteryLayoutState` and
`SamsungBatteryComposeViewKt`, bypassing the old `BatteryMeterView` implementation.
The new hook modifies only layout/drawing state, never the battery repository or
charging state. The old hooks remain in use on earlier One UI versions.

The added level label supports the existing percent-sign and charging-symbol
options. Its space is reserved across charging changes and is placed before the
native icon in RTL. Hiding the native body preserves native percentage/charging
content when the extra label is disabled. As before, the nested charging toggle
controls the symbol in the **added label**.

The Compose hook resolves its fields, draw callback and canvas contract before
installation. This contract is specific to the uploaded One UI 9 SystemUI;
another firmware may require updated names. It must still be tested on a phone.

## Device checks

1. Try a bright wallpaper top, a dark top, and different brightness on each side.
   Check notification icons, clock, signal and battery. Open an app and the shade;
   their native colours should return. Check wallpaper changes and rotation.
2. Open/close the app drawer, cancel a partial swipe, and open a folder in the drawer.
   Its status icons should stay white; Home and Recents should remain adaptive.
   Repeat with Hide apps search bar enabled, and restart SystemUI while in the drawer.
   Return Home, open apps repeatedly, open Recents, lock/unlock and pull the shade.
   Check smooth clock fades, restoration and no shifting of adjacent icons.
3. Try hide battery on/off with extra text on/off. With extra text enabled, test
   both nested options while plugged and unplugged, at low and full battery,
   in both light/dark icon modes. Also check an RTL language if used.
4. Turn the new options off and reboot; stock behaviour should return.

The status-mod debug workflow runs unit tests, hook lint and an APK build. The
regular CI workflow on `dev` builds and verifies the signed debug APK. These
checks cannot replace the device checks above, especially app-opening transitions.
