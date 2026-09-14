# Auto Equalizer (Android, Kotlin)

A native Android app that runs a 5-band equalizer against the device's
global audio session, with an "auto mode" that analyzes the live FFT
and nudges each band toward a flat curve — same idea as the web
prototype, applied system-wide instead of to one loaded file.

## How to build an installable APK

1. Install **Android Studio** (free): https://developer.android.com/studio
2. Open Android Studio → **Open** → select this `AutoEqualizer` folder.
3. Let it sync Gradle (first time takes a few minutes, downloads the
   Android SDK/build tools automatically).
4. Plug in your Android phone via USB with **USB debugging** enabled
   (Settings → About phone → tap Build number 7x → Developer options →
   USB debugging), or use an emulator.
5. Click the green ▶ **Run** button, or
   **Build → Build Bundle(s)/APK(s) → Build APK(s)** to just produce
   the `.apk` file (it lands in `app/build/outputs/apk/debug/`).
6. Copy that `.apk` to your phone and open it to install (you'll need
   to allow "install unknown apps" for whatever file manager/browser
   you use to open it).

No signing setup is required for a debug APK you install on your own
device.

## What it actually does — read before expecting magic

- It attaches an `Equalizer` and `Visualizer` effect to **audio
  session 0**, which on most Android versions represents the general
  output mix rather than a single app. This is the same technique
  real system-wide EQ apps (Wavelet, Flat Equalizer, etc.) use.
- **It is best-effort, not guaranteed.** Some OEM audio stacks, some
  DRM-protected streams (certain video/music apps), and apps that use
  hardware audio offload can bypass this entirely. There is no public
  Android API that guarantees control over literally every sound the
  device produces — that would be a serious security/privacy hole if
  it existed.
- It runs as a **foreground service** (so Android doesn't kill it) and
  shows a persistent notification while active — this is required by
  Android for background audio processing, not optional.
- iOS cannot do this at all — Apple does not expose any system-wide
  audio session hook to third-party apps.

## Files

- `app/src/main/java/com/autoeq/app/EqualizerService.kt` — the
  foreground service holding the global equalizer + auto-adjust logic.
- `app/src/main/java/com/autoeq/app/MainActivity.kt` — UI: toggle the
  service, toggle auto mode, manual sliders per band.
- `app/src/main/res/layout/activity_main.xml` — the screen layout.
