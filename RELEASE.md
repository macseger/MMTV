# MMTV release signing

Release APKs must use the permanent production keystore. Configure these
environment variables before building a release:

```text
MMTV_KEYSTORE_PATH=/secure/path/mmtv-release.jks
MMTV_KEYSTORE_PASSWORD=<keystore-password>
MMTV_KEY_ALIAS=<key-alias>
MMTV_KEY_PASSWORD=<key-password>
```

Never commit the keystore or any of these values. Store the keystore and its
backups securely. Losing the production keystore prevents future in-place
updates of installed MMTV releases.

Build the signed release APK with:

```text
./gradlew :app:assembleRelease
```

The APK is written under `app/build/outputs/apk/release/` using the configured
MMTV versioned filename.
