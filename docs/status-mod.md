# Status mod (One UI 9)

This branch includes the accepted FOD changes. Both new Home options default to off.
After changing a status-bar option, use **Restart SystemUI** in the app. When trying
the separate `OneUIX Status debug` module, disable other OneUIX modules first and
reboot after enabling its System UI scope.

## Home appearance

- Adaptive status bar uses Samsung's existing `RegionSamplingHelper` to sample
  the composition below the status-bar surface, independently on the left and
  right. It receives luminance callbacks; it does not capture or store screenshots.
  Native sampling thresholds avoid reacting to insignificant brightness changes.
- Colours go through `DarkIconDispatcherImpl`, including its Compose flow.
  The last native tint and regions are restored when leaving Home.
- The clock fades over 250 ms using transition alpha, preserving its layout space
  and Samsung's native alpha. Rapid navigation reverses from the current opacity.
  System animation-disable settings are respected.
- Task events, screen state and the status-bar state controller limit both options
  to unlocked, interactive One UI Home with the shade closed. No launcher hook,
  exported command receiver, extra permission or authentication change is added.
- Adaptive colour sampling also applies to an identified Recents task/launcher overview
  when unlocked and the shade is closed. The clock option remains Home-only.
  If Samsung's overview is not exposed as a distinct task, the stock tint remains;
  confirm the Recents result on the device.
- Repeated task/layout callbacks no longer force duplicate SystemUI tint dispatches
  when brightness and tint areas have not changed. Native clear-area callbacks are
  deferred while adaptive sampling is active, avoiding a transient icon redraw.
- Native app tint callbacks take precedence as soon as Home/Recents is left.
  A single 300 ms fallback restores the prior native tint only if no callback arrives;
  there is no repeated polling. Lock, shade and screen-off restore immediately.
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
2. Return Home, open apps repeatedly, open Recents, lock/unlock and pull the shade.
   Check smooth clock fades, restoration and no shifting of adjacent icons.
3. Try hide battery on/off with extra text on/off. With extra text enabled, test
   both nested options while plugged and unplugged, at low and full battery,
   in both light/dark icon modes. Also check an RTL language if used.
4. Turn the new options off and restart SystemUI; stock behaviour should return.

CI runs the preference/format/state tests, existing tests, hook lint and an APK
build. These checks cannot replace the device checks above.
