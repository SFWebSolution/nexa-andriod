# Nexa Messenger — Native Android Project

This is the **true native Android mobile application** for Nexa Messenger.

## Features:
- 🚀 **100% Native Container:** Zero Chrome notifications, zero "tap to copy URL", zero browser address bars.
- 📸 **Camera & Microphone:** Full support for video calls, voice messages, and media capture.
- 📁 **File Upload & Download:** Integrated with native Android File Chooser and Android DownloadManager.
- 🔄 **Pull-to-Refresh:** Smooth gesture to refresh the chat.
- 🌐 **Offline Protection:** Built-in network error recovery screen with a "Retry" button.
- 🎨 **Dark Theme:** Status bar and navigation bar tailored to Nexa's `#0B0F19` palette.

---

## How to Build the `.apk` File:

### Option 1: Using Android Studio (Easiest)
1. Open **Android Studio**.
2. Click **Open** and select the folder `nexa-android`.
3. Wait for Gradle sync to complete.
4. Click **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. Android Studio will generate the APK at:
   `app/build/outputs/apk/debug/app-debug.apk`

---

### Option 2: Using GitHub Actions (Zero Local Setup)
1. Push this folder to a GitHub repository.
2. Go to **Actions** tab on your GitHub repository.
3. The **Build Android APK** workflow will compile the `.apk` automatically in the cloud.
4. Download the ready-to-install APK directly from GitHub.
