# audio

Small, modular Java audio experiments for [3d4j](https://github.com/aresstack/3d4j). Generate original game music and UI sound effects from machine-readable descriptions returned by a text model. Keep AI calls separate from local synthesis and playback.

## Modules

- **`music`** — Original multitrack composition renderer, recovered from the Gemma/Cubase-style experiment. Accepts an eight-bar, four-track TSV arrangement, exports editable Standard MIDI Format type 1 and mono PCM WAV previews (one per instrument plus mix). Uses the Java Sound API only.
- **`instruments`** — Registry of researched libraries (GeneralUser GS, MuseScore General, VSCO 2 CE, VPO, Salamander, Spanish Classical Guitar, FSBS Clean, FreePats, SFZ Instruments), local path storage and license/source links.
- **`studio`** — Swing test application with KIPITZ/Gemma chat client, resumable multitrack composition, per-track sampled instruments and WAV mixing.
- **`sounds`** — Game-event effect synthesis. Contains the original KIPITZ/Gemma PowerShell experiment and a Java renderer for the JSON sound plan. Uses Gson for input parsing and the Java Sound API for audio output.

All Java sources compile with `--release 8`, including when the build itself runs on Java 21.

## Rolling release

Every successful build on `main` updates the existing [latest release](https://github.com/aresstack/audio/releases/tag/latest); binaries are published under **stable names and URLs**:

- [audio-studio.jar](https://github.com/aresstack/audio/releases/download/latest/audio-studio.jar) — Swing-based Gemma composer and SF2/SF3/SFZ instrument test bench
- [music-renderer.jar](https://github.com/aresstack/audio/releases/download/latest/music-renderer.jar)
- [sounds-renderer.jar](https://github.com/aresstack/audio/releases/download/latest/sounds-renderer.jar)
- [music-soundfont-renderer.jar](https://github.com/aresstack/audio/releases/download/latest/music-soundfont-renderer.jar) — renders an existing MIDI file through an installed FluidSynth and a user-supplied SoundFont

**Same Gemma composition, different instrument sounds:**
- [District Dawn – GeneralUser GS (MP3)](https://github.com/aresstack/audio/releases/download/latest/district-dawn-generaluser.mp3)
- [District Dawn – original Java synthesis (MP3)](https://github.com/aresstack/audio/releases/download/latest/district-dawn-synth.mp3)
- [GeneralUser WAV (44.1 kHz)](https://github.com/aresstack/audio/releases/download/latest/district-dawn-generaluser.wav) · [Original WAV](https://github.com/aresstack/audio/releases/download/latest/district-dawn-synth.wav) · [MIDI](https://github.com/aresstack/audio/releases/download/latest/district-dawn.mid)

Both are runnable with `java -jar` and a JDK/JRE 8 or newer. The `sounds` JAR includes Gson; neither program needs KIPITZ to render an existing plan. Only the original generated *District Dawn* comparison music is published as audio files; these are not binary dependencies. The 32 MB SoundFont is not redistributed.

## Build

Use the included **Gradle 8.14.3 wrapper** with JDK 17+ (JDK 21 recommended). Java compilation targets Java 8:

```sh
bash ./gradlew clean build
bash ./gradlew :music:jar :sounds:jar
bash ./gradlew :music:renderDemo
```

Use the committed demo arrangement without calling a model:

```sh
java -jar music/build/libs/music-renderer.jar music/examples/demo-arrangement.tsv music/build/my-demo
```

Generate short WAV effects from a validated JSON sound plan:

```sh
gradle :sounds:run --args="sounds/examples/sound-plan.json sounds/build/effects"
```

Both renderer JARs are self-contained for their CLI use cases; the sound renderer packages Gson into its runnable JAR.

## KIPITZ / Gemma experiments

PowerShell scripts reside in `music/experiments/` and `sounds/experiments/`. Both assume **`$ApiKey` and `$CompletionsUrl` are already defined in the current PowerShell session**. Never commit credentials or API responses containing secrets.

- `music/experiments/kipitz-gemma-multitrack.ps1`: sends one composition-plan request and four instrument-part requests to `google/gemma-4-31B-it`; saves JSON and TSV; uses the compiled `music/build/libs/music-renderer.jar` to generate MIDI and WAV files. Build `:music:jar` first using the wrapper (or download the rolling-release JAR into `music/build/libs`). Previously saved model answers are reused on restart; pass `-Regenerate` to request every step again. The PowerShell script is self-contained and also works when pasted into an interactive session (it downloads the release renderer JAR to a temporary cache if no locally built JAR can be found). Off-chord bass/harmony notes are corrected deterministically for the rendered TSV while the original JSON remains unchanged; corrections appear in `harmonic-corrections.txt`. Output: `%TEMP%\kipitz-gemma-multitrack\` and `%TEMP%\kipitz-gemma-multitrack.zip`.
- `sounds/experiments/kipitz-gemma-sounds.ps1`: requests four sound designs; synthesizes WAV files directly with constrained-language-compatible PowerShell. Output: `%TEMP%\kipitz-gemma-sounds\` and `%TEMP%\kipitz-gemma-sounds.zip`.

These requests are initiated only by a user launching the scripts; no remote API is called by the Java renderers. No generated WAVs, private API settings, or audio-renderer binaries are tracked in Git (the standard Gradle wrapper JAR is build tooling).

## Arrangement TSV (music)

One tab-separated record per line:

```text
tempo   108
track   melody  10  0
track   harmony 48  1
track   bass    32  2
track   drums   0   9
note    melody  0   480 72  80
```

Fields are separated by actual tab characters (the example is visually spaced for readability). `track`: ID, zero-based General MIDI program, MIDI channel. `note`: track ID, tick start, tick length, MIDI pitch, velocity. The current renderer expects four named parts (`melody`, `harmony`, `bass`, `drums`), tempo 60–180 BPM and eight 4/4 bars at 480 ticks per quarter note. It is a **prototype-specific contract**, not a general MIDI sequencing API yet.

## Extending the project

Keep descriptive models and input validation separate from synthesizers, encoders and the user-facing CLI. Planned areas include reusable waveform strategies, sound fonts, stereo mixing, dynamic instruments and more robust structured AI plans. In production, validate model output before rendering, bound sample counts and durations, and treat file names from models as untrusted.

## Swing Audio Studio

Run: `java -jar audio-studio.jar` (Java 8 or newer).

1. Open **Instrumentenbibliotheken**, select a library, open its upstream page, download it locally and register its `.sf2`, `.sf3` or specific `.sfz` file. Keep SFZ sample files at their original relative paths.
2. Open **Komposition und Wiedergabe**, enter your KIPITZ chat completions URL and API key (masked, never persisted), model `google/gemma-4-31B-it`, and optional HTTP proxy hostname/port.
3. Enter a music description and click **Mit Gemma komponieren**. The app generates the plan and four tracks through five model requests; completed valid JSON is cached in the output directory for resumable runs.
4. Select a locally installed bank for each track, or leave **Java synthesis (built in)**. Render the mix using FluidSynth (SF2/SF3) and/or sfizz_render (SFZ).
5. Use **Demo laden** to play the bundled original Gemma song without any API calls. Click **Abspielen** to hear mixed, reference or separate tracks.

The native FluidSynth and sfizz_render programs must be installed separately and available on PATH or entered as full executable paths. Full sample libraries (often gigabytes) are not bundled. Neither sources nor audio data are downloaded without user action. Java's default proxy configuration is used when no explicit HTTP proxy is entered.

Settings save only local soundbank paths under `~/.aresstack/audio/instruments.properties`; no API key is stored. Sample-library license terms vary; VPO is from mixed origins and Salamander requires attribution. Some SFZ libraries use ARIA-specific extensions that may not work identically with sfizz. Polyphone can convert some SFZ to SF2 but can lose modulation or articulation features.

Build the Swing app with `bash ./gradlew :studio:jar`, or `gradlew.bat :studio:jar` on Windows. CI runs `java -jar studio/build/libs/audio-studio.jar --smoke-test` without opening a desktop.

The instrument library can import **entire folders**, discovering up to 2,000 SF2/SF3/SFZ presets recursively. Each file becomes its own selectable track instrument; sample data stays wherever it was extracted. This supports large orchestral SFZ collections without repackaging or converting them to SF2. The CI additionally renders an actual GeneralUser GS stem inside the studio and verifies the combined MIDI/WAV output.
