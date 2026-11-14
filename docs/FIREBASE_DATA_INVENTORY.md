# Firebase data inventory (repository audit)

This is an implementation inventory, not a published privacy policy or a legal statement. It describes the Firebase usage found in this repository at the time of the audit.

## Data written by the Android client

### Firebase Authentication
- Email/password sign-in and account creation are implemented through Firebase Authentication.
- Google sign-in uses Google Sign-In credentials exchanged for a Firebase Auth credential.
- Firebase Auth therefore manages account identifiers and provider credentials. Passwords are not written by this app to Firestore.

### Firestore profile document
Path: `users/{uid}`

The sign-in flow writes/merges:
- `email`
- `updatedAt` (server timestamp)
- `provider` (`password` or `google`)
- For Google sign-in, `displayName` and `photoUrl`

### Firestore cloud-library document
Path: `users/{uid}/private/library`

The sync implementation writes one bounded document containing:
- Playlist names and lists of track metadata
- Track metadata keys, title, artist, album and duration
- Favorite state, rating, play count, last-played time and update timestamps
- Schema/version and sync timestamps

The inspected sync code uploads track metadata and listening statistics for cloud playlist/stat synchronization; it does not upload the actual audio/video file bytes.

## Access control found in firestore.rules

- A signed-in user can read/write only their own `users/{uid}` document and descendants.
- All other paths are denied, including `_server`.
- This is the repository rule file only. The deployed Firebase Console rules must be checked independently before relying on this statement in a public policy.

## Storage and backend findings

- No Firebase Storage SDK usage was found in the inspected Android source.
- No Firebase Cloud Functions source/deployment configuration was found in the repository tree inspected for this change.
- Absence from this repository does not prove that the Firebase project has no manually configured Storage objects, extensions, functions, analytics, crash reporting, exports, backups, or other services.

## Account deletion is not yet implemented

A safe complete deletion operation must:
1. Re-authenticate the user when Firebase requires recent authentication.
2. Delete every user-owned Firestore document and nested subcollection, not only the known profile and library documents.
3. Delete any user-owned Firebase Storage objects if the project uses Storage.
4. Delete the Firebase Authentication user only after data cleanup succeeds, and report partial failures accurately.
5. Be verified against the live Firebase project configuration and rules.

The current client repository does not contain a trusted Admin SDK / callable backend for recursive Firestore deletion. A client-side delete of the known profile/library documents would not prove that unknown nested collections or project-side data are removed. Therefore no destructive account-deletion button or misleading privacy-policy promise is added by this change.

## Owner verification required before publishing a privacy policy

- Inspect Firebase Console Authentication providers and configured identity providers.
- Inspect every Firestore collection and deployed ruleset, including any historical schema.
- Inspect Firebase Storage buckets, rules, object paths, retention and backups.
- Inspect enabled Firebase/Google services (for example Analytics, Crashlytics, App Check, Extensions, scheduled exports).
- Supply the app's support/privacy contact and retention/deletion commitments.
- Implement and test a trusted deletion backend against the verified inventory.

Last repository audit: 2026-11-14.
