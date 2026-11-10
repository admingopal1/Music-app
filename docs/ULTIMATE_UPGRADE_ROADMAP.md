# Ultimate Music Player Upgrade

## Goal
Evolve the existing Kotlin + Jetpack Compose + AndroidX Media3 application into a premium offline-first music and video player while preserving current functionality.

## Working rules
- All work is performed on the `ultimate-upgrade` branch; `main` remains untouched until changes are reviewed.
- Preserve existing package names, app identity, library data, playlists, playback state, widgets, sharing, and user settings.
- Prefer incremental, buildable commits over broad rewrites.
- Do not promise codec or DSP behavior that the device/decoder/audio route cannot provide.
- AI features must be optional, privacy-aware, and useful offline where possible.
- Every feature must include permission/error/loading/empty states and accessibility labels.

## Existing architecture observed
- Kotlin application using Jetpack Compose and Material 3.
- AndroidX Media3 ExoPlayer and MediaSessionService.
- Main tabs: Library, Now Playing, EQ & DSP, Playlists, Videos, Share.
- Existing lyrics repository supports imported LRC/TXT, a user-selected lyrics folder, and embedded MP3 USLT lyrics.
- Existing video repository queries MediaStore and caches thumbnails.
- Existing playback service attaches Android audio effects to the ExoPlayer audio session.
- Existing UI includes vinyl/cassette presentation, waveform-style animation, queue, favorites, ratings, and lyrics entry points.

## Roadmap

### Stage 0 — Audit and baseline
- Inspect all source packages, Gradle configuration, manifest, storage permissions, playback service, DSP processors, and video activity.
- Identify correctness, lifecycle, security, compatibility, and performance issues.
- Establish build/test workflow and preserve a clean baseline.

### Stage 1 — Playback and audio quality
- Harden MediaSessionService lifecycle, audio focus, noisy-route handling, queue restoration, and playback error recovery.
- Review format support against Media3 extractors and device decoders; add format support only where justified.
- Improve gapless transitions and optional crossfade without corrupting seek/queue state.
- Improve DSP controls, preset persistence, per-device limitations, and safe effect release.
- Add replay-gain/normalization tests and avoid clipping.

### Stage 2 — Premium experience
- Refine adaptive Now Playing layout for compact and large screens.
- Add accessible controls, dynamic artwork palette, theme customization, AMOLED mode, and motion preferences.
- Improve library search, sorting, folder navigation, and large-library loading.
- Preserve existing vinyl and cassette modes as optional visualizations.

### Stage 3 — Lyrics
- Improve LRC parsing, offset handling, malformed-file recovery, and timestamp synchronization.
- Add line highlighting, seek-to-line, manual timing adjustment, and a safe lyrics editor/import flow.
- Keep lyrics local by default; online lookup must be optional and clearly disclose its provider/privacy implications.

### Stage 4 — Smart / AI music tools
- Start with on-device smart playlists and explainable recommendations from local listening history.
- Add mood/genre tagging as opt-in, user-editable suggestions.
- Make cloud AI optional; never upload a user's library or listening history without explicit consent.
- Provide graceful offline behavior.

### Stage 5 — Video experience
- Audit the current video activity and Media3 player implementation.
- Improve subtitles, audio-track selection, playback speed, gestures, orientation, and Picture-in-Picture.
- Use scoped storage/MediaStore URIs; avoid reliance on deprecated filesystem paths.
- Add robust decoder and unsupported-format error states.

### Stage 6 — Quality and release
- Add unit tests for LRC, playlists, DSP math, and media metadata.
- Add device/manual test checklist for Android 7+ and current Android releases.
- Run CI build, lint, and tests where the repository's workflow permits.
- Publish a reviewed pull request; do not merge to main without review.

## Initial source-level observations
- The app currently declares compileSdk 34, targetSdk 34, minSdk 24, Kotlin 1.9.24, AGP 8.5.2, and Media3 1.4.1.
- MainActivity requests audio/video read permissions and notification permission on Android 13+.
- VideoRepository includes MediaStore.Video.Media.DATA in its projection for folder display; this needs compatibility review because filesystem path access is deprecated/restricted on modern Android.
- The source tree and Gradle setup must be built in CI before claiming a change is compile-verified.

## Definition of done
A stage is complete only when its code changes, tests or manual verification notes, compatibility limitations, and user-facing behavior are documented.
