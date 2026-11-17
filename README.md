# FaceBlurCam

Records video with every face blurred. The blur runs on the GPU before the video
is encoded, so the saved file never contains an unblurred face.

## Get the APK (no Android Studio needed)

1. Create a new empty repository on github.com.
2. Upload the contents of this folder (make sure the `.github` folder comes along;
   pushing with git is the most reliable way).
3. Open the repo's **Actions** tab, pick **Build APK**, click **Run workflow**
   (it also runs on every push).
4. When it finishes (about 5 minutes), open the run and download the
   **FaceBlurCam-debug-apk** artifact. Unzip it to get `app-debug.apk`.
5. Copy the APK to your phone and install it (allow "install unknown apps").

## Or build locally

Open this folder in Android Studio and press Run, or with Gradle 8.9 and JDK 17:

    gradle assembleDebug

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Tuning

- Blur strength: `spread = h * 0.4` in `FaceBlurProcessor.kt` (bigger = blurrier).
- Padding around faces: the `0.30f` in `MainActivity.kt`.
- Hold time after a face is lost: `HOLD_NANOS` in `FaceBlurProcessor.kt`.
- If the GL blur misbehaves on your phone, set `USE_GL_BLUR = false` in
  `MainActivity.kt` to fall back to a solid dark oval over each face.

## Known limits

- Faces turned far away from the camera, tiny, or heavily occluded may not be
  detected. Treat this as best-effort, not a guarantee.
- Debug-signed APK, intended for personal use.
