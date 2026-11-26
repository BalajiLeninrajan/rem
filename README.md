# rem

1rem = 90min

An Android alarm clock that wakes you at the end of a sleep cycle and only shuts up once you've walked over and scanned a barcode.

You set the time you have to be up by. Before bed you tap "going to sleep", and rem moves each alarm earlier so it lands on the end of a 90 minute cycle, at most 30 minutes before your deadline. To turn it off you scan a barcode you registered on that alarm, like the one on your toothpaste.

Android 12 or newer. No accounts, no analytics, no network permission.

## Features

- Never rings after your deadline. If a cycle ends just after it, rem asks whether you'd rather sleep up to 30 minutes longer that night.
- Dismiss by scanning one of the alarm's barcodes. If the barcode goes missing or the camera breaks, you can type a 40 character code shown on screen. Pasting is off.
- Snooze is one tap. Each alarm sets its own snooze length and limit.
- The volume ramps up and is held at a floor you pick, so a quiet phone still wakes you.
- Headphones-only mode plays through your earbuds. It switches to the speaker if they disconnect or you sleep through 2 minutes of it.
- Alarms survive reboots, app updates, process kills and timezone changes.
- A reliability screen checks every system setting that can silence an alarm and fires a test alarm a minute out.

## Install

There's no release yet. CI builds a debug APK on every push to this repo:

```
gh run download --repo BalajiLeninrajan/rem --name rem-debug-apk
adb install app-debug.apk
```

After installing, open the reliability screen and run the test alarm with the phone locked.

## Build

You need JDK 21 and the Android SDK, with `sdk.dir` set in `local.properties`.

```
./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest
```

[ARCHITECTURE.md](ARCHITECTURE.md) walks through the code.

## License

[MIT](LICENSE)
