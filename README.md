<p align="center"><img src="docs/icon.svg" alt="Sift icon" width="128"></p>

<h1 align="center">Sift</h1>

<p align="center">Sort your notifications by what they’re about.</p>

Control notifications by **what they're about**, across all your apps at once. Silence every app's promotions in one tap, see a history of everything that arrived, and review what was blocked.

No root, no account, no internet permission: everything stays on your device.

## Features

- **Categories across apps**: channels from every installed app are grouped into categories like Promotions, Social activity, Messages and Security. Block, silence or allow a whole category in one go, or select individual apps.
- **Notification history**: every notification is kept for 7 days in Logs, with blocked ones clearly marked. Filter by app, category, or shown/blocked.
- **Blocking with a record**: blocked notifications are hidden as they arrive and saved in Logs, so you can check what you missed and allow a category again.
- **New channels handled automatically**: choose what happens when an app adds a channel to a category.
- **Keyword rules**: act on individual notifications whose text matches words you choose, for apps that mix promotions into normal channels. A rule can **remove** them, **snooze** them for an hour, or **keep** them — letting an OTP or a delivery update through a category you otherwise block. Rules can be narrowed to one app, one category, or both.
- **Two ways to block**: *Hide & log* removes blocked notifications as they arrive but keeps a record of them, or *Block fully* switches the channel off in Android so nothing arrives at all.
- **Back up your settings**: export channel behaviors, categories, rules, blocks, log exclusions and appearance to a file. Restore them for matching apps and channels on another device. Notification history and device-specific undo records are never included. Set up notification access and pairing before restoring; Android may refuse changes to locked channels.
- **Exclude apps from Logs**: keep private apps (e.g. messengers) out of the history.
- **Undo**: every change can be undone from the change history.

## How it works, and its limits

Android doesn't let regular apps change other apps' notification settings. Sift uses the same APIs that smartwatch companion apps use, unlocked by two things you grant during setup:

1. **Notification access**, to read incoming notifications and see other apps' notification channels.
2. **A companion device pairing**. Pick any nearby Bluetooth device or Wi-Fi network. Nothing is sent to it; it only unlocks Android's channel controls.

Things to know:

- **Categories are a best guess.** Android has no category field for channels, so Sift infers one from channel names, descriptions and the categories apps attach to notifications. You can change any channel's category by hand.
- **Hide & log removes notifications as they arrive.** A blocked category is set to *Minimized* (no sound, pop-up or status-bar icon), and Sift removes each notification the moment it arrives and logs it. It can appear silently in the shade for a split second.
- **In Hide & log mode, categories already off in Android Settings are taken over** so they're logged too. Block fully leaves those channels off. Notifications from before Sift was set up can't be recovered.
- **Apps with their main notification switch off can't be logged or managed.** Android drops everything from them before any app can see it. Turn the switch on and block their categories in Sift instead.
- **Keyword rules act after a notification arrives**, so a matching one may appear for a moment.
- **"Keep" rules can't un-silence anything.** By the time Sift sees a notification Android has already delivered it at its channel's importance, so a kept one stays in the shade silently, without sound or a pop-up. It is simply not removed.
- **"Block fully" turns off logging and rules for blocked channels.** Android drops notifications from a channel set to *None* before any app can see them, so there is nothing left to record or match. Use *Hide & log* if you want a record of what was blocked, or "keep" rules to work.
- **If you uninstall Sift**, Hide & log channels stay *Minimized* and start appearing silently; Block fully channels stay off. Allow them in Sift first, or adjust them in Android Settings.

## Support

If Sift is useful to you, you can [sponsor its development](https://github.com/sponsors/semi-column). Bug reports and wrong-category reports are just as welcome: [open an issue](https://github.com/semi-column/sift/issues).

## Requirements

Android 13 or newer.

## Install

Download the APK from [Releases](../../releases) and open it on your phone. Your browser or file manager may ask for permission to install apps.

Android may block notification access for apps installed this way. If the switch is greyed out, open **App info → ⋮ → Allow restricted settings**, then try again.

### Verify the download

Each release includes `SHA256SUMS.txt`:

```sh
sha256sum -c SHA256SUMS.txt
```

All releases are signed with the same key. Android refuses to update Sift with an APK signed by a different key, which protects you from tampered builds.

## Privacy

- No internet permission. Sift can't send anything anywhere.
- Notification history is stored only in the app's private storage, kept for 7 days, and excluded from Android backups and device transfers.
- Clearing history, excluding an app, or uninstalling deletes the stored notifications.
- Settings backups include your channel settings, categories, rules, blocks, log exclusions and appearance, but no notification history. Backups are unencrypted JSON, so store them somewhere private; rule keywords can contain sensitive phrases.

## Permissions

| Permission | Why |
| --- | --- |
| Notification access | Read incoming notifications, and read and change other apps' notification channels |
| `QUERY_ALL_PACKAGES` | List your installed apps and their channels |
| `REQUEST_COMPANION_RUN_IN_BACKGROUND` | Keep the companion pairing active in the background |

## Building

Requires JDK 17 and the Android SDK.

```sh
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest lintDebug
```

Debug builds use the application ID `app.sift.debug`, so they install alongside a release build.

### Testing on an emulator

Emulators have no nearby devices to pair with, so grant both permissions with adb:

```sh
adb shell cmd notification allow_listener app.sift.debug/app.sift.service.NotifListener
adb shell cmd companiondevice associate 0 app.sift.debug 02:00:00:00:00:01
```

## Releasing

Releases are built and signed by GitHub Actions ([release.yml](.github/workflows/release.yml)):

1. Update `versionCode` and `versionName` in [app/build.gradle.kts](app/build.gradle.kts).
2. Tag the commit with the same version (`git tag v0.1.0 && git push origin v0.1.0`).
3. The workflow checks the tag matches `versionName`, runs tests and lint, builds and signs the APK, and creates a **draft** release with checksums.
4. Review the draft, edit the notes, and publish it.

The signing key is provided through these repository secrets:

| Secret | Value |
| --- | --- |
| `SIFT_KEYSTORE_BASE64` | The keystore file, base64 encoded |
| `SIFT_KEYSTORE_PASSWORD` | Keystore password |
| `SIFT_KEY_ALIAS` | Key alias |
| `SIFT_KEY_PASSWORD` | Key password |

## License

Copyright © 2026 semi-column

Sift is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License v3.0](LICENSE) as published by the Free Software Foundation.

It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.

Sift bundles the [Inter](https://github.com/rsms/inter) typeface, © The Inter Project Authors,
under the [SIL Open Font License 1.1](docs/licenses/Inter-OFL.txt).
