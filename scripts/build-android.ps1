# Build the Android APK. Run from a Windows PowerShell with JDK 17 + Android SDK.
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..\android-app')
if (!(Test-Path '.\gradlew.bat')) {
    throw 'gradlew.bat not found. Open the android-app directory in Android Studio or add a Gradle wrapper.'
}
.\gradlew.bat :app:assembleDebug
Write-Host "APK: $(Resolve-Path '.\app\build\outputs\apk\debug\app-debug.apk')"
