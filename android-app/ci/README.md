# CI debug signing key

`debug.keystore` is a **shared debug keystore**, not a production signing key.

It exists so every GitHub Actions run produces an APK signed with the same key,
which allows `adb install -r` updates across releases. The alias/password are the
standard Android debug values (`androiddebugkey` / `android`).

Do **not** use this key for Play Store or production releases. For production,
configure your own release signing config/keystore and keep it out of Git.
