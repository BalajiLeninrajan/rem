# Architecture

rem has two Gradle modules. `:core` is plain Kotlin with `java.time` and no Android imports. `:app` is the Android application.

## :core (`dev.balaji.rem.core`)

All the rules live here, as pure functions and small value types, with unit tests.

- `Alarm` and `Alarm.nextDeadline(after, zone)`. The alarm time is the local "must wake by" deadline. Next occurrences are computed per calendar date in the zone, so daylight saving shifts keep the wall clock time. A time in a spring-forward gap resolves to the start of the gap. A time in a fall-back overlap rings once, at its first occurrence.
- `SleepSession` with `covers(deadline)` (after the start, up to 16 hours later) and `isActive(now)`.
- `RemSettings` with defaults, ranges and `coerced()`. It is not called `Settings` so it does not clash with `android.provider.Settings`.
- `computeWakeTime(deadline, session, settings, now)` returns the ring instant and a `WakeExplanation` (`Cycles(cycles, minutesBeforeDeadline)` or `AtDeadline(reason)`).
- `planSchedule(alarms, session, settings, clock, lastFired)` returns a `SchedulePlan`: one `PlannedRing` per enabled alarm. `checkMissed(previous, lastFired, now)` finds rings that should have fired.
- `ScannedCode`, `BarcodeKey`, `Barcode`, `normaliseBarcode`, `Collection<Barcode>.findMatch`, and `StableScanFilter` (same code in 2 consecutive frames).
- `FallbackCode.generate` and `FallbackCode.matches` for the 40 character typed escape hatch.
- `RingLimits.MAX_RING` is the 30 minute limit.

## :app packages and owners

| Package | Owner | Holds |
|---|---|---|
| `data` | data and scheduling | Room, DataStore, repository implementations, `DataModule` |
| `schedule` | data and scheduling | `AlarmScheduler` implementation, `AlarmReceiver`, `RescheduleReceiver`, `ScheduleModule.kt` |
| `ring` | ringing and scanner | `RingingService`, audio, volume, vibration, notifications, `RingingActivity`, `ring.ui` screens, `RingModule.kt` |
| `scan` | ringing and scanner | `BarcodeScanner` composable (CameraX and ML Kit) |
| `ui` | UI | `MainActivity`, every non-ringing screen, navigation, view models, theme |
| `di` | shared | `AppContainer`, `DefaultAppContainer`, `SystemZoneClock`, access helpers |

Strings are split the same way: `strings_data.xml`, `strings_ring.xml`, `strings_ui.xml`, with `strings.xml` for app-wide names. Prefix names with the owner (`ring_`, `ui_`, `data_`).

## Contracts

These files are shared. Change them only by adding, and say so.

- `data`: `AlarmRepository`, `BarcodeRepository`, `SettingsRepository`, `SleepSessionRepository`, `RingStateStore` (with `RingingRecord`). Flows for the UI, suspend functions for writes, `:core` types throughout. In-memory fakes are in `data.fake`. Abstract contract tests in `app/src/test/.../data/contract` state the required behaviour. Each real implementation gets a subclass.
- `schedule`: `AlarmScheduler` (`plan`, `rescheduleAll`, `scheduleTestAlarm`) and `AlarmBroadcast` (action, extras, test alarm id, `fireIntent`, `Intent.putScheduledRing`, `Intent.scheduledRingOrNull`).
- `ring`: `RingController` (state, `startRinging`, three dismissal calls, `newFallbackCode`, `recoverInterruptedRing`), `AlarmNotifier` (`ensureChannels`, `notifyMissed`), `RingContract` (channel ids, notification ids, service and activity intents).
- `scan`: `BarcodeScanner(onBarcode, modifier, torchEnabled, onCameraError, onTorchAvailable)` and `ScannedBarcode`.
- `ui`: `AppLinks` (intents that open `MainActivity` on a screen) and the routes in `ui.navigation.Routes`.
- `di`: `AppContainer`. Get it with `context.appContainer`, or `LocalAppContainer.current` in Compose. Build view models with `containerViewModel { MyViewModel(it.alarmRepository) }`.

`DefaultAppContainer` never names a concrete class. It calls one module file per package: `DataModule`, `createAlarmScheduler`, `createRingController`, `createAlarmNotifier`. To swap a stub for the real thing, edit your own module file and nothing else.

## Who writes what

- The UI writes alarms, barcodes, settings and the sleep session through the repositories. It does not call `rescheduleAll`. The scheduler observes the alarm, session and settings flows and reschedules itself.
- The UI reads ring times and explanations from `AlarmScheduler.plan`. For previews (an unsaved alarm, "if I sleep now") it calls `computeWakeTime` or `planSchedule` directly.
- `schedule` writes `RingStateStore.markFired`, `retainFired` and `setScheduled`.
- `ring` writes `RingStateStore.setRinging` and `clearRinging`, disables one-shot alarms when their ring ends, and ends the sleep session when a ring it covers is dismissed.

## From "alarm scheduled" to "barcode scanned"

1. Something changes: an alarm is saved or toggled, "going to sleep" or "I'm up" is pressed, a setting changes, the process starts, the device boots, the clock or zone changes. Each of these ends in `AlarmScheduler.rescheduleAll()`.
2. `rescheduleAll` ends a session older than 16 hours, runs `checkMissed` on the rings it persisted last time and reports misses through `AlarmNotifier.notifyMissed`, runs `planSchedule`, sets one `setAlarmClock` alarm per planned ring with `AlarmBroadcast.fireIntent`, cancels the rest, persists the rings with `setScheduled` and publishes `plan`.
3. At the ring time AlarmManager sends the broadcast to `AlarmReceiver`. It reads the `ScheduledRing` from the intent and calls `RingController.startRinging(ring)` at once. Then, under `goAsync()`, it calls `RingStateStore.markFired(alarmId, deadline)` and `rescheduleAll()`. The fired record is what stops an alarm that rang at 06:45 for a 07:00 deadline from being planned again for 07:00.
4. `startRinging` starts `RingingService` with `RingContract.serviceIntent`. The service calls `startForeground` (type systemExempted) with a notification whose full-screen intent and single "Scan to dismiss" action both open `RingingActivity`. It persists a `RingingRecord`, loads the alarm and settings, and plays on the alarm stream. `RingController.state` becomes `Ringing`.
5. `RingingActivity` shows the ringing screen over the lock screen. "five more minutes" opens the scanner screen, which hosts `BarcodeScanner`. "can't scan?" opens the typed fallback, which asks `newFallbackCode()` for a fresh code.
6. `BarcodeScanner` reports a barcode once it was seen in 2 consecutive frames. The screen passes it to `RingController.dismissWithBarcode`. The controller compares it with the registered barcodes using `findMatch`. With no barcodes registered `state.requiresBarcode` is false and `dismissWithoutBarcode()` works instead.
7. On `DISMISSED` the controller stops the service, clears the `RingingRecord`, disables the alarm if it is one-shot, and ends the sleep session if it covers the ring's deadline. Those repository writes make the scheduler reschedule. `state` becomes `Idle` and the activity finishes.
8. If nobody dismisses within 30 minutes the controller ends the ring the same way, except that the session stays and a missed-alarm notification is posted.

## Reboot and process death

`RemApplication.onCreate` runs on every process start, including before the first unlock. It calls `ensureChannels()`, then `RingController.recoverInterruptedRing()`, then `rescheduleAll()`. A persisted `RingingRecord` with no live ring means the ring was cut off, and it is reported as missed. A persisted ring whose deadline passed without a fired record is reported as missed by `rescheduleAll`. Nothing on these paths starts a ring. A ring whose early time was lost but whose deadline is still ahead is simply planned again, at the deadline at the latest.

The test alarm uses id `AlarmBroadcast.TEST_ALARM_ID`. It goes through the same receiver, service and dismissal. It has no stored alarm, is not marked fired, is not in the plan and does not touch the session.

## Storage rule

Everything the scheduling and ringing paths read lives in device-protected storage: alarms, barcodes, settings, the sleep session and `RingStateStore`. Open Room and DataStore with `context.createDeviceProtectedStorageContext()`. The receivers, the service and the ringing activity are `directBootAware`. Nothing created during `Application.onCreate` may touch credential-protected storage. A user-picked ringtone may be unreadable before the first unlock, so the ringing service falls back to the bundled tone.

## Build

This machine cannot build `:app`. CI (`.github/workflows/build.yml`) runs `./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest` on every push.
