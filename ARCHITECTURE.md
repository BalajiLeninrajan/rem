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

## :app packages

| Package | Holds |
|---|---|
| `data` | Repository interfaces, `RingStateStore`, `DataModule`. `data.db` is Room (`RemDatabase`, alarms and barcodes). `data.store` is Preferences DataStore (settings, sleep session, ring state). |
| `schedule` | `AlarmScheduler` and its implementation `AlarmManagerScheduler`, `AlarmClocks` (the `setAlarmClock` calls), `AlarmReceiver`, `RescheduleReceiver`, `AlarmBroadcast`, `ScheduleModule.kt` |
| `ring` | `RingController` and `DefaultRingController`, `RingingService`, `RingNotifications`, `RingingActivity`, `RingModule.kt`. `ring.audio` is the player, headphone routing, volume floor and vibration. `ring.ui` is the ringing, scanner and typed fallback screens. |
| `scan` | `BarcodeScanner` composable and `ScannerCamera` (CameraX with the bundled ML Kit model) |
| `ui` | Every non-ringing screen with its view model, navigation, theme, `AppLinks`, `ui.system` (system checks and settings openers) |
| `di` | `AppContainer`, `DefaultAppContainer`, `SystemZoneClock`, access helpers |

`MainActivity` and `RemApplication` sit in the root package. Strings are in `strings_ring.xml` (`ring_` prefix) and `strings_ui.xml` (`ui_` prefix), with `strings.xml` for the app name and screen titles.

## Wiring

There is no DI framework. `RemApplication` holds one `DefaultAppContainer`. Get it with `context.appContainer`, or `LocalAppContainer.current` in Compose. Build view models with `containerViewModel { MyViewModel(it.alarmRepository) }`.

`DefaultAppContainer` never names a concrete class. It calls one module file per package:

- `DataModule(context)` opens Room and three DataStore files on `context.createDeviceProtectedStorageContext()`. One instance per process, because DataStore rejects a second instance on the same file.
- `createAlarmScheduler` builds `AlarmManagerScheduler`.
- `createRingController` builds `DefaultRingController`. It reaches the scheduler through a lambda, so neither is created inside the other's constructor.
- `createAlarmNotifier` builds `RingNotifications`.

Everything in the container is lazy. `applicationScope` has a `SupervisorJob` and an exception handler that logs, so failed background work does not kill a process that may be ringing.

The in-memory repositories (`data.fake`) and the stand-in controllers and schedulers are in the test source set only.

## Who writes what

- The UI writes alarms, barcodes, settings and the sleep session through the repositories. It does not call `rescheduleAll`. The scheduler collects the alarm, session, settings and ringing-record flows and reschedules itself.
- The UI reads ring times and explanations from `AlarmScheduler.plan`. For previews (an unsaved alarm, "if I sleep now") it calls `computeWakeTime` directly.
- `schedule` writes `RingStateStore.markFired`, `retainFired` and `setScheduled`. It disables a one-shot alarm whose only occurrence was missed.
- `ring` writes `RingStateStore.setRinging` and `clearRinging`, disables one-shot alarms when their ring ends, and ends the sleep session when a ring it covers is dismissed.

## From "alarm saved" to "barcode scanned"

1. Something changes: an alarm is saved or toggled, "going to sleep" or "I'm up" is pressed, a setting changes, the process starts, the device boots, the clock or zone changes. Each of these ends in `AlarmScheduler.rescheduleAll()`.
2. `rescheduleAll` runs under a mutex. It ends a session older than 16 hours, runs `checkMissed` on the rings it persisted last time and reports misses through `AlarmNotifier.notifyMissed`, runs `planSchedule`, sets one `setAlarmClock` alarm per planned ring with `AlarmBroadcast.fireIntent`, cancels the rest, persists the rings with `setScheduled` and publishes `plan`. Three details:
   - If a ring became due under 2 seconds ago and has no fired record yet, the run first waits out those 2 seconds. Setting an entry again would cancel a broadcast that is still on its way.
   - A one-shot alarm that is ringing is left out of the plan. The ring disables it when it ends.
   - A fired record is ignored when the alarm no longer has an occurrence at that instant, so editing an alarm after it rang early does not skip a day.
3. At the ring time AlarmManager sends the broadcast to `AlarmReceiver`. It reads the `ScheduledRing` from the intent and calls `RingController.startRinging(ring)` at once. Then, under `goAsync()`, it calls `RingStateStore.markFired(alarmId, deadline)` and `rescheduleAll()`. The fired record is what stops an alarm that rang at 06:45 for a 07:00 deadline from being planned again for 07:00.
4. `startRinging` claims the ring, so `RingController.state` is `Ringing` before it returns, and starts `RingingService` with `RingContract.serviceIntent`. The service calls `startForeground` (type systemExempted) with a notification whose full-screen intent, tap and single "Scan to dismiss" action all open `RingingActivity`. The controller then loads the alarm, the registered barcodes and the settings, persists a `RingingRecord` and tells the service to play.
5. The service follows the controller's `command` flow (`Wait`, `Play`, `Stop`) and decides nothing itself. `RingPlayback` plays the tone on the alarm stream with a volume ramp, vibrates, keeps the alarm volume at the floor and, for a headphones-only alarm, pins the output to a connected headphone until it disconnects, the route turns out wrong or `speakerFallbackSeconds` pass. If Do Not Disturb mutes alarms it vibrates even when the alarm has vibration off.
6. `RingingActivity` shows the ringing screen over the lock screen. "five more minutes" opens the scanner screen, which hosts `BarcodeScanner`. "can't scan?" opens the typed fallback, which asks `newFallbackCode()` for a fresh code.
7. `BarcodeScanner` reports a barcode once it was seen in 2 consecutive frames. The screen passes it to `RingController.dismissWithBarcode`. The controller compares it with the barcodes that were registered when the ring started, using `findMatch`. With none registered `state.requiresBarcode` is false and `dismissWithoutBarcode()` works instead.
8. On dismissal the controller tells the service to stop, which restores the volume. It clears the `RingingRecord`, disables the alarm if it is one-shot, ends the sleep session if it covers the ring's deadline and calls `rescheduleAll`. `state` becomes `Idle` and the activity finishes.
9. If nobody dismisses within 30 minutes the controller ends the ring the same way, except that the session stays and a missed-alarm notification is posted.

## Reboot and process death

`RemApplication.onCreate` runs on every process start, including before the first unlock. It calls `ensureChannels()`, then `RingController.recoverInterruptedRing()`, then `rescheduleAll()`.

- The service returns `START_REDELIVER_INTENT`. If the process dies mid-ring and the system starts the service again, the controller picks the ring up from its persisted record and keeps the original start time for the 30 minute limit.
- `recoverInterruptedRing` waits 2 seconds for such a redelivery. A record that is still not live after that is cleared and reported as a missed alarm. This is what happens after a reboot.
- A persisted ring whose deadline passed without a fired record is reported as missed by `rescheduleAll`.
- Nothing on the boot path starts a ring. A ring whose early time was lost but whose deadline is still ahead is planned again, at the deadline at the latest.

The test alarm uses id `AlarmBroadcast.TEST_ALARM_ID`. It goes through the same receiver, service and dismissal. It has no stored alarm, is not marked fired, is not in the plan and does not touch the session.

## Storage rule

Everything the scheduling and ringing paths read lives in device-protected storage: alarms, barcodes, settings, the sleep session and `RingStateStore`. The receivers, the service and the ringing activity are `directBootAware`. Nothing created during `Application.onCreate` may touch credential-protected storage. A user-picked ringtone may be unreadable before the first unlock, so the player falls back to the bundled tone.

ML Kit initialises itself from a content provider that is not direct boot aware. `ScannerCamera` initialises it by hand when that provider has not run. A scanner that still cannot start reports a camera error, and the screen offers the typed fallback and an unlock button.

## Network

The app does not use the network. The manifest removes `INTERNET` and `ACCESS_NETWORK_STATE`, which ML Kit's logging library would otherwise merge in. `RemApplicationTest` checks the merged manifest.

## Tests

`:core` has plain JUnit tests. `:app` tests run on the JVM with Robolectric and coroutines-test: contract tests for every repository (run against Room, DataStore and the in-memory fakes), the scheduler against Robolectric's AlarmManager, the ring controller with a fake service launcher, the view models, and `AlarmFlowTest`, which runs whole flows on the real container. Nothing covers the service, the audio classes, the camera or the Compose screens.

## Build

CI (`.github/workflows/build.yml`) runs `./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest` on every push and uploads the debug APK as the `rem-debug-apk` artifact.
