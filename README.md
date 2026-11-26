# rem

1rem = 90min

An Android alarm clock that wakes you at the end of a sleep cycle and only shuts up once you've walked over and scanned a barcode.

You set the time you have to be up by. Before bed you tap "going to sleep", and rem moves each alarm earlier so it lands on the end of a 90 minute cycle, at most 30 minutes before your deadline. To turn it off you scan a barcode you registered on that alarm, like the one on your toothpaste.

## Install

Download the APK from the [latest release](https://github.com/BalajiLeninrajan/rem/releases/latest) and open it on your phone, or install it with `adb install`.

After installing, open the reliability screen and run the test alarm with the phone locked.

## Build

You need JDK 21 and the Android SDK, with `sdk.dir` set in `local.properties`.

```
./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest
```

[ARCHITECTURE.md](ARCHITECTURE.md) walks through the code.

## License

[MIT](LICENSE)
