# Google sign-in setup

The app code is ready. Google needs a few one-time settings in Firebase before the
"Continue with Google" button works. Until then the button shows a message instead of crashing.

1. **Enable the provider**
   Firebase console -> Authentication -> Sign-in method -> Google -> Enable (pick a support email).

2. **Get your app's SHA-1 key**
   - Debug build: run `./gradlew signingReport` and copy the `SHA1` of the `debug` variant.
   - Release build: use the SHA-1 of your release keystore.
   - If you publish with Play App Signing, also add the SHA-1 shown in Play Console ->
     Setup -> App signing.

3. **Register the key**
   Firebase console -> Project settings -> Your apps -> `com.app.musicplayer` -> Add fingerprint.

4. **Replace `app/google-services.json`**
   Download the new file from the same page (it now contains an `oauth_client` entry with
   `client_type: 3`) and replace the old one. The Google Services Gradle plugin then generates
   the `default_web_client_id` value that the login screen reads.

5. **Rebuild and sign in.**

## Important for the GitHub Actions APK
`gradle assembleDebug` on a GitHub runner creates a *new* debug keystore on every run, so its
SHA-1 changes every time and Google sign-in will fail on those APKs. Either build locally, or
sign CI builds with one fixed keystore (store it as a GitHub secret) and register that
keystore's SHA-1 in step 3.
