# Release signing setup

The repository never stores a release keystore or signing passwords. The signed AAB workflow is manual and requires four repository Actions secrets.

## Add these secrets

In GitHub, open **Settings → Secrets and variables → Actions → New repository secret** and add:

- `RELEASE_KEYSTORE_BASE64`: base64-encoded contents of the app's existing upload/release keystore.
- `RELEASE_STORE_PASSWORD`: keystore password.
- `RELEASE_KEY_ALIAS`: alias of the signing key.
- `RELEASE_KEY_PASSWORD`: key password.

Encode the keystore locally (do not upload the keystore to the repository):

```bash
base64 -w 0 /path/to/your-upload-key.jks
```

Copy the output into `RELEASE_KEYSTORE_BASE64`. On macOS, use `base64 < your-upload-key.jks | tr -d '\\n'`.

**Important:** Use the same signing key already associated with the app's Play Console listing. If this is an existing published app and the original key is unavailable, do not generate a replacement without checking Play App Signing and the registered upload-key reset process.

## Build

After all four secrets are saved, open **Actions → Signed release AAB → Run workflow** on the flagship upgrade branch. The workflow runs unit tests, builds `bundleRelease`, and uploads `music-app-release-aab` as a downloadable artifact.

The workflow intentionally fails early with a missing-secret message when signing credentials have not been configured. Never paste keystore files or passwords into chat, commits, issues, or pull-request comments.
