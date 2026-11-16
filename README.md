# rem

1rem = 90min

rem is an Android alarm clock. You tell it when you must be awake, it rings at the end of a sleep cycle shortly before that, and it stops only when you scan a barcode you registered, such as the toothpaste in the bathroom.

It needs Android 12 or newer. It has no accounts, no analytics and no network permission.

## What it does

- Each alarm has a "must wake by" time, repeat days, a label, a ringtone, a vibrate switch and a headphones-only switch.
- Press "going to sleep" and every alarm due in the next 16 hours moves to the end of a 90 minute cycle, if one ends within 30 minutes before its deadline. Otherwise it rings at the deadline. It never rings later. The home screen shows the ring time and the reason, for example "Rings 06:45, 5 cycles, 15 min before 07:00".
- A ringing alarm stops when you scan one of your registered barcodes. There is no snooze. The big button says "five more minutes" and opens the scanner.
- If the barcode is lost or the camera is broken, you can type a 40 character code shown on screen. Pasting is off.
- With no barcode registered, a plain button stops the alarm and the home screen warns you.
- A headphones-only alarm plays on connected headphones and keeps the speaker silent. It moves to the speaker when the headphones disconnect or after 2 minutes without dismissal.
- The alarm volume ramps up, is held above a floor while ringing and is restored afterwards.
- Ringing stops after 30 minutes. That, an alarm the phone slept through, and a ring cut off by a reboot each give a "missed alarm" notification.
- Alarms survive reboots, including before the first unlock, app updates, and clock and timezone changes.
- A reliability screen checks notifications, full-screen alarms, exact alarms, Do Not Disturb, background restriction, the camera permission and whether a barcode is registered. It has a fix button for each and a "test alarm in 1 minute" button.
- Cycle length, time to fall asleep, the early window, the speaker fallback time, the volume floor and the ramp are settings.

## Get the APK

Every push runs the [build workflow](.github/workflows/build.yml). Open the latest run under Actions, download the `rem-debug-apk` artifact, unzip it and install `app-debug.apk`. With the GitHub CLI:

```
gh run download --name rem-debug-apk
adb install app-debug.apk
```

After installing, add a barcode, open the reliability screen and run the test alarm with the phone locked.

## Build

```
./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest
```

The build needs the Android SDK path in `local.properties` (`sdk.dir=...`) and a JDK 21. Gradle picks the JDK itself from `gradle/gradle-daemon-jvm.properties`, so `JAVA_HOME` does not matter.

## Layout

- `core` is plain Kotlin with no Android imports. It holds the alarm model, repeat rules, the wake-time calculation, barcode matching and the fallback code.
- `app` is the Android application, written with Jetpack Compose and Material 3.

[ARCHITECTURE.md](ARCHITECTURE.md) describes the packages and follows an alarm from saving it to scanning the barcode.
