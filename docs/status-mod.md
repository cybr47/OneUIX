# Status mod (One UI 9)

This branch includes the accepted FOD changes. Both new Home options default to off.
After changing Adaptive status bar or Fade clock, enable **System UI** and **One UI Home** in
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
- The clock fades by opacity over 250 ms; it is never clipped or resized while it moves,
  so there is no wipe edge. Only the space it occupies in its container shrinks, so
  notification icons use the released space through Samsung's native layout, moving
  with a gentle fade dip. On return to an app, the clock fades in and the icons move
  back. The clock fades out ahead of the icons and fades in after they have moved away.
  Rapid navigation reverses from the current position/opacity; disabled animations snap
  to the final layout. Native alpha/visibility and the outer status-bar padding remain intact.
- The clock is hidden on Home and in Recents, and stays visible in the app drawer. The
  launcher's Home/drawer state is used for this, so **One UI Home** must be in the LSPosed
  scope for this option as well. Until the launcher has reported, a launcher task is
  treated as Home.
- Empty left-clock margins collapse with the clock. Shared activity chips keep their
  native spacing, and centre/right QuickStar clocks do not fade the notification area.
  Current clock text/font is measured before expansion, and original layout parameters,
  clipping, accessibility and transition alpha are restored on detach. No background polling.
- Task events, screen state and the status-bar state controller limit both options
  to an unlocked, interactive screen with the shade closed (Home for both; the clock
  option also covers Recents).
- The app drawer retains Samsung's stock white icons, matching its white app labels.
  A launcher hook observes `AppscreenPot.updateSystemUIColor()` and
  `HomescreenPot.updateSystemUIColor(boolean)`, the calls used by Samsung's
  `WhiteBgColorUpdater`. It releases the sampled input in the drawer, allowing
  the existing native appearance request to take effect. It does not recolour labels.
- Recents also retains Samsung's native status-bar handling, including its default
  white icons. Identified Recents tasks are excluded from sampling. The launcher's
  `RecentsActivity.onResume()` event releases samples even before the task callback;
  its lifecycle state also prevents a stale Home screen-manager reply from enabling
  adaptive tint during a SystemUI restart in Recents. No white tint is forced.
- Completed/cancelled screen transitions are reconciled with
  `HoneyScreenManager.getCurrentHoneyScreen()`. The hook does not depend on the
  search bar being visible. Only the main display and main launcher process participate.
- Launcher state uses event-driven, package-targeted broadcasts protected by the
  existing platform `STATUS_BAR_SERVICE` permission and Android's sender package/UID.
  There is no polling, service, persistent setting or per-frame broadcast. SystemUI
  asks for the current state after restarting, and retains stock Home colours until
  the launcher replies. If the launcher hook is unavailable, Home stays stock.
- Adaptive colour sampling applies only on Home. Drawer, Recents and other launcher
  surfaces use native colours. The clock option hides the clock on Home and Recents only.
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
- Sampling stops away from Home and listeners are destroyed with the status view.
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
   Its status icons should stay white; Home should remain adaptive. Recents should
   use Samsung's default colours when entered from Home, the drawer or an app.
   Repeat with Hide apps search bar enabled, and restart SystemUI in the drawer and Recents.
   Return Home, open apps repeatedly, open Recents, lock/unlock and pull the shade.
   With clock hiding enabled, check that the clock fades (no wipe edge, also with no
   notification icons) while notification icons smoothly fill its space on Home and
   in Recents, move back as it reappears in apps, and that it stays visible in the
   app drawer. Try rapid Home/app switches,
   no/many notifications, an active call/activity chip, rotation, font/clock-format
   changes, QuickStar clock positions and RTL if used. Check that icons do not
   overlap the clock, and that system animation-disable settings are respected.
3. Try hide battery on/off with extra text on/off. With extra text enabled, test
   both nested options while plugged and unplugged, at low and full battery,
   in both light/dark icon modes. Also check an RTL language if used.
4. Turn the new options off and reboot; stock behaviour should return.

The status-mod debug workflow runs unit tests, hook lint and an APK build. The
regular CI workflow on `dev` builds and verifies the signed debug APK. These
checks cannot replace the device checks above, especially app-opening transitions.
