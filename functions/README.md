# Music App secure AI backend

This backend is a deployable Firebase Functions v2 scaffold. It is not connected to a Firebase project until the project-specific setup below is completed.

## Security
- Callable function requires Firebase Authentication.
- Firebase App Check enforcement is enabled.
- OpenAI key is stored as a Firebase Functions secret, never in Android source.
- Daily per-user quota: 60 calls (starter guardrail; tune after observing usage).
- Firestore server quota path is denied to client SDKs by firestore.rules.
- Only bounded text and track metadata are accepted. Audio files and local device paths are never uploaded.
- No prompt/lyrics content is written to logs.

## One-time project setup
1. Install Firebase CLI and sign in: `npm install -g firebase-tools`, then `firebase login`.
2. In Firebase Console, open the existing project and enable Authentication (Email/Google as desired), Firestore, and App Check.
3. Register the Android app with package ID `com.app.musicplayer`. Download its `google-services.json` into `app/google-services.json`. Do not commit this file if it contains project configuration you prefer to keep private; use a private deployment branch/secure CI secret instead.
4. From the repository root run `firebase use --add` and select the existing project. This creates the local `.firebaserc`; do not commit a real project ID unless you intend it to be public.
5. In Firebase App Check, register the Android app with Play Integrity. For local debug builds, configure the debug provider and register the debug token in the Firebase Console. Never ship a debug token.
6. Set the provider secret: `firebase functions:secrets:set OPENAI_API_KEY`.
7. Install/build backend dependencies: `cd functions && npm install && npm run build`.
8. From repository root deploy: `firebase deploy --only functions,firestore:rules`.
9. Configure billing for the selected Firebase/Google Cloud project as required by Functions and your usage. Set budget alerts; budgets alert but do not automatically cap every charge.

## Callable function contract
Function: `musicAi` (region `asia-south1`)

Example data:
```json
{
  "feature": "music_assistant",
  "prompt": "Make a calm evening mix",
  "tracks": [
    { "title": "Track title", "artist": "Artist", "album": "Album" }
  ]
}
```

Supported feature values: `music_assistant`, `ai_dj`, `playlist_ideas`, `lyrics_explanation`, `lyrics_translation`, `music_discovery`.

Lyrics translation/explanation accepts a user-provided excerpt in `lyrics`; the function does not fetch or reproduce full copyrighted lyrics.

## Important
The Android app still needs Firebase SDK wiring, Authentication, App Check initialization, and callable-function client integration. Those changes require the actual Firebase Android app configuration and the project's selected authentication providers. Do not put the OpenAI API key or service-account JSON in Git.
