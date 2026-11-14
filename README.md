# audio

Small, modular Java audio experiments for [3d4j](https://github.com/aresstack/3d4j). Generate original game music and UI sound effects from machine-readable descriptions returned by a text model. Keep AI calls separate from local synthesis and playback.

## Modules

- **`music`** — Original multitrack composition renderer, recovered from the Gemma/Cubase-style experiment. Accepts an eight-bar, variable-track TSV arrangement (any number of tracks; beyond 15 melodic ones, channels are reused on further MIDI ports), exports editable Standard MIDI Format type 1 and mono PCM WAV previews (one per instrument plus mix). Uses the Java Sound API only.
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

1. Press the **Soundbänke** key under the display. The table distinguishes **Installiert**, **Download verfügbar** and **Manuell**. Select a library and click **Herunterladen**. At the right, **Alle verfügbaren laden** installs the four verified direct-download libraries in one action, after a size confirmation. These are GeneralUser GS (SF2, checksum verified), MuseScore General (SF3), Spanish Classical Guitar (compact SF2, unpacked from 7z), and FSBS Clean Electric Guitar (compact SF2, unpacked from 7z). Downloaded banks are stored and registered automatically in `~/.aresstack/audio/libraries/`.
2. For the large and multi-package libraries **VSCO 2 CE, Virtual Playing Orchestra, Salamander Grand Piano**, and the **FreePats/SFZ Instruments collections**, use **Download-Seite öffnen** and then **Datei / Ordner importieren …**. Importing a directory discovers up to 2,000 SF2/SF3/SFZ instrument definitions without copying the sample data. VPO needs both its WAV archive and its SFZ script archive extracted to the same folder. The app does not silently download gigabytes.
3. Start with **Demo laden** in the small menu beside the choice of the composition and the round **RENDER** key of the transport to hear the supplied original Gemma composition without a new API call. The built-in Java synthesizer works without any downloaded bank.
4. For a new song, click **Neu …** under **Stück**, give it a name, describe the music and press **Komponieren**. That creates a **new composition folder**, preserving older cache and WAV files. Completed model responses are cached within the chosen output folder and reused after restart.
5. Choose a downloaded instrument independently for each track. General-MIDI banks allow program numbers 0–127. Install FluidSynth (SF2/SF3) or sfizz_render (SFZ) separately and set their executable paths. Press RENDER, then the round PLAY key (or the space bar), or **Solo** on a channel strip to hear a single track.
6. Set your KIPITZ chat completions URL, masked API key and model (`google/gemma-4-31B-it`), or click **KeePass verbinden** to pair with KeePassRPC and take over a selected entry (API address and password). **Modelle laden** retrieves model IDs over the configured proxy. Audio Studio reads no settings of other programs: model and answer length start with its own defaults (`google/gemma-4-31B-it`, 16384 tokens), the address and the credentials you enter here. Save your choices with **Einstellungen speichern**; they reload on restart.
7. **Proxy:** the default setting is **Standard (Windows: PAC per PowerShell, sonst kein Proxy)**. The resolver uses the same `win-proxy-java:0.2.0` and the same defaults as 3d4j, but only Audio Studio's own settings: no file of 3d4j or of the Enterprise AI Client is read for the proxy. Windows without explicit config uses `PAC_URL_POWERSHELL`, other systems no proxy; options include `PAC_URL_WINDOWS_SETTINGS`, `PAC_URL_WSCRIPT`, `PAC_URL_MANUAL`, `WINDOWS_STATIC_PROXY`, `MANUAL_PROXY` and `DISABLED`. **Proxyweg prüfen** displays the resolved route for the selected destination. Both KIPITZ traffic and sample downloads follow the same route. There is **no silent DIRECT fallback** when PAC fails.

### Streamed answers

Every model request is sent with `stream: true`, and the pieces are put together while they arrive. A proxy or gateway on the way gives up on a connection that stays silent (HTTP 504); a long answer that only comes at the end keeps it silent for minutes, a stream does not. Reading stops at the end mark `[DONE]` rather than when the connection closes, an answer whose connection breaks after the model has finished is taken, and a request whose stream breaks in the middle (or whose connection fails) is sent again, up to twice. A service that refuses to stream is asked the old way for the rest of the run. Cached answers are not requested again either way.

### Local settings and security

The application deliberately stores both settings **and the API key in clear text**, as requested, in `~/.aresstack/audio/studio.properties` (on Windows: `%USERPROFILE%\\.aresstack\\audio\\studio.properties`). The app attempts owner-only POSIX permissions on Unix; on Windows the user's profile ACL applies. Do not copy this file into a repository or share it. The instrument registry is separate: `~/.aresstack/audio/instruments.properties`. No credentials or actual sample banks are committed to GitHub.

The downloader uses a bounded HTTP(S) transfer, verifies the known GeneralUser SHA-256, checks SoundFont RIFF signatures, extracts 7z instrument archives in Java with path traversal protection, and registers successfully installed banks. It uses the **3d4j-compatible corporate proxy resolver**, not a separate simplistic HTTP proxy. Both Java's default roots and the Windows certificate store are trusted on Windows; TLS verification stays enabled. **CI runs a separate integration workflow that downloads and installs all four direct sources**; see [Verify soundbank downloads](https://github.com/aresstack/audio/actions/workflows/soundbank-downloads.yml).

### Scope and limitations

The UI is a lightweight test application, not yet a complete DAW. The Gemma composer still creates eight bars in C major, but accepts any number of user-selected tracks instead of always composing four. Real SF2/SF3/SFZ playback requires the appropriate external renderer (FluidSynth or sfizz_render), not merely the instrument samples. The offline demo with Java synthesis does not.

Sample-library licenses differ. Virtual Playing Orchestra draws on multiple origins; Salamander Grand Piano is CC BY 3.0. Not every ARIA-specific SFZ opcode can be used by sfizz. **Polyphone** may convert SFZ into SF2, but articulation and modulation information can be lost.

Build with `bash ./gradlew :studio:jar` or `gradlew.bat :studio:jar` on Windows. CI runs a headless launch test, a real GeneralUser stem/mix, cached Gemma composition tests, 7z extraction tests, and the existing Windows PowerShell compatibility tests.

### KeePassRPC integration

The implementation uses the same **MIT-licensed KeePassRPC transport** as 3d4j / Enterprise AI Client, ported to Java 8. A running and unlocked **KeePass with KeePassRPC plugin** is required; the first use prompts for KeePass's one-time pairing code. The Studio uses its **own identity** and stores the pairing information separately in `~/.aresstack/audio/keepass.properties`. It does not transfer or bundle a KeePass database, pairing key, or secret from the 3d4j repository. The chosen KeePass entry's URL is treated as the OpenAI-compatible base URL, with `/chat/completions` appended when needed.

An optional proxy-authentication entry can be named under **Anmeldung** in the proxy settings. Java performs HTTP proxy BASIC authentication using the selected KeePass credentials when the proxy requests them. On workstations whose proxy supports integrated Windows authentication, Java's usual authentication mechanism remains available. If a proxy route cannot be resolved, the request fails with diagnostics instead of bypassing the company network.

UI layout: a dark mixing desk on FlatLaf, with the rounded modules of 3d4j's dialogs and its pink as the signal colour.

- **Transport** (top): the name, and below it the choice of the composition with a small menu beside it (new, open, show folder, propose instruments only, plan, demo); a display with the time, the loaded source and the flags FILE / PLAY / AI; under it one raised key per page, as on a player (Komposition, Arrangement, Bühne, Soundbänke, Einstellungen, Protokoll); round keys for play, stop, render and cancel; master level meters. The space bar plays and pauses. Messages appear in a status line at the lower edge of the window; a click on it opens the log.
- **Komposition** page, where a piece is written: what music it shall be (this is what the AI is told), who decides on the instruments, and the compose key. Below it the piece as a **conductor's score**: every track on its staff with its clef, bars, notes with stems, flags and beams, rests, dots, sharps, ledger lines and ties, several lines down the sheet, each headed with the bars it holds; beams lean with the notes and neighbouring notes of a chord stand side by side; while the piece plays a line follows and the sheet turns along. The score is derived from `arrangement.tsv` (`studio/score/Score`): one voice per staff, sixteenths as the finest value, C major. The symbols are outlines taken from the font Bravura (SIL Open Font License 1.1, `score-glyphs-OFL.txt`); no notation library is used. The menu beside the choice of the composition saves the score as a picture (PNG, dark on white). Composing switches to the arrangement.
- **Arrangement** page, where what was composed is adjusted, laid out like a simple program for cutting music: the tracks fill the page, one under the other.
- **Lanes**: a ruler with the bars, one lane per track with its notes (from `arrangement.tsv`), and below the lanes the loaded mix as a waveform. A line marks the playback; a click in the lanes jumps there. The lanes share the height of the page; with many tracks the list scrolls.
- **Head of a lane** (in front of its notes): the name, below it role and instrument, a fader for the level and **M** to mute. A click on the name chooses the track for listening alone (a click on **Mix** chooses the mix again), a double click renames it in place (its notes and its sound go with the new name). Everything else is in the menu behind **⋯**, which also opens with the right mouse button: listen alone, compose anew by the AI, rename, role, instrument (the 128 General MIDI instruments by family), the description the AI gets, sound bank, move up or down, remove. **+ Spur** below the last lane adds a track.
- **Compositions**: the pieces are a list, not a path. Each piece is a folder below `~/.aresstack/audio/compositions` with a name and its description in `song.properties`; **Neu …** asks for a name, the menu beside the choice opens a folder or an arrangement file from elsewhere, **Ordner** shows the files (WAV, MIDI, the AI's answers). One line tells tempo, key, metre, length and mood from the AI's plan.
- **Komponieren**: one key. Before it, the choice of who decides on the instruments: **KI wählt Instrumente** (proposal, composition and rendering in one go, the former autopilot) or **Meine Spuren** (compose exactly the tracks below). In the menu beside the choice of the composition: proposal only proposes instruments, **Plan** shows the AI's plan and answers, **Demo** loads the bundled piece. If the description no longer is the one the piece was planned from, composing for **Meine Spuren** asks whether to compose everything anew or only what is missing.
- In a narrow window the display drops its less important parts.

- While the studio composes or renders, the head of every lane says how its own track is getting on (waiting, working, done, failed) with a blinking colour bar, and the status line counts all of them: how many are worked on at the same time, how many are done.

The meters and the waveform are drawn from the rendered WAV files (loudness per hundredth of a second, `DeckPlayer.measure`); they show what was rendered, they do not change it. Each lane has a fader and a mute key; both act on the mix and show the same as the stage. The Java 8 runtime smoke test installs the theme, resolves a disabled proxy route, loads KeePassRPC and renders the supplied demo headlessly.

**Scope:** CI validates Java 8, simulated KIPITZ calls through a local HTTP proxy, proxy routing modes, and KeePassRPC class loading. Actual company PAC/KeePass pairing and a GUI review must be tested on the target Windows environment; no corporate network or secret is available to CI.


### Built-in Java Gervill for SF2 (the new default)

**FluidSynth is no longer required for SF2 sample playback or WAV export.** Audio Studio now renders SF2 instruments (including GeneralUser GS, Spanish Classical Guitar and FSBS Clean Electric Guitar) with the **Gervill software synthesizer built into Java 8 and newer**. It produces 44.1-kHz stereo WAVs offline without a sound card or any native `fluidsynth.exe`.

Under **Einstellungen → Klangerzeugung → SF2-Synthesizer**, the default is **Java / Gervill (integriert)**. Optionally switch to FluidSynth if you have it installed. **SF3** (e.g. MuseScore General) still requires FluidSynth, and **SFZ** requires sfizz. If an external executable is missing, the original Java audio track is preserved and used as a fallback instead of losing the composition.

The JDK does not expose Gervill's offline PCM stream through its standard public Java Sound API. Audio Studio isolates this internal API access in a second Java process using the same installed Java runtime. For Java 9+ the program automatically supplies the necessary `--add-exports` flag; Java 8 requires no flag. Users do not have to configure these parameters manually. CI renders the actual GeneralUser SF2 on both Java 8 and Java 21, verifies non-silent WAV output and runs the studio's GeneralUser end-to-end smoke test.

To continue an interrupted song, reopen Audio Studio. It automatically recognizes an existing `arrangement.tsv` in the saved output directory. Press RENDER, then PLAY. No additional AI requests are made.


## The stage: mixing in a room

The **Bühne** page shows the stage from above: the back wall at the top, the listener in front at the bottom, each track a disc in the colour of its channel. A disc is dragged to its place; the mouse wheel over it changes its level. Beside the stage are the sliders of the chosen instrument (side, depth, level, width, share of the room, **Stumm**, **Vor KI schützen**) and the room (dry studio, chamber hall, concert hall, cathedral; reverberation time and amount).

What the stage does to the sound (`studio/mix/SpatialStemMixer`, float throughout, 44.1 kHz, only the finished mix is written as 16-bit PCM; 24 bit is supported by the mixer):

- level per track, optionally moving over the bars (**Verlauf**: points of bar and dB, interpolated without clicks);
- width (mid/side) and equal-power panning from the angle at which the listener sees the track;
- distance: the direct sound gets quieter and duller, the share of the room larger;
- three early reflections (left wall, right wall, back wall) from mirrored sources;
- one reverberation shared by all tracks, whose decay time follows the room; the tail is rendered to its end;
- the finished mix is levelled as a whole (never clipped, never compressed).

The stage is kept per piece in `spatial-scene.json` (versioned). A piece from before the stage gets the usual seating of the roles: melody in front, bass and drums in the middle, harmony to the sides, texture at the back. `stems.tsv` notes which WAV holds each track after rendering. Moving anything on the stage or a fader only runs `StageMixService` again: no AI request and no synthesis; what plays continues with the new mix.

The course of a track's level can be set by hand in the field under the sliders of the instrument: a click sets a point, a point is dragged, the right mouse button removes it. **Master** sets the level of the finished mix (the mix is levelled to full scale first, the master turns it down). A track chosen for listening alone is heard as it stands on the stage, with its level, its place and the room, also when it is muted in the mix. The menu beside the choice of the composition saves the mix as a 24-bit WAV.

**KI mischt ab** is the last step before mixing: the AI is told what each track plays (notes per bar, range, velocities) and proposes levels, their course over the piece, places and the room. The proposal is drawn dashed over the stage and only applies after **Übernehmen**; protected instruments keep their place. With **KI wählt Instrumente**, the instrument proposal carries a stage in the same request and it is applied at once. Everything the AI says about the stage is clamped into range; unknown tracks are dropped, forgotten ones get their usual seat.

No audio library was added for this. AskAI's `audio-dsp` module is a 16-bit speech pipeline; its filter formulas (Audio EQ Cookbook) were taken over, the float processing is new. The previous `WaveStemMixer` stays in the code as the plain reference. Not built yet: convolution reverb, HRTF/3D, moving sources, a real-time preview while dragging.

## Variable instrumentation: manual, AI suggestion or autopilot

The **Arrangement** page contains the editable tracks.
The original Melody, Harmony, Bass and Drums entries are just the initial defaults. Any track can be edited or removed through the menu of its lane, and **+ Spur** adds a new instrument.

| Property of a track | Meaning |
| --- | --- |
| Name (top) | Unique filename-safe ID, such as `melody`, `cello` or `flute` |
| Rolle | `melody`, `harmony`, `bass`, `drums` or `texture` |
| Instrument | General MIDI instrument, shown as "73 · Flute" (zero-based program 0–127) |
| Beschreibung für die KI | Musical description used in the model prompt |
| Soundbank | Locally installed library or built-in Java synth |
| ✕ | Remove any track, including a default track |

Three workflows are supported. **Meine Spuren**: edit the strips and click **Komponieren**. **Vorschlag**: generate only an editable proposal of appropriate instruments, without notes. **KI wählt Instrumente**: generate a proposal, then the shared musical composition plan, then each selected track and the final WAV mix. Autopilot selects an installed GeneralUser SF2 bank automatically when it is available; otherwise it uses Java synthesis.

Under **Einstellungen → KI-Zugang**, **Parallel** defaults to **32**, independently of 3d4j's default of one. The real request count is limited to the number of configured tracks. The AI proposal and the shared eight-bar plan are each created sequentially, and then all part requests can run concurrently. Reducing parallelism can help with provider rate limits.

General MIDI reserves channel 10 for percussion and leaves 15 melodic channels per port. There is no upper limit of tracks: the sixteenth melodic track begins again at the first channel, and in `arrangement.mid` tracks that share a channel are told apart by a MIDI port event. Every track is rendered alone and the stems are mixed, so shared channels never collide in the studio; a simple MIDI player that ignores ports will play such a file wrongly. Several drum tracks may share the percussion channel. The TSV track line has five fields: `track<TAB>id<TAB>program<TAB>channel<TAB>role`. Original four-field lines still work. The Java renderer outputs an individual MIDI track and WAV stem per configured row, then mixes them.

Each composition folder stores the editable list in `tracks.json` and the optional AI proposal in `instrument-proposal.json`. Cached notes for changed instrument descriptions use a prompt fingerprint to avoid reusing the wrong content. Existing `composition-plan.json`, `melody.json`, `harmony.json`, `bass.json`, and `drums.json` remain compatible with the original default arrangement.

CI tests six dynamically selected tracks against a fake OpenAI-compatible server, verifies overlapping generation requests, reuses cached results without additional API requests, and renders six WAV stems and a multitrack MIDI. A separate offline test validates that an AI proposal can be reviewed and edited before composing.
