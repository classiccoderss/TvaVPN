# tvaVPN

A VPN and censorship-bypass app for Android.

- Telegram: [@ClassicDevs](https://t.me/ClassicDevs)
- GitHub: [tohidbareza](https://github.com/tohidbareza)

See [CHANGELOG.md](CHANGELOG.md) for what is new in 1.6.0.

## Features
- Several tunnel modes and a smart mode that tries them for you.
- Export and import of all tunnel settings to a file.
- Every option is a drop-down list, and the app is built to work with TalkBack.
- Signature lock: only builds signed with the official key run.

## Building
The project is built with Android Studio (Gradle JDK 17).

1. Clone the repository and open the folder in Android Studio.
2. Put the engine files (`.so`) in `app/src/main/jniLibs/arm64-v8a/`. They are not
   stored in git (see `.gitignore`).
3. Run on a phone for a debug build, or use Build > Generate Signed App Bundle / APK
   for a release.

### Signing
A release must be signed with the official key, otherwise the app shows the error
screen and does not connect. Put `release.keystore` and `keystore.properties` next to
`settings.gradle.kts` (never commit them). See `docs/SIGNING.md` and
`keystore.properties.example`.

To check which certificate a keystore has:

```
keytool -list -v -keystore release.keystore -alias tvavpn
```

The SHA-256 must equal `officialSigner` in `app/build.gradle.kts`.

## License
GNU AGPL-3.0, see [LICENSE](LICENSE). Third-party components are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
