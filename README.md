# audio

Small, modular Java audio experiments for [3d4j](https://github.com/aresstack/3d4j). Generate original game music and UI sound effects from machine-readable descriptions returned by a text model. Keep AI calls separate from local synthesis and playback.

## Modules

- **`music`** — Original multitrack composition renderer, recovered from the Gemma/Cubase-style experiment. Accepts an eight-bar, four-track TSV arrangement, exports editable Standard MIDI Format type 1 and mono PCM WAV previews (one per instrument plus mix). Uses the Java Sound API only.
- **`sounds`** — Game-event effect synthesis. Contains the original KIPITZ/Gemma PowerShell experiment and a Java renderer for the JSON sound plan. Uses Gson for input parsing and the Java Sound API for audio output.

All Java sources compile with `--release 8`, including when the build itself runs on Java 21.

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

`music-renderer.jar` is self-contained. The `sounds` launcher uses Gson at runtime, so use Gradle's `:sounds:run` task (or include the runtime classpath when launching Java).

## KIPITZ / Gemma experiments

PowerShell scripts reside in `music/experiments/` and `sounds/experiments/`. Both assume **`$ApiKey` and `$CompletionsUrl` are already defined in the current PowerShell session**. Never commit credentials or API responses containing secrets.

- `music/experiments/kipitz-gemma-multitrack.ps1`: sends one composition-plan request and four instrument-part requests to `google/gemma-4-31B-it`; saves JSON and TSV; uses the compiled `music/build/libs/music-renderer.jar` to generate MIDI and WAV files. Build `:music:jar` first using the wrapper. Output: `%TEMP%\kipitz-gemma-multitrack\` and `%TEMP%\kipitz-gemma-multitrack.zip`.
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
