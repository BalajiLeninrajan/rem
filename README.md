# rem

1rem = 90min

rem is an Android alarm clock. You tell it when you must be awake, it rings at the end of a sleep cycle shortly before that, and it stops only when you scan a barcode you registered on that alarm, such as the toothpaste in the bathroom.

It needs Android 12 or newer. It has no accounts, no analytics and no network permission.

## What it does

- Each alarm has a "must wake by" time, repeat days, a label, a ringtone, a vibrate switch, a volume floor, a volume ramp and its own barcodes. One barcode can be registered on several alarms.
- The play button next to the alarm sound plays it in the editor on the alarm stream, raised to the alarm's volume floor. That includes the bundled tone, which the system sound picker does not list.
- Barcodes are added, test-scanned, renamed and deleted in the alarm's editor. A new alarm's barcodes are stored when you save it.
- "Duplicate", in an alarm card's menu or the editor's top bar, opens a new alarm with the same time, label, days, sound and barcodes. Nothing is stored until you save the copy.
- Press "going to sleep" and every alarm due in the next 16 hours moves to the end of a 90 minute cycle, if one ends within 30 minutes before its deadline. Otherwise it rings at the deadline. It never rings later. The home screen shows the ring time and the reason, for example "Rings 06:45, 5 cycles, 15 min before 07:00".
- A ringing alarm stops when you scan one of its barcodes. There is no snooze. The big button says "scan to dismiss" and opens the scanner.
- While the scanner or the typed code screen is open the alarm goes quiet for 60 seconds, and the screen shows the time left. It rings again when that runs out or when you go back. Opening the scanner again gives another 60 seconds. The 30 minute limit still counts.
- If the barcode is lost or the camera is broken, you can type a 40 character code shown on screen. Pasting is off.
- An alarm with no barcode stops with a plain button, and its card on the home screen says so.
- With the headphones-only setting on, alarms play on connected headphones and keep the speaker silent. A ring moves to the speaker when the headphones disconnect or after 2 minutes without dismissal.
- The alarm volume ramps up, is held above the alarm's floor while ringing and is restored afterwards.
- Ringing stops after 30 minutes. That, an alarm the phone slept through, and a ring cut off by a reboot each give a "missed alarm" notification.
- Alarms survive reboots, including before the first unlock, app updates, and clock and timezone changes.
- A reliability screen checks notifications, full-screen alarms, exact alarms, Do Not Disturb, background restriction, the camera permission and whether every alarm that is on has a barcode. It has a fix button for each and a "test alarm in 1 minute" button.
- Cycle length, time to fall asleep, the early window, headphones only and the speaker fallback time are settings for all alarms.

## Get the APK

Every push runs the [build workflow](.github/workflows/build.yml). Open the latest run under Actions, download the `rem-debug-apk` artifact, unzip it and install `app-debug.apk`. With the GitHub CLI:

```
gh run download --name rem-debug-apk
adb install app-debug.apk
```

After installing, create an alarm, add a barcode to it, open the reliability screen and run the test alarm with the phone locked. The test alarm checks that a locked phone rings. It needs no barcode and stops with a plain button, so try the scanner on your real alarm's first ring.

## Build

```
./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest
```

The build needs the Android SDK path in `local.properties` (`sdk.dir=...`) and a JDK 21. Gradle picks the JDK itself from `gradle/gradle-daemon-jvm.properties`, so `JAVA_HOME` does not matter.

## Layout

- `core` is plain Kotlin with no Android imports. It holds the alarm model, repeat rules, the wake-time calculation, barcode matching and the fallback code.
- `app` is the Android application, written with Jetpack Compose and Material 3.

[ARCHITECTURE.md](ARCHITECTURE.md) describes the packages and follows an alarm from saving it to scanning the barcode.
