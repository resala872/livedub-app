# Live Dub (Android 10+)

Speaks a translated voice over whatever video is playing (YouTube, Morphe-patched YouTube, any app).

Pipeline: captured app audio -> Vosk offline speech recognition (English) -> ML Kit on-device translation -> Android text-to-speech.

## Build the APK
Option A - Android Studio: open this folder, wait for Gradle sync, then Build > Build APK(s).
Option B - GitHub: push this folder to a new GitHub repo, open Actions > "Build APK" > Run workflow, download the APK artifact.

## Use
1. Install the APK (allow install from unknown sources).
2. Open the app, tap "Download speech model" once.
3. Pick the dub language. Install that language's voice in Android Settings > Text-to-speech if missing.
4. Tap Start dubbing, accept the screen-capture prompt (only audio is captured), then play a video.

## Limits
- Source language is English only (swap the Vosk model in ModelManager.kt for other source languages).
- Delay is a few seconds: recognition waits for a pause or ~14 words.
- If the original audio is lowered, recognition may weaken: raise the slider.
- Some apps block audio capture; if the video app opts out, nothing is recognised.
