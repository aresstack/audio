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

Legacy PowerShell scripts reside in `music/experiments/` and `sounds/experiments/`. Both assume **`$ApiKey` and `$CompletionsUrl` are already defined in the current PowerShell session**. Never commit credentials or API responses containing secrets.

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

Start the current [audio-studio.jar](https://github.com/aresstack/audio/releases/download/latest/audio-studio.jar) with `java -jar audio-studio.jar` (Java 8 or newer). The Swing application is a **replacement for the PowerShell music experiment**.

### First-time use

1. Open **1 · Bibliotheken installieren**. The table distinguishes **Installiert**, **Download verfügbar** and **Manuell**. Select a library and click **Herunterladen**. At the right, **Alle verfügbaren herunterladen** installs the four verified direct-download libraries in one action, after a size confirmation. These are GeneralUser GS (SF2, checksum verified), MuseScore General (SF3), Spanish Classical Guitar (compact SF2, unpacked from 7z), and FSBS Clean Electric Guitar (compact SF2, unpacked from 7z). Downloaded banks are stored and registered automatically in `~/.aresstack/audio/libraries/`.
2. For the large and multi-package libraries **VSCO 2 CE, Virtual Playing Orchestra, Salamander Grand Piano**, and the **FreePats/SFZ Instruments collections**, use **Download-Seite öffnen** and then **Vorhandene Datei / Ordner importieren**. Importing a directory discovers up to 2,000 SF2/SF3/SFZ instrument definitions without copying the sample data. VPO needs both its WAV archive and its SFZ script archive extracted to the same folder. The app does not silently download gigabytes.
3. Switch to **2 · Komponieren und abspielen**. Start with **Demo laden (ohne KI)** and **3 · Rendern** to hear the supplied original Gemma composition without a new API call. The built-in Java synthesizer works without any downloaded bank.
4. For a new song, click **1 · Neues Stück**, enter your music description and choose **2 · Mit Gemma komponieren**. That creates a **new composition folder**, preserving older cache and WAV files. Completed model responses are cached within the chosen output folder and reused after restart.
5. Choose a downloaded instrument independently for each track. General-MIDI banks allow program numbers 0–127. Install FluidSynth (SF2/SF3) or sfizz_render (SFZ) separately and set their executable paths. Click **3 · Rendern**, then **4 · Anhören** or select a single track.
6. Set your KIPITZ chat completions URL, masked API key and model (`google/gemma-4-31B-it`), or click **KeePass verbinden / Einträge laden** to pair with KeePassRPC and take over a selected entry (API address and password). **Modelle laden** retrieves model IDs over the configured proxy. Audio Studio also suggests the base URL, model and KeePass entry from 3d4j or the Enterprise AI Client when there is no local setting. Save your choices with **Einstellungen speichern**; they reload on restart.
7. **Proxy:** the default setting is **Aus 3d4j / Windows übernehmen**. The resolver uses the same `win-proxy-java:0.2.0` as 3d4j and gets its values in order from Audio Studio, `~/.3d4j/generator.properties`, and the Enterprise AI Client. Windows without explicit config uses `PAC_URL_POWERSHELL`; options include `PAC_URL_WINDOWS_SETTINGS`, `PAC_URL_WSCRIPT`, `PAC_URL_MANUAL`, `WINDOWS_STATIC_PROXY`, `MANUAL_PROXY` and `DISABLED`. **Proxyweg prüfen** displays the resolved route for the selected destination. Both KIPITZ traffic and sample downloads follow the same route. There is **no silent DIRECT fallback** when PAC fails.

### Local settings and security

The application deliberately stores both settings **and the API key in clear text**, as requested, in `~/.aresstack/audio/studio.properties` (on Windows: `%USERPROFILE%\\.aresstack\\audio\\studio.properties`). The app attempts owner-only POSIX permissions on Unix; on Windows the user's profile ACL applies. Do not copy this file into a repository or share it. The instrument registry is separate: `~/.aresstack/audio/instruments.properties`. No credentials or actual sample banks are committed to GitHub.

The downloader uses a bounded HTTP(S) transfer, verifies the known GeneralUser SHA-256, checks SoundFont RIFF signatures, extracts 7z instrument archives in Java with path traversal protection, and registers successfully installed banks. It uses the **3d4j-compatible corporate proxy resolver**, not a separate simplistic HTTP proxy. Both Java's default roots and the Windows certificate store are trusted on Windows; TLS verification stays enabled. **CI runs a separate integration workflow that downloads and installs all four direct sources**; see [Verify soundbank downloads](https://github.com/aresstack/audio/actions/workflows/soundbank-downloads.yml).

### Scope and limitations

The UI is a lightweight test application, not yet a complete DAW. The original Gemma pipeline still composes eight bars with four tracks. Real SF2/SF3/SFZ playback requires the appropriate external renderer (FluidSynth or sfizz_render), not merely the instrument samples. The offline demo with Java synthesis does not.

Sample-library licenses differ. Virtual Playing Orchestra draws on multiple origins; Salamander Grand Piano is CC BY 3.0. Not every ARIA-specific SFZ opcode can be used by sfizz. **Polyphone** may convert SFZ into SF2, but articulation and modulation information can be lost.

Build with `bash ./gradlew :studio:jar` or `gradlew.bat :studio:jar` on Windows. CI runs a headless launch test, a real GeneralUser stem/mix, cached Gemma composition tests, 7z extraction tests, and the existing Windows PowerShell compatibility tests.

### KeePassRPC integration

The implementation uses the same **MIT-licensed KeePassRPC transport** as 3d4j / Enterprise AI Client, ported to Java 8. A running and unlocked **KeePass with KeePassRPC plugin** is required; the first use prompts for KeePass's one-time pairing code. The Studio uses its **own identity** and stores the pairing information separately in `~/.aresstack/audio/keepass.properties`. It does not transfer or bundle a KeePass database, pairing key, or secret from the 3d4j repository. The chosen KeePass entry's URL is treated as the OpenAI-compatible base URL, with `/chat/completions` appended when needed.

An optional proxy-authentication entry can be named in **Proxy-Anmeldung**, or inherited via `network.proxy.auth.mode=BASIC` and `network.proxy.auth.credentialRef=keepass:<title>` from 3d4j. Java performs HTTP proxy BASIC authentication using the selected KeePass credentials when the proxy requests them. On workstations whose proxy supports integrated Windows authentication, Java's usual authentication mechanism remains available. If a proxy route cannot be resolved, the request fails with diagnostics instead of bypassing the company network.

UI layout: FlatLaf with the rounded, card-based 3d4j design translated to Swing, using navy/teal rather than the game's pink theme. The Java 8 runtime smoke test installs the theme, resolves a disabled proxy route, loads KeePassRPC and renders the supplied demo headlessly.

**Scope:** CI validates Java 8, simulated KIPITZ calls through a local HTTP proxy, proxy routing modes, and KeePassRPC class loading. Actual company PAC/KeePass pairing and a GUI review must be tested on the target Windows environment; no corporate network or secret is available to CI.
