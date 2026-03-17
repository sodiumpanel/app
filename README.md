# Sodium App

Android client for [Sodium Panel](https://github.com/sodiumpanel/panel) — a game server management panel.

## Features

- Manage multiple Sodium panel instances
- Native Android WebView client
- Dark theme UI
- Add, edit, and delete panel connections

## Build

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Release

Push a tag to create a GitHub release with the APK:

```bash
git tag v1.0.0
git push origin v1.0.0
```

## License

[MIT](LICENSE)
