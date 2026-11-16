# rem

1rem = 90min

rem is an Android alarm clock that picks wake times on 90-minute sleep cycle boundaries and only stops ringing after you scan a barcode.

## Build

```
./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest
```

The build needs the Android SDK path in `local.properties` (`sdk.dir=...`) and a JDK 21. Gradle picks the JDK itself from `gradle/gradle-daemon-jvm.properties`, so `JAVA_HOME` does not matter.

## Layout

- `core` is plain Kotlin with no Android imports. It holds the wake-time calculation, repeat rules and alarm model.
- `app` is the Android application, written with Jetpack Compose and Material 3.
