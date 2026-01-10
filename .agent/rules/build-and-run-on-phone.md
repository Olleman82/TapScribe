---
trigger: model_decision
description: Information about how to build with gradle, how to start in phone. Paths for adb, sdk etc
---

# Build & Run Guide (TapScribe)

This document defines the standard procedure for building `se.olle.rostbubbla` and deploying it to a physical Android device on this Windows environment.

## 1. System Environment Configuration

*   **Project Root:** `D:\Appar\Tapscribe`
*   **Java/JDK:** Android Studio's bundled JBR is used.
    *   *Path:* `C:\Program Files\Android\Android Studio\jbr`
    *   *Config:* This is explicitly set in `gradle.properties` (`org.gradle.java.home`). Do not change this, or Gradle will fail to find `tools.jar`.
*   **ADB Path:** `C:\SDK\platform-tools\adb.exe`
    *   Always use the full path to avoid alias conflicts in PowerShell.

## 2. Standard Deployment Workflow

To build the app and launch it on your connected phone, run these commands in PowerShell from the project root.

### Step 1: Build
Compiles the code and assembles the APK.
```powershell
./gradlew assembleDebug
```

### Step 2: Install
Installs the APK while preserving app data (databases, settings, permissions).
```powershell
C:\SDK\platform-tools\adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

### Step 3: Launch
Starts the app and clears any stuck "Waiting for Debugger" states.
```powershell
# Prevent "Waiting for debugger" hangs
C:\SDK\platform-tools\adb.exe shell am clear-debug-app

# Force stop to ensure clean reload (optional but recommended)
C:\SDK\platform-tools\adb.exe shell am force-stop se.olle.rostbubbla

# Start the Main Activity
C:\SDK\platform-tools\adb.exe shell am start -n se.olle.rostbubbla/se.olle.rostbubbla.ui.MainActivity
```

## 3. Troubleshooting

### Corrupted Gradle Cache
**Symptoms:** Build fails with `Could not read workspace metadata`, `FileNotFoundException` inside `.gradle`, or similar inexplicable errors.
**Fix:** Nuke the cache and rebuild.
```powershell
./gradlew --stop
Remove-Item -Recurse -Force 'C:\Users\OlleSöderqvist\.gradle\caches'
./gradlew clean assembleDebug
```

### "Waiting for Debugger" Dialog
**Symptoms:** App starts but freezes with a dialog saying it's waiting for a debugger.
**Fix:**
```powershell
C:\SDK\platform-tools\adb.exe shell am clear-debug-app
```
