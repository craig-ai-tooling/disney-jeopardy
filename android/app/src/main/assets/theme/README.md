# Bundled theme pack

Files in this folder ship inside the APK, so the core art loads at once and still works when the network is slow.

- When the page requests `/theme/<path>?v=N`, the app serves `assets/theme/<path>` from the APK if two things are true: the file is in the bundle, and `manifest.json`'s `version` is `>= N`. A request with no `v` counts as `v=0`. Every other request goes to the server.
- Served types: png, jpg/jpeg, webp, svg, mp3, ogg, woff2, json, css, js. Other extensions, including this README, always go to the network.
- `logo.png`, if present, is also shown on the native loading/offline screen.
- To ship new art: bump `version` in `manifest.json`, then make the server request `?v=<new version>`. Older APKs then fall through to the network copy.
- `files` is informational: list what's bundled. The app checks the real asset contents.
- aapt drops asset names that start with `.` or `_`, so don't use either prefix.
