# Disney Jeopardy for Android TV

Native TV board (Kotlin + Jetpack Compose for TV). All game logic, content and timing stay on the server: the app opens the
same WebSocket as the web TV page (`/ws?role=tv&tv=<id>`, the id is a UUID kept in prefs), renders each `{t:'state'}` natively
and plays each `{t:'say'}` clip, sending back the same `start`/`next`/`pick`/`readDone`/`pitch`/`override`/... messages as
`public/tv.js`. Art, voice clips and host loops come from the server over HTTP and are cached on the device (images: 512 MB
OkHttp cache with `Last-Modified` revalidation; voice/video/host loops: 1 GB Media3 cache), so game updates still never need a
reinstall. The old fullscreen WebView kiosk is kept as **Classic web mode** (settings dialog). Package
`io.craigcloud.disneyjeopardy`, minSdk 23 (Android 6), targetSdk 35. Shows up on the Android TV / Google TV home row (Leanback
banner) and in normal launchers.

Code: `NativeActivity` (launcher, remote keys, settings dialog), `tv/Net.kt` (WebSocket + reconnect), `tv/Controller.kt`
(state, narration callbacks, host mood, sfx cues, prefetch), `tv/Screens.kt` + `tv/Play.kt` (one composable per phase),
`tv/Fx.kt` (backdrop, lecterns, countdown, flash, confetti, host cam), `tv/Focus.kt` (focus memory), `tv/Media.kt`,
`tv/Sfx.kt`. `MainActivity` is the classic WebView.

## Sideload onto the TV
1. **Developer mode:** Settings → System → About (Google TV: Settings → System → About) → click **Android TV OS build** 7 times
   until it says you are a developer.
2. **Allow installs:** Settings → Apps → Security & restrictions → Unknown sources (Google TV: Settings → Apps → Security &
   restrictions → Unknown sources), and switch on **Downloader** once it's installed.
3. **Install with Downloader** (easiest, no computer): install *Downloader by AFTVnews* from the Play Store, open it, type
   `https://jeopardy.craigcloud.io/apk`, press Go, then **Install**. The same steps update an installed copy.
4. **Or with adb** from a laptop on the same Wi-Fi: Settings → System → Developer options → **USB/Network debugging** on, note the
   TV's IP (Settings → Network), then
   ```
   adb connect <tv-ip>:5555        # accept the prompt on the TV
   adb install -r disney-jeopardy.apk
   ```
The APK comes from the latest `v*` GitHub release (`disney-jeopardy.apk`) or the `disney-jeopardy-apk` artifact of the `android` workflow.

## Using it
| Remote | What happens |
| --- | --- |
| Arrows, OK | Move focus / press the focused button. Focus is remembered per screen by a stable key (button, board cell, episode id), so state updates never move it. |
| BACK | Closes the episode picker / settings panel; at top level, BACK twice within 2 s exits ("Press BACK again to exit"). |
| MENU, or hold OK (0.7 s) | Settings dialog: game server URL, reset to default, **Classic web mode**. |

Voice: the server's clip is played; with no clip (voice off or a failed clip) the line is shown as a subtitle for
`1200 + words × 380 ms`. Android Text-to-Speech is never used in native mode. Sound effects and the think music are
synthesized on the device (same tones as `public/common.js`).

If the server can't be reached the app shows "The server can't be reached" with a focused Retry and keeps retrying quietly
(15 s, 30 s, 60 s, then every 120 s, and at once when the network comes back). During a short drop the last board stays on
screen. When `/api/info` reports a newer `apk.versionCode` than the installed one, an "Update available" badge appears.

Pointing the app at another server from adb (handy for testing): `adb shell am start -n io.craigcloud.disneyjeopardy/.NativeActivity -e url http://<host>:<port>/tv`
(`.MainActivity` for classic mode). The URL is saved, so it sticks until changed again or reset in the dialog.

## Classic web mode
### Page contract (`window.DJ_TV`)
Defined before any page script runs (androidx.webkit document-start script; on very old WebViews it is injected at page start
and a `djtvready` event fires on `window`). The app also adds `?tv=1&app=<versionName>` to the URL and
`DisneyJeopardyTV/<version>` to the User-Agent, so the page can detect the TV app server-side too.

```js
DJ_TV.isTV            // true
DJ_TV.version         // "1.0.0" (versionName), DJ_TV.versionCode
DJ_TV.exit()          // close the app
DJ_TV.setTopLevel(b)  // true on the main board: BACK twice exits; false inside a clue/menu: BACK sends Escape
DJ_TV.speak(text, rate = 1, pitch = 1)  // native TextToSpeech (en-US voice when installed); returns an utterance id
DJ_TV.stopSpeaking()  // stop; no done callback for a stopped utterance
DJ_TV.hasTTS()        // true once the TTS engine is ready (speak() before that is queued)
window.onDJSpeakDone = (id) => {}  // called when the latest utterance finishes or fails
DJ_TV.openSettings(), DJ_TV.reload()
```
Each `speak()` replaces anything still being spoken (only the newest utterance calls `onDJSpeakDone`). Audio and `<video>`
autoplay with sound without a user gesture; WebView's grey default video poster is replaced with a transparent one.

## Build
Docker, no local SDK needed:
```
cd android
docker run --rm -u $(id -u):$(id -g) -e GRADLE_USER_HOME=/w/.gradle-home -v $PWD:/w -w /w ghcr.io/cirruslabs/android-sdk:35 \
  bash -c 'echo "sdk.dir=$ANDROID_HOME" > local.properties && ./gradlew --no-daemon assembleRelease'
# -> app/build/outputs/apk/release/app-release.apk
```
`-PappVersionName=1.2.3 -PappVersionCode=42` set the version (CI does this from the tag and run number).

CI (`.github/workflows/android.yml`, runner from the `RUNNER` repo variable, default `ubuntu-latest`) builds on PRs touching `android/**` and on pushes to main; pushing a tag
`v1.2.3` also attaches `disney-jeopardy.apk` to the GitHub release. versionCode is `100 + run number`. CI also writes
`disney-jeopardy.json` (`{"versionCode":…,"versionName":…}`); put it next to the served APK (`DATA_DIR` or `public/apk/`)
and `/api/info` reports it as `apk`, which drives the app's "Update available" badge.

**Signing:** release and debug builds are signed with `keystore/sideload.jks` (alias and passwords `disneyjeopardy`), committed
on purpose so every build, local or CI, can update an installed copy. It is for sideloading only and is not a secret; never use it
for a Play Store listing. An APK signed with a different key can't update the installed app (uninstall first).

Branding comes from `android-art/` and `public/theme/` at the repo root. `python3 android/tools/import_art.py` (Pillow) regenerates the legacy, round and adaptive launcher icons and the Leanback banners (xhdpi 320x180, plus xxhdpi and xxxhdpi from the 1280x720 art). It also copies `public/theme/*` into `assets/theme/` and rewrites the `files` list in `manifest.json`, leaving `version` unchanged.

## Bundled theme pack
Core art ships in the APK under `app/src/main/assets/theme/`. The app serves a page request for `/theme/<path>?v=N` from the
bundle when the file is there and `version` in `assets/theme/manifest.json` is `>= N`. A request with no `v` counts as
`v=0`. Otherwise the request goes to the server as usual. Served types: png, jpg, webp, svg, mp3, ogg, woff2, json, css,
js. Bundled responses carry an `X-DJ-Theme: bundled-v<version>` header. If `assets/theme/logo.png` exists, it is shown on
the native loading/offline screen. To ship new art without an APK update, put it on the server and raise the `?v=` the
page requests above the bundled version. See `assets/theme/README.md` for details.

## Testing on an emulator
`tools/stub/tv.html` logs every key the page receives, plays audio and an unmuted video without a gesture, calls `DJ_TV.speak`,
and fetches `/theme/manifest.json` at `?v=1` (expects bundled) and `?v=999` (expects network), and toggles board/clue level on Enter (so BACK behaviour can be checked):
```
cd android/tools/stub && python3 -m http.server 8000 &
emulator -avd <android-tv-avd> &      # e.g. system-images;android-34;android-tv;x86, device tv_1080p
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n io.craigcloud.disneyjeopardy/.MainActivity -e url http://10.0.2.2:8000/tv.html
adb shell input keyevent DPAD_CENTER   # BACK, MENU, --longpress DPAD_CENTER ...
```
