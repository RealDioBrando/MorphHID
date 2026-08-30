# Building MorphHID

## Normal setup

- JDK 17+ (this repo was bootstrapped with Android Studio's JBR 21)
- Android SDK with platform 35
- Gradle wrapper (8.11.1) downloads automatically on first build

```bash
./gradlew :core:hid:test :core:control:test   # pure-JVM tests
./gradlew :app:assembleDebug
```

## Offline bootstrap used during initial setup

The machine had no Gradle and no JDK on PATH, so the environment was
bootstrapped as follows (kept here for reproducibility):

1. JDK: `C:\Program Files\Android\Android Studio\jbr` (JDK 21).
2. Gradle 8.11.1 zip downloaded manually to `%TEMP%\morphhid-nettest\gradle.zip`.
3. Extracted to a local folder and used directly:

```powershell
Expand-Archive $env:TEMP\morphhid-nettest\gradle.zip -DestinationPath E:\MorphHID\.gradle-dist
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
E:\MorphHID\.gradle-dist\gradle-8.11.1\bin\gradle.bat --version
E:\MorphHID\.gradle-dist\gradle-8.11.1\bin\gradle.bat wrapper
```

4. `local.properties` points `sdk.dir` at the local Android SDK.

Dependency resolution still requires network access to Google/Maven Central
on the first build.