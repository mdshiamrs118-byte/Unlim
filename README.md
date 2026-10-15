# Saved Cloud

A small Android app that uses your own Telegram **Saved Messages** as cloud storage:
log in with your Telegram account, upload any file, browse, download, open, share and delete.
Files are sent as documents to your Saved Messages chat; "folders" are stored as a caption
(`📁 Folder name`), so they also show up inside the official Telegram app.

This is an original implementation written from scratch (Kotlin + Jetpack Compose + TDLib).
It is not the Unlim Cloud source code.

## Features
- Telegram login (phone, code, optional two-step password)
- Upload multiple files (picker or "Share" from other apps), progress, foreground service
- Browse everything in Saved Messages (documents, photos, videos, audio)
- Folders (filter chips, "New folder", "Move to folder")
- Download, open, share, save to Downloads, delete

## Not included (yet)
- Automatic camera/DCIM backup, thumbnails/gallery view, search, duplicate finder, face grouping
- 32-bit devices (the bundled TDLib library is arm64-v8a only)

## Build
1. Get a free API ID and API hash at https://my.telegram.org -> "API development tools".
2. Either
   - build as-is: the app asks for the ID and hash on first launch, or
   - add repository secrets `TG_API_ID` and `TG_API_HASH` on GitHub so they are baked into the CI build
     (or put them in `~/.gradle/gradle.properties` for local builds).
3. Push to GitHub. `.github/workflows/android.yml` builds a debug APK (artifact "SavedCloud-debug-apk").
   Locally: open the folder in Android Studio (JDK 17) or run `gradle assembleDebug` (Gradle 8.10+).

## TDLib
`app/src/main/java/org/drinkless/tdlib/` holds the official TDLib 1.8.56 Java files (generated from the
`td_api.tl` of commit "Update version to 1.8.56"), and `app/src/main/jniLibs/arm64-v8a/libtdjni.so` is the
matching native library. They must stay at the same version. TDLib is Boost Software License 1.0
(see `TDLIB_LICENSE_1_0.txt`).

## Notes
- Telegram limits single files to 2 GB (4 GB with Telegram Premium).
- Uploads are copied into app storage first (TDLib needs a real path); the copy is removed after upload.
- Use at your own risk: storing large amounts of data this way is not an officially supported Telegram use.
