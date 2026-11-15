# Architekturprüfung: Weiterentwicklung AresStack Audio Studio

**Stand:** 15. November 2026 · **Geprüfter Code:** `main`, Commit `e60793f` · **Status:** Planung, keine Implementierung beauftragt

Dieses Dokument beantwortet den Architekturbericht vom 15. November 2026 (Abschnitte 16 und 17) auf Grundlage einer erneuten Prüfung des Repositorys. Es liefert Ist-Architektur, Zielarchitektur, Abweichungsanalyse, Architekturentscheidungen, Migrationskonzept, Umsetzungsplan und Abnahmekonzept.

**Grenzen der Prüfung.** Gelesen wurde ausschließlich `aresstack/audio`. Der Code von AskAI und 3d4j liegt nicht in diesem Repository und konnte nicht geprüft werden; alle Aussagen dazu sind als Annahme gekennzeichnet. Aussagen zu externen Bibliotheken stammen aus allgemeinem Kenntnisstand und sind vor einer Entscheidung (Version, Lizenz, Abhängigkeiten) zu verifizieren. Der Build wurde für diese Prüfung nicht ausgeführt; die CI für `e60793f` gilt laut Bericht als grün.

---

## 0. Kurzfassung

1. **Der Bericht stimmt mit dem Code überein**, bis auf einige Punkte, die schwerer wiegen als dort beschrieben (Abschnitt 1.4). Java 21 mit Java-8-Launcher ist bestätigt (`build.gradle`: `options.release = 21`; `studio/build.gradle`: Source-Set `launcher` mit `release = 8`; CI startet das JAR mit einem echten Java 8).
2. **Der eigentliche Engpass ist nicht die KI, sondern das fehlende Werkmodell.** Heute ist `arrangement.tsv` die Wahrheit, sie wird bei jedem Komponieren aus den KI-Antwort-Caches neu zusammengesetzt (`GemmaMusicComposer.assemble`) und von sechs unabhängigen Parsern gelesen. Solange das so ist, kann keine manuelle Änderung überleben (A9, A10) und keine Änderungsgrenze technisch erzwungen werden (A5).
3. **Empfehlung:** ein neues, UI-freies Modul `composition` mit einem unveränderlichen Werkmodell (`composition.json`), in dem **Bahn (Track), Besetzungsstimme (Part), Stimme (Voice) und Clip** getrennt sind. Alle Änderungen, manuell wie durch KI, laufen als **Patch mit Änderungsbereich** durch dieselbe Schicht. Die erste Komposition wird zum Sonderfall von `APPEND` auf ein leeres Werk.
4. **Typisierte Bahnen** (MIDI oder Audio), keine gemischten. Instrumentenwechsel innerhalb einer Bahn entsteht dadurch, dass ein Clip auf einen anderen Part zeigt.
5. **Ein zweites neues Modul `audio-core`** (nur JDK, blockbasiert) entsteht durch Herauslösen des vorhandenen Mixers, nicht durch Neuentwicklung. Offline-Mix und Echtzeit-Vorschau benutzen denselben Graphen.
6. **Zwingende Reihenfolge:** Sicherheitsnetz → Werkmodell mit Import → parametrische Zeit/Tonart → Änderungskern. Danach können Editor, KI-Bearbeitung, inkrementelles Rendern und Audio-Kern parallel entstehen.
7. **Vier Entscheidungen brauchen eine Bestätigung** des Auftraggebers (Abschnitt 8).

---

## 1. Ist-Architektur

### 1.1 Module

| Modul | Inhalt | Abhängigkeiten |
|---|---|---|
| `music` | `MultiTrackMusicRenderer` (Default-Package, CLI): TSV → SMF Typ 1 + additive Java-Synthese (22,05 kHz, mono) je Spur; `SoundFontMidiRenderer` (CLI-Wrapper) | nur JDK |
| `sounds` | JSON-Soundplan → synthetische Effekt-WAVs (`SoundEffectRenderer`, `SoundSynthesizer`, `Waveform`) | Gson |
| `instruments` | Soundbank-Registry, Downloader, Proxy-/Trust-Auflösung | JDK |
| `studio` | Swing-Anwendung, 10 Packages, zusätzlich portierter KeePassRPC-Code | `music`, `instruments`, Gson, Java-WebSocket, FlatLaf |

Gesamtumfang rund 17 000 Zeilen Java, davon `studio` etwa 13 500. Tests: JUnit 4, 17 Testklassen, keine UI-Tests.

### 1.2 Packages in `studio` und ihre tatsächlichen Abhängigkeiten

```
ui ──► application ──► ai ──► instruments
 │         │  ▲
 │         ▼  │          (Zyklus application ⇄ stage)
 │       stage ◄── mix
 │         
 ├──► render ──► instruments
 ├──► score      (liest TSV selbst, keine Abhängigkeit)
 └──► settings, keepass
```

| Package | Klassen | Verantwortung heute |
|---|---|---|
| `ai` | `KipitzChatClient` | OpenAI-kompatibler Aufruf, Streaming, Wiederholung, Abbruch, **Antwort-Cache als Datei** (`generate(…, Path cache)`) |
| `application` | `GemmaMusicComposer`, `CompositionNoteNormalizer`, `InstrumentProposalService`, `MixDirector`, `StudioMusicRenderer`, `StageMixService`, `SongLibrary`, `MusicTrack`, `TrackArrangement`, `TrackArrangementStore` | Anwendungsfälle, aber auch Fachmodell, Dateiformate und Prozesssteuerung in einem |
| `render` | `GervillOfflineRenderer` (Kindprozess), `GervillSoundFontRenderer`, `InstrumentSampleRenderer`, `MidiStemExporter`, `ExternalProcessRunner`, `WaveStemMixer` | Stems je Spur aus isoliertem MIDI: SF2 über Gervill, SF3 über FluidSynth, SFZ über sfizz |
| `mix` | `SpatialStemMixer`, `RoomReverb`, `Biquad` | Räumlicher Offline-Mix ganzer Stems im Speicher |
| `stage` | `SpatialScene`, `SoundSourcePlacement`, `AcousticRoom`, `SpatialSceneStore` | Unveränderliches Bühnenmodell, an `trackId` gebunden; `spatial-scene.json` |
| `score` | `Score` | TSV → Notensysteme, Takte, zwei Stimmen (heuristisch), Bindebögen |
| `ui` | `AudioStudioFrame` (2337 Zeilen), `TrackLanes`, `StagePanel`, `StageView`, `ScoreView`, `ArrangementView`, `DeckPlayer`, … | Oberfläche; `AudioStudioFrame` enthält zusätzlich Ablaufsteuerung, Dateiverwaltung, Cache-Verwaltung, Einstellungen, KeePass, Wiedergabe |

### 1.3 Datenfluss heute

```
Beschreibung
   │  InstrumentProposalService ──► instrument-proposal.json (+ Bühnenvorschlag)
   ▼
tracks.json (TrackArrangementStore)
   │  GemmaMusicComposer.plan ──► composition-plan.json   (fest: 108 BPM, C-Dur, 4/4, 8 Takte, C|G|Am|F|C|G|F|G)
   │  GemmaMusicComposer.generateTracks (parallel) ──► <track>[-hash].json je Spur
   │  CompositionNoteNormalizer ──► note-corrections.tsv
   ▼
arrangement.tsv   ◄── wird bei jedem Lauf vollständig neu geschrieben
   │  MultiTrackMusicRenderer (Kind-JVM) ──► arrangement.mid, <track>.wav, arrangement.wav
   │  MidiStemExporter + InstrumentSampleRenderer (parallel) ──► <track>-isolated.mid, <track>-sampled.wav
   │  stems.tsv
   ▼
StageMixService + SpatialStemMixer (spatial-scene.json) ──► arrangement-instruments.wav
   ▼
DeckPlayer (ganze Datei im Speicher, Austausch während der Wiedergabe)
```

Bestätigt aus dem Bericht: alle in Abschnitt 2.1 genannten Funktionen sind vorhanden. „Einzelne Spur neu komponieren" (`AudioStudioFrame.composeAgain`) verschiebt die Cache-Datei der Spur nach `earlier/` und startet danach **den gesamten Kompositionslauf** neu; die übrigen Spuren bleiben nur deshalb gleich, weil ihre Caches getroffen werden.

### 1.4 Befunde, die über den Bericht hinausgehen

| Nr. | Befund | Beleg | Folge |
|---|---|---|---|
| F1 | Die TSV ist Wahrheit und wird aus KI-Caches regeneriert | `GemmaMusicComposer.assemble` schreibt `arrangement.tsv` immer neu | Manuelle Änderungen gehen beim nächsten KI-Lauf verloren. Blockiert A9, A10, A11. |
| F2 | Sechs unabhängige TSV-Parser | `MultiTrackMusicRenderer.parseArrangement`, `StageMixService.tracks/tempo/rename`, `MixDirector.summaries`, `StudioMusicRenderer.readTrackIds`, `Score.of`, `ArrangementView.Arrangement` | Jede Formaterweiterung muss sechsmal nachgezogen werden; die Parser sind unterschiedlich streng. |
| F3 | Die Acht-Takt-Grenze sitzt auch im MIDI-Schreiber | `MultiTrackMusicRenderer`: `note.tick + note.length > PPQ * 4 * 8` wird abgelehnt; Tempo nur 60–180 | Die Mischgrenze von zehn Minuten ist wirkungslos, solange dieser Schritt davor liegt. |
| F4 | Vier unabhängige Laufzeitgrenzen | Gervill 3 min (`MAX_DURATION_MICROS`), `ExternalProcessRunner` und `SoundFontMidiRenderer` je 5 min Prozesslaufzeit, Mixer 10 min (`MAX_SECONDS`), dazu 256 Noten je KI-Antwort | A14 |
| F5 | `trackId` ist gleichzeitig Identität, Anzeigename, Dateiname, Cache-Schlüssel, MIDI-Spurname und Bühnenschlüssel | `MusicTrack.requireId`, `StageMixService.rename`, `cachedResponse`, `SoundSourcePlacement.trackId` | Umbenennen schreibt Dateien um; ein Instrumentenwechsel in einer Bahn ist nicht darstellbar. |
| F6 | Der Normalizer verändert Tonhöhen fachlich | `normalizePitch`: Melodie und Textur werden auf die C-Dur-Leiter, Harmonie und Bass auf Akkordtöne der festen Akkordfolge gezogen | In anderen Tonarten würde er korrekte Noten verfälschen; Variation und Chromatik sind ausgeschlossen. |
| F7 | Zyklische Package-Abhängigkeit `application ⇄ stage` | `stage` importiert `application.MusicTrack`, `application` importiert `stage.SpatialScene` | Das Fachmodell liegt im falschen Package. |
| F8 | Fortschrittsmeldung hängt am Komponisten | `GemmaMusicComposer.Progress/TrackProgress` wird von Renderer, Mixer und MixDirector benutzt | Rendern und Mischen hängen von der KI-Klasse ab. |
| F9 | Der Mixer normalisiert auf den Gesamtpegel | `SpatialStemMixer.master`: `CEILING / peak`, bis +12 dB | Nicht kausal: eine Echtzeit-Engine kann das nicht, und eine lokale Änderung verschiebt den Pegel des ganzen Stücks. Entscheidung nötig (E8). |
| F10 | Pegelkurven sind in Takten mit einem festen `secondsPerBar` adressiert | `SoundSourcePlacement.LevelPoint.bar`, `StageMixService.mix` rechnet `4 * 60 / tempo` | Tempo- und Taktartwechsel würden die Kurven verschieben. |
| F11 | Partitur ist auf 4/4 und Sechzehntel festgelegt | `Score.BAR = 16`, `TICKS_PER_SIXTEENTH = 120`, `sharp()` kennt nur C-Dur | Andere Taktarten, Triolen und Tonartvorzeichnung fehlen. |
| F12 | Referenzsynthese läuft immer für alle Spuren in einer Kind-JVM | `StudioMusicRenderer.render` | Kein gezieltes Neurendern möglich. |
| F13 | `MixDirector` rechnet Takte fest | `ticks / (480 * 4)`, Text „4/4" | Muss über die Zeitachse des Werks laufen. |
| F14 | Versionierung existiert nur als Ordner `earlier/` | `putAnswersAside` | Enthält KI-Antworten, nicht Fassungen des Werks. |

### 1.5 Was gut ist und bleiben soll

- **Unveränderliche Wertobjekte** (`SpatialScene`, `SoundSourcePlacement`, `MusicTrack`, `TrackArrangement`): dieses Muster trägt Undo, Vorschau und Nebenläufigkeit und wird für das Werkmodell übernommen.
- **Vorschlag-und-Übernahme** in der Bühne (`StagePanel.setProposal`, gestrichelte Darstellung, „Übernehmen", `SpatialScene.taking` respektiert gesperrte Plätze): das ist bereits das Bedienmuster, das der Bericht für KI-Änderungen an Noten verlangt.
- **Rendern je Spur als Stem** und **Mischen ohne KI und Synthese** (`StageMixService`): die richtige Schnittlinie für inkrementelles Rendern.
- **Nachsichtiges Lesen von KI-Antworten** (`SpatialSceneStore.read` klemmt Werte, ergänzt Fehlendes) und **Prüfprotokoll** (`note-corrections.tsv`).
- **Atomares Schreiben** (Temp-Datei + `Files.move`) in den Stores und im Mixer.
- **Parallele KI-Aufrufe mit Abbruch und Fortschritt je Spur.**
- **Mixer ist deterministisch** („The same input always gives the same output") und bereits getestet (`SpatialStemMixerTest`).

---

## 2. Zielarchitektur

### 2.1 Schichten und Module

```
┌──────────────────────────────────────────────────────────────────────┐
│ studio.ui            Swing: Fenster, Bahnen, Bühne, Partitur, Editor │
├──────────────────────────────────────────────────────────────────────┤
│ studio.application   Anwendungsfälle: ProjectSession, EditService,   │
│                      RenderService, PlaybackService                  │
├───────────────┬───────────────┬──────────────────┬───────────────────┤
│ studio.ai     │ studio.render │ studio.project   │ studio.score      │
│ Planer,       │ StemRenderer- │ Projektordner,   │ Notensatz aus     │
│ Generatoren,  │ Adapter,      │ Revisionen,      │ dem Werkmodell    │
│ Kontext       │ Stem-Cache    │ Import/Export    │                   │
├───────────────┴───────────────┴──────────────────┴───────────────────┤
│ composition  (neues Modul, nur JDK)        │ audio-core (neues Modul,│
│ Werkmodell, Zeitachse, Patch,              │ nur JDK) Blöcke, Graph, │
│ Änderungsbereich, Validierung              │ DSP, Offline/Echtzeit   │
└────────────────────────────────────────────┴─────────────────────────┘
```

Regeln:

- `composition` kennt weder Swing noch KI noch Dateien noch Audio. Es ist reine Fachlogik und damit vollständig unit-testbar.
- `audio-core` kennt keine Musik: nur Signale, Parameter und Zeit in Samples. Es ist das Modul, das AskAI und 3d4j mitbenutzen sollen.
- Abhängigkeiten zeigen nur nach unten. `stage` (Bühnenmodell) wandert fachlich zu `composition` beziehungsweise wird von dort referenziert; der Zyklus F7 entfällt.
- **Nur zwei neue Gradle-Module.** `ai`, `render`, `project`, `score` bleiben Packages in `studio`. Mehr Module brächten Verwaltungsaufwand ohne zusätzliche Trennung.
- `music` bleibt als eigenständiges CLI erhalten (TSV → MIDI/WAV) und wird zum Exportwerkzeug; die Studio-Pipeline benutzt es mittelfristig nicht mehr.

### 2.2 Fachmodell

```
Composition
 ├─ meta          Titel, Genre, Stimmung, ursprünglicher Auftrag, Vorgaben
 ├─ timeMap       PPQ 480; Listen von Tempo-, Taktart- und Tonartereignissen
 ├─ sections[]    lückenlose Formgliederung: Name, Funktion, Takte, Intensität, Harmonik, KI-Anweisung
 ├─ parts[]       Besetzungsstimmen: Instrument, Rolle, Soundbank, Notensystem-Layout, Stimmen
 ├─ tracks[]      sichtbare Bahnen: Name, Art (MIDI | AUDIO), Standard-Part, clips[]
 │    └─ clips[]  MidiClip (Noten relativ zum Clipanfang) | AudioClip (Verweis auf Asset)
 └─ assets[]      Audiodateien, die Quelldaten sind (Aufnahmen, Soundeffekte)

MixScene (eigenes Dokument, Nachfolger von spatial-scene.json)
 ├─ room, master
 ├─ sources[]     je Part: Platz, Pegel, Breite, Hallanteil, stumm, gesperrt
 └─ automation[]  Ziel (Part + Parameter | Master), Punkte auf der Zeitachse
```

Zuordnung zu den Begriffen des Berichts:

| Begriff im Bericht | Zielmodell | Anmerkung |
|---|---|---|
| Composition | `Composition` | unveränderlich; jede Änderung ergibt ein neues Objekt |
| MusicalSection | `Section` | Formgliederung, kein Container für Noten |
| ArrangementTrack | `Track` | nur Bahn im Editor; trägt keine Klangeigenschaften außer einem Standard-Part |
| MidiClip / AudioClip | `MidiClip` / `AudioClip` | Clips einer Bahn überlappen sich nicht |
| InstrumentAssignment + AudioSource + MixerChannel | **`Part`** | bewusst *ein* Begriff, siehe E3 |
| MusicalVoice | `Voice` innerhalb eines Parts | explizit, jede Note trägt `voiceId` |
| AutomationLane | `AutomationLane` in `MixScene` | Pegelkurve von heute ist der erste Fall |
| Bus | zunächst implizit (Raum, Master) | wird im Audio-Kern explizit, sobald Routing gebraucht wird |

**Part** ist „ein Spieler auf der Bühne": genau ein Klangerzeuger, genau ein Platz, genau ein Kanalzug, ein Notensystem (oder ein Doppelsystem) in der Partitur, ein Stem im Renderer. Ein Clip verweist auf einen Part; fehlt der Verweis, gilt der Standard-Part der Bahn. Damit ist „Klavier Takt 1–8, Flöte Takt 9–16 auf derselben Bahn" (A7) zwei Clips mit zwei Parts, und beide stehen unabhängig auf der Bühne (Abschnitt 11.5 des Berichts).

**Stimmen, Notensysteme, Doppelsystem** (Rückmeldung vom 15. November):

- Ein Part hat ein `staffLayout`: `SINGLE` (ein System, Schlüssel automatisch oder fest) oder `GRAND` (Violin- und Bassschlüssel, verbunden; Klavier, Harfe, Orgelmanual).
- Ein Part hat 1..n `Voice`-Einträge mit `id`, Name, Ziel-System (`upper`/`lower` bei `GRAND`) und bevorzugter Halsrichtung.
- Jede Note trägt eine `voiceId`. Die KI liefert sie bei der Komposition mit; für Altbestand und unmarkierte Noten bleibt die heutige Heuristik aus `Score` als **Rückfall**, deren Ergebnis beim Import einmalig als explizite Zuordnung gespeichert wird.
- Die Partitur liest danach nur noch das Modell. Mehr als zwei Stimmen je System und das Doppelsystem sind dann Erweiterungen des Notensatzes, keine Modellfragen mehr.

**Zeit.** Kanonisch sind Ticks bei 480 PPQ (wie heute). Takt und Schlag werden über die `TimeMap` berechnet; Sekunden ebenso. Die `TimeMap` enthält von Anfang an *Listen* von Tempo-, Taktart- und Tonartereignissen. Version 1 der Oberfläche und der KI benutzt jeweils genau ein Ereignis am Anfang; spätere Wechsel im Stück erfordern dann keine Formatänderung. Audio-Clips sind am Anfang in Ticks verankert und haben eine Länge in Samples; sie dehnen sich bei Tempoänderung nicht.

**Tonart.** `Key` (Grundton, Geschlecht) liefert die Vorzeichnung und die enharmonische Schreibweise einer Tonhöhe. `Score.sharp()` wird durch `Key.spell(pitch)` ersetzt; Versetzungszeichen gelten taktweise.

**Stabile IDs.** Jedes Objekt (Section, Part, Voice, Track, Clip, Note) erhält eine kurze, zufällige, unveränderliche ID. Namen sind reine Anzeige. Dateinamen von Stems leiten sich aus Part-ID und Inhalts-Hash ab, nicht aus dem Namen.

### 2.3 Änderungskern

```
EditRequest ─► (KI oder Benutzer) ─► Patch ─► Validator(scope) ─► Proposal ─► Übernehmen ─► Revision
                                                                      └─► Verwerfen
```

- **`Patch`**: geordnete Liste von Operationen auf IDs: `AddPart`, `AddTrack`, `AddClip`, `RemoveClip`, `MoveClip`, `SplitClip`, `SetClipPart`, `AddNotes`, `RemoveNotes`, `ChangeNote`, `SetSections`, `SetTimeMap`, `SetMeta`.
- **`EditScope`**: erlaubte Part-/Track-/Clip-IDs, Tickbereich, erlaubte Operationsarten (zum Beispiel „darf Part anlegen"), optional Übergangszone.
- **`CompositionEditor.apply(composition, patch, scope)`** prüft jede Operation gegen den Bereich und liefert ein neues `Composition` oder eine Liste von Verstößen. Es gibt keinen anderen Weg, ein Werk zu verändern.
- **`Proposal`**: ein angewendeter, noch nicht übernommener Patch samt gerenderter Vorschau. Übernehmen erzeugt eine Revision; Verwerfen löscht nur die Vorschau.

### 2.4 KI-Bearbeitung

```
EditRequest (Art, Bereich, Anweisung, Preset, Schlussart)
   │
   ▼
ContextBuilder        deterministisch, modellunabhängig, mit Digest
   │   globaler Steckbrief · Abschnittstabelle · Harmonieplan
   │   Zusammenfassung je Part · volle Noten im Fenster ± n Takte
   ▼
Planner (ein Aufruf)  EditPlan: Abschnitte, Akkorde je Takt, Intensität,
   │                  welche Parts wo spielen, Motivbezüge
   ▼
Generatoren           je Part × je Abschnitt; Parts parallel,
   │                  Abschnitte eines Parts nacheinander mit Anschluss
   ▼
Normalizer ─► Patch ─► Validator ─► Proposal
```

Schnittstellen: `ChatModel` (heute `KipitzChatClient`), `EditPlanner`, `PartGenerator`, `ContextBuilder`. Die Anwendung baut den Patch selbst aus den Noten, die die KI für genau das angefragte Ziel liefert; die KI adressiert niemals fremde Objekte.

### 2.5 Render-Pipeline

```
Composition ─► MidiWriter (je Part) ─► StemRenderer (SPI) ─► Stem-Cache ─┐
AudioClip-Assets ───────────────────────────────────────────────────────┼─► audio-core Graph ─► WAV | Soundkarte
MixScene ───────────────────────────────────────────────────────────────┘
```

`StemRenderer`-Implementierungen: eingebaute Java-Synthese (aus `MultiTrackMusicRenderer` herausgelöst), Gervill (weiter im Kindprozess wegen `--add-exports`), FluidSynth, sfizz. Der Stem-Cache ist inhaltsadressiert.

### 2.6 Audio-Kern

```
StemSource ─► Strip (Pegel + Automation, Breite, Distanz-Shelf) ─► Panner ─┬─► Master ─► Sink
                                     └─► Send ─► RoomBus (frühe Reflexionen, Hall) ─┘
```

Kernschnittstellen: `AudioBlock` (Float, Stereo, feste Blockgröße), `AudioProcessor.process(block)`, `AudioSource.read(block)`, `AudioSink`, `ParameterSnapshot` (unveränderlich, atomar ausgetauscht, im Prozessor geglättet). `Panner` und `RoomBus` sind austauschbar: Stereo-Panorama heute, binaural/HRTF später; algorithmischer Hall heute, Faltungshall später.

### 2.7 Zerlegung von `AudioStudioFrame`

Kein eigenes Großprojekt, sondern begleitend: jedes Arbeitspaket zieht die von ihm berührte Ablauflogik in einen Dienst der Anwendungsschicht.

| Dienst | übernimmt aus `AudioStudioFrame` |
|---|---|
| `ProjectSession` | geöffnetes Stück, Laden/Speichern, `openSong`, `keepSong`, `loadArrangementForCurrentDirectory` |
| `EditService` | `compose`, `composeAgain`, `generate`, `proposeInstruments`, `startAfresh`, `planStillFits`, `putAnswersAside` |
| `RenderService` | `render`, `remix`, `mixedAgain`, `exportMix`, `refreshStems` |
| `PlaybackService` | `play`, `stopPlayback`, `seek`, `tick`, `listenAlone`, `playbackFile` |
| `SettingsController` | Einstellungen, Proxy, KeePass, Modelle |

---

## 3. Abweichungsanalyse

Legende: ● vorhanden · ◐ teilweise, wiederverwendbar · ○ fehlt

| Anforderung (Bericht) | Stand | Wiederverwendbar | Erforderlich |
|---|---|---|---|
| 4.1 Länge, Tempo, Tonart, Taktart wählbar | ○ | Prompt-Aufbau, Planformat | `TimeMap`, `CompositionSpec`; feste Werte in Composer, Normalizer, MIDI-Schreiber, MixDirector, Score entfernen |
| 4.2 Notation nach Tonart | ○ | Notensatz in `Score`/`ScoreView` | `Key.spell`, Vorzeichnung, Taktarten, Versetzungszeichen je Takt |
| 4.3 Musikalische Abschnitte | ○ | `composition-plan.json` als Keimzelle | `Section`, Abschnittsleiste im Editor |
| 4.4 Steckbrief | ◐ | `song.properties`, `composition-plan.json` | in `Composition.meta` zusammenführen |
| 5 Verlängerung | ○ | paralleler Generator, Cache-Mechanik | Kontextaufbau, Planer, abschnittsweise Generierung, Übergangsmodus |
| 6 Stimme hinzukomponieren | ◐ | `+ Spur`, Neugenerierung fehlender Spuren | Noten der anderen Parts als Kontext; kein Gesamtlauf |
| 7 Gezielt überarbeiten | ◐ | `composeAgain` (ganze Spur) | Bereichsauswahl, Kontext davor und danach, technische Änderungsgrenze |
| 8.2 Vorschau/Übernahme | ◐ | Muster aus `StagePanel` (Vorschlag gestrichelt, „Übernehmen") | `Proposal` für Noten, Vorschau-Rendern |
| 8.4 Revisionen, Undo | ○ | `earlier/` als Idee | Revisionsspeicher, Undo-Stapel |
| 9.1 Noten bearbeiten | ○ | `ArrangementView` zeichnet Noten, kennt Zeitachse und Bahnen | Auswahl, Ziehen, Raster, Befehle als Patch |
| 9.2 Clips | ○ | — | Clip-Modell, Clip-Bearbeitung |
| 9.3 Instrumentenwechsel in der Bahn | ○ | — | Trennung Track/Part |
| 9.4 Soundeffekte | ◐ | Modul `sounds` erzeugt Effekt-WAVs aus einem Plan | Audio-Bahn, `AudioClip`, Asset-Verwaltung |
| 10.1 Kompositorische Dynamik | ○ | — | Intensität je Abschnitt im Plan, wirkt auf Besetzung und Dichte |
| 10.2 Interpretatorische Dynamik | ◐ | Velocity vorhanden | Controller (CC 7/11), Artikulation als spätere Erweiterung der Note/des Clips |
| 10.3 / 10.4 Audiotechnische Dynamik | ● | `LevelPoint`, `StagePanel.LevelCourse`, `MixDirector` | auf `AutomationLane` verallgemeinern; Limiter im Master |
| 11.1 Räumlicher Mix | ● | `SpatialStemMixer`, `RoomReverb`, `Biquad` | in Blockprozessoren zerlegen |
| 11.2 Faltungshall, 3D, HRTF, bewegte Quellen, Echtzeit | ○ | Rechenkern des Mixers, Zuführ-Thread des `DeckPlayer` | Audio-Kern, FFT, HRTF-Datensatz |
| 11.3 Live-Vorschau | ◐ | `DeckPlayer.swap` (Austausch fertig gemischter Datei) | Echtzeit-Graph mit Parameterwechsel |
| 11.5 Position je Instrument | ◐ | `SoundSourcePlacement` | Schlüssel `partId` statt `trackId` |
| 12 Datenhaltung | ◐ | atomare Stores, JSON mit `version` | verbindliches `composition.json`, Import, Revisionen, Trennung Quelle/Artefakt |
| 13 Austauschbare KI/Renderer/DSP | ◐ | `InstrumentSampleRenderer` wählt Backend | `ChatModel`, `StemRenderer`, `AudioProcessor` als Schnittstellen |
| Stimmen explizit, Doppelsystem | ◐ | zwei heuristische Stimmen in `Score` | `Voice`, `staffLayout`, `voiceId` an der Note |

---

## 4. Architekturentscheidungen

Jede Entscheidung nennt Empfehlung, Begründung und verworfene Alternativen. Die Nummern E1–E10 entsprechen den Fragen 1–10 aus Abschnitt 16 des Berichts.

### E1 Kompositionsmodell

**Empfehlung.** Ein unveränderliches Werkmodell nach 2.2 in einem eigenen Modul, eingeführt in zwei Stufen: zuerst deckungsgleich mit dem heutigen Stand (ein Part und ein Clip je Bahn, ein Abschnitt über das ganze Stück), dann schrittweise genutzt.

**Begründung.** Die Stufe 1 ist eine reine Umformung: jedes heutige Stück lässt sich verlustfrei abbilden, die Oberfläche ändert sich nicht. Komplexität entsteht erst dort, wo eine Funktion sie braucht. Die Unveränderlichkeit folgt dem vorhandenen Stil (`SpatialScene`) und macht Undo, Vorschau und Hintergrund-Rendern einfach.

**Verworfen.** *TSV erweitern:* sechs Parser, keine IDs, keine Verschachtelung. *MIDI-Datei als Modell:* kennt weder Abschnitte noch Clips noch stabile IDs. *MusicXML als Modell:* notationsorientiert, keine Clips, keine Mischung; als späteres Exportformat für die Partitur sinnvoll. *Veränderliches Modell mit Listenern:* erschwert Undo und Nebenläufigkeit.

### E2 Spurkonzept

**Empfehlung.** **Typisierte Bahnen**: eine Bahn ist entweder MIDI oder Audio. Clips einer Bahn überlappen sich nicht. Gleichzeitiges liegt auf einer weiteren Bahn. Keine Unterspuren; eine spätere Gruppierung (Ordner) bleibt möglich.

**Begründung.**
- MIDI- und Audio-Clips werden verschieden gerendert (Synthese gegen Datei), verschieden bearbeitet (Noten gegen Schnitt) und verschieden dargestellt (Partitur gibt es nur für MIDI).
- Die Bedienung bleibt eindeutig: die heutige Bahn mit Kopf (`TrackLanes.Head`) passt unverändert auf eine MIDI-Bahn.
- Die Music-Maker-artige Freiheit entsteht nicht durch gemischte Bahnen, sondern dadurch, dass der **Clip** den Part trägt und nicht die Bahn. Eine MIDI-Bahn kann also nacheinander Klavier, Flöte, Klavier enthalten.
- Das Überlappungsverbot vermeidet die schwierigste Frage (zwei gleichzeitige Clips mit verschiedenen Instrumenten in einer Bahn) vollständig, ohne Ausdrucksmöglichkeiten zu nehmen.

Die Typisierung ist eine Regel des Validators, keine Struktur des Formats: `Clip` hat ohnehin einen Typ. Sollte sich später zeigen, dass gemischte Bahnen gewünscht sind, wird die Regel gelockert, ohne das Format zu ändern.

**Verworfen.** *Gemischte Bahnen (Reaper-Stil):* größte Freiheit, aber Routing, Partitur und Bühne müssten je Clip statt je Bahn entscheiden; für den Einstieg unnötig. *Bahn = Instrument wie heute:* erfüllt A7 nicht. *Unterspuren je Instrument:* macht die Oberfläche tiefer, löst aber nichts, was Clip → Part nicht einfacher löst.

### E3 Instrumentierung: Instanz, Stimme, Kanal

**Empfehlung.** Drei Begriffe statt der fünf aus dem Bericht:

| Begriff | Bedeutung | sichtbar als |
|---|---|---|
| `Track` | Bahn im Editor | Zeile im Arrangement |
| `Part` | Spieler: Instrument + Schallquelle + Kanalzug + Notensystem | Kopf der Bahn, Punkt auf der Bühne, System in der Partitur |
| `Voice` | Stimme innerhalb eines Parts | Halsrichtung und System in der Partitur |

`InstrumentAssignment`, `AudioSource` und `MixerChannel` werden **nicht** getrennt modelliert, sondern sind Eigenschaften des Parts. MIDI-Kanal und -Port bleiben wie heute abgeleitet (`TrackArrangement.channelFor`), nie gespeichert.

**Begründung.** In allen Anwendungsfällen des Berichts gilt 1:1:1 zwischen Klangerzeuger, Schallquelle und Kanalzug. Drei getrennte Objekte mit zwei Verweisen dazwischen würden jede Bedienhandlung („neues Instrument") zu drei Schritten machen. Für die Bedienung bleibt alles wie heute, solange eine Bahn nur ihren Standard-Part benutzt; erst wer einem Clip ein anderes Instrument gibt, sieht einen zweiten Part auf der Bühne. Die Trennung lässt sich später nachholen (ein Part bekommt mehrere Quellen, etwa Stereo-Mikrofonierung, oder mehrere Parts teilen einen Bus), weil der Audio-Kern ohnehin mit Quellen und Bussen arbeitet.

**Offener Punkt.** Soll ein Part, der in zwei Bahnen benutzt wird (dieselbe Flöte in Bahn 2 und Bahn 5), erlaubt sein? Empfehlung: ja; ein Part darf von beliebig vielen Clips benutzt werden, überlappende Noten sind dann einfach Mehrstimmigkeit dieses Instruments.

**Verworfen.** *Volles DAW-Routing (Instrument → Kanal → Bus-Matrix) von Anfang an:* mächtig, aber für Orchesterarbeit mit Stems überdimensioniert und in der Oberfläche teuer.

### E4 KI-Architektur

**Wiederverwendbar aus der heutigen Implementierung:**

| Baustein | Verwendung im Zielbild |
|---|---|
| `KipitzChatClient` (Transport, Streaming, Wiederholung, Abbruch, Cache-Datei) | unverändert hinter der Schnittstelle `ChatModel` |
| Parallele Ausführung mit `CompletionService`, Abbruch, `TrackProgress` | Kern der Generatorstufe; `Progress` zieht in ein neutrales Package (F8) |
| `promptFor`, `rules(role)`, `validateResponse` | werden parametrisch: Taktzahl, Tempo, Tonart, Akkorde, Bereich kommen aus dem `EditPlan` |
| `CompositionNoteNormalizer` + `note-corrections.tsv` | bleibt als Reparaturstufe, mit geänderter Politik (siehe unten) |
| `InstrumentProposalService`, `MixDirector` | unverändert; lesen künftig das Werkmodell statt der TSV |
| Antwort-Caches je Prompt-Hash | werden Herkunftsnachweis, nicht mehr Quelle des Werks |

**Nicht wiederverwendbar:** `assemble` (schreibt TSV aus Caches), `validatePlan` (prüft auf die festen Werte), die Konstante `CHORDS`.

**Gemeinsames Bearbeitungsmodell.** Alle Arten sind derselbe Ablauf aus 2.4 mit verschiedenem Bereich:

| Art | Bereich | Besonderheit |
|---|---|---|
| `COMPOSE` (heute) | leeres Werk, alle Parts, ganze Länge | = `APPEND` auf ein leeres Werk; dadurch gibt es nur *einen* Kompositionsweg |
| `APPEND` | neue Takte, alle oder gewählte Parts | Planer erzeugt neue Abschnitte; Modus „Übergang überarbeiten" erweitert den Bereich um n Takte nach hinten |
| `INSERT` | wie `APPEND`, davor Verschieben des Folgenden | das Verschieben ist eine deterministische Operation der Anwendung, nicht der KI |
| `ADD_TRACK` | neuer Part, ganze Länge oder gewählte Abschnitte | Planer optional (Rolle der neuen Stimme) |
| `REPLACE_REGION` | gewählte Parts × Tickbereich | Kontext davor *und* danach |
| `VARIATE_REGION` | wie `REPLACE_REGION` | bisherige Noten des Bereichs werden als Vorlage mitgegeben |
| `REORCHESTRATE` | gewählte Parts × Bereich, darf Parts anlegen | Planer verteilt vorhandenes Material neu |

**Abschnittsweise Generierung.** Ein Aufruf erzeugt einen Part für höchstens einen Abschnitt (Richtwert 8–16 Takte). Die KI zählt Takte und Schläge relativ zum Abschnittsanfang; die Anwendung rechnet in Ticks um. Das hält die Antworten klein (heutige Grenze 256 Noten), macht Fehler lokal und erlaubt das Wiederholen eines einzelnen Abschnitts. Parts laufen parallel; die Abschnitte eines Parts nacheinander, wobei die letzten Takte des vorigen Abschnitts als Anschluss mitgegeben werden.

**Kontextaufbereitung** (Bericht 8.3) in vier festen Ebenen: Steckbrief; Abschnittstabelle mit Harmonieplan; je Part eine Zusammenfassung (Tonumfang, Dichte je Takt, wie heute `MixDirector.summaries`); volle Noten der betroffenen und benachbarten Takte. Der `ContextBuilder` ist eine reine Funktion des Werks und des Bereichs und liefert zusätzlich einen Digest. Der Digest geht in den Cache-Schlüssel ein.

**Normalizer-Politik.** Formale Reparaturen bleiben (Raster, Dauer, MIDI-Bereich, Velocity, Bereichsgrenzen). Tonhöhen werden nur noch auf den Tonumfang des Instruments begrenzt. Das Ziehen auf Leiter oder Akkord wird eine abschaltbare Option je Auftrag und arbeitet gegen `Key` und Harmonieplan statt gegen Konstanten. Leiterfremde Töne werden protokolliert, nicht stillschweigend verändert.

**Stimmenzuordnung.** Das Antwortformat erhält je Note ein optionales Feld `voice`. Der Auftrag nennt die Stimmen des Parts (bei Klavier: rechte Hand, linke Hand). Fehlt das Feld, greift die Heuristik.

**Verworfen.** *Ein Aufruf liefert das ganze überarbeitete Stück:* nicht begrenzbar, teuer, zerstört Unbeteiligtes. *KI liefert Diff-Operationen auf Noten-IDs:* fehleranfällig; ein Bereich wird zuverlässiger vollständig neu geliefert und von der Anwendung als Ersetzung eingebaut. *Eigener Ablauf je Funktion:* dreifache Pflege von Kontext, Validierung und Vorschau.

### E5 Änderungsmodell

**Empfehlung.** Patch und Änderungsbereich nach 2.3, mit drei voneinander unabhängigen Sicherungen:

1. **Konstruktiv:** Die KI erhält als Ziel nur „Part X, Abschnitt Y" und liefert nur Noten. Der Patch wird von der Anwendung gebaut. Fremde Objekte sind für die KI nicht adressierbar.
2. **Prüfend:** Der Validator lehnt jede Operation ab, deren Ziel oder Zeitbereich außerhalb des `EditScope` liegt.
3. **Nachweisend:** Vor und nach dem Anwenden wird ein Hash über alles außerhalb des Bereichs gebildet; die Hashes müssen gleich sein. Das ist zugleich die Testbedingung für A4, A5, A10.

**Grenzregeln.** Eine Note gehört zu dem Bereich, in dem sie *beginnt*. Noten, die vor dem Bereich beginnen und hineinklingen, bleiben unverändert und werden der KI als „gehalten" mitgeteilt. Neue Noten werden am Bereichsende abgeschnitten, außer im Übergangsmodus.

**Undo und Revisionen.** Sitzungs-Undo ist ein Stapel unveränderlicher `Composition`-Objekte (billig durch geteilte Teilstrukturen). Dauerhafte Revisionen entstehen an definierten Punkten: vor und nach jeder übernommenen KI-Änderung, beim Speichern, auf Wunsch. Jede Revision trägt Urheber (Benutzer | KI), Zeit, Kurzbeschreibung und bei KI-Änderungen den Verweis auf den Lauf. Die erste Revision ist die Originalfassung und wird nie gelöscht.

**Verworfen.** *Nur Prompt-Anweisung:* vom Bericht zu Recht ausgeschlossen. *Operations-Log als einzige Wahrheit (Event Sourcing):* jede Formatänderung müsste alte Logs abspielbar halten; Vollabbilder sind bei dieser Datenmenge einfacher und robuster. *Sperren je Objekt:* löst das Problem nur zur Hälfte, da „freigegeben" kontextabhängig ist.

### E6 Datenhaltung

**Empfehlung.** Projektordner wie heute (`compositions/<stück>/`), JSON über Gson, klare Trennung von Quelle und Artefakt:

```
<stück>/
  project.json              Formatversion, aktuelle Revision
  composition.json          VERBINDLICH: Werkmodell
  mix.json                  Bühne + Automation (Nachfolger von spatial-scene.json)
  assets/                   Quelldaten: Audiodateien für Audio-Clips
  revisions/
    0001.json.gz …          Vollabbilder (composition + mix)
    history.jsonl           eine Zeile je Revision
  ai/runs/<lauf>/           Auftrag, Kontext, Plan, Antworten je Part (Herkunft, nicht Quelle)
  render/                   ABGELEITET, jederzeit löschbar
    midi/  stems/  mix/  manifest.json
  arrangement.tsv           Export (übergangsweise bei jedem Speichern)
  arrangement.mid           Export
  (Altdateien bleiben unangetastet liegen)
```

**Begründung.** JSON ist bereits das Format aller Stores, lesbar, mit Versionsfeld migrierbar, ohne neue Abhängigkeit. Vollabbilder als Revision sind einfach und klein: ein zehnminütiges Stück mit 30 Parts liegt in der Größenordnung von wenigen Megabyte unkomprimiert. `mix.json` bleibt ein eigenes Dokument, damit eine Pegeländerung das Werk nicht berührt und keine Noten-Caches entwertet (A12).

**KI-Caches.** Sie sind künftig Protokoll eines Laufs. Das Werk wird nie mehr aus ihnen aufgebaut. Ein Cache-Treffer entsteht nur, wenn derselbe Auftrag mit demselben Kontext-Digest wiederholt wird; ändert sich relevanter Kontext, ändert sich der Digest und damit der Schlüssel. Eine gesonderte Invalidierungslogik ist dadurch nicht nötig.

**Verworfen.** *SQLite:* neue Abhängigkeit, schlecht vergleichbar, kein Gewinn bei dieser Datenmenge. *Ein ZIP als Projektdatei:* erschwert inkrementelles Schreiben; als Weitergabeformat („Stück exportieren") später sinnvoll. *TSV als Primärformat:* siehe E1.

### E7 Render-Pipeline

**Empfehlung.**

1. **MIDI direkt aus dem Modell**, je Part, ohne Umweg über TSV und ohne Kind-JVM. Die Schreiblogik aus `MultiTrackMusicRenderer.writeMidi` wird als Bibliotheksklasse herausgelöst; das CLI bleibt als dünne Hülle.
2. **`StemRenderer` als Schnittstelle** mit den vier vorhandenen Backends. Die Auswahl bleibt, wo sie ist (`InstrumentSampleRenderer`), wird aber über die Schnittstelle erweiterbar.
3. **Inhaltsadressierter Stem-Cache.** Schlüssel = Hash aus Noten des Parts, Programm, Soundbank (ID, Größe, Änderungszeit), Engine, `TimeMap`, Abtastrate. Ein Part wird nur neu gerendert, wenn sich sein Schlüssel ändert. `stems.tsv` geht im `manifest.json` auf.
4. **Eine Grenze statt vier.** Ein `RenderLimits`-Objekt (maximale Dauer, daraus abgeleitete Prozesslaufzeit) wird an MIDI-Schreiber, Gervill, Prozessstarter und Mixer durchgereicht (F3, F4).
5. **Audio-Clips** werden nicht gerendert, sondern vom Mixer an ihrer Zeitposition gelesen.
6. **Mischen** über den Graphen des Audio-Kerns, blockweise in eine Datei gestreamt.

**Bewusst zurückgestellt:** abschnittsweises Rendern eines Parts. Es erfordert Überlappung für Ausklang und Hallfahne und bringt erst bei sehr langen Stücken Gewinn. Zuerst messen, ob der Cache je Part genügt.

**Verworfen.** *Eine Echtzeit-Synthese für alle Formate im Prozess:* würde FluidSynth/sfizz ausschließen oder native Bindungen erzwingen; der Bericht will native Abhängigkeiten optional halten.

### E8 Audio-Kern

**Empfehlung.** Ein Modul `audio-core` ohne Fremdabhängigkeit, das durch **Herauslösen** entsteht:

| Vorhanden | wird im Kern zu |
|---|---|
| `Biquad`, `RoomReverb` | Prozessoren mit Zustand über Blockgrenzen |
| `SpatialStemMixer.place` (Pegelrampe über 64 Samples, Breite, Distanz, Shelf, Equal-Power-Panorama) | `Strip` und `StereoPanner` |
| `SpatialStemMixer.reflect` | `EarlyReflections` im `RoomBus` |
| `read`, `decode`, `write`, `resample` | `WavSource` (streamend), `WavSink` (streamend), `Resampler` |
| Zuführ-Thread des `DeckPlayer` | `LineSink`: derselbe Thread zieht Blöcke aus dem Graphen statt aus einem Byte-Array |
| `DeckPlayer.measure` | Pegelmessung als Abgriff im Graphen |

**Abnahme des Herauslösens:** Der Block-Mixer liefert für die vorhandenen Testfälle dasselbe Ergebnis wie `SpatialStemMixer` (Toleranz im Bereich der Gleitkomma-Rundung) und ist unabhängig von der Blockgröße. Erst dann wird der alte Mixer ersetzt.

**Gesamtpegel (F9).** Die nachträgliche Normalisierung auf den Spitzenwert ist in Echtzeit nicht möglich. Empfehlung: im Graphen feste Pegelstruktur plus Limiter im Master; die Normalisierung bleibt als Option *nur* beim Datei-Export. Folge: die Vorschau klingt wie der Export ohne Normalisierung. Das ändert das heutige Klangergebnis leicht und braucht eine Bestätigung (Abschnitt 8).

**Erweiterungen in dieser Reihenfolge:** Echtzeit-Wiedergabe mit Parameterwechsel (A13) → Faltungshall (partitionierte Faltung) → Höhe als dritte Koordinate und bewegte Quellen (Positions-Automation ist bereits durch `AutomationLane` abgedeckt) → HRTF-Panner.

**FFT.** Für Faltung und HRTF genügt eine Radix-2-FFT. Empfehlung: eigene, kleine Implementierung hinter einer Schnittstelle, damit der Kern abhängigkeitsfrei bleibt; JTransforms als Ausweichlösung, falls die Messung es verlangt.

**Externe Bibliotheken** (Kenntnisstand, vor Entscheidung zu verifizieren):

| Bibliothek | Einschätzung |
|---|---|
| TarsosDSP | Schwerpunkt Analyse (Tonhöhe, Onsets), eigene Verarbeitungskette. Lizenz nach meinem Stand GPL: für einen gemeinsam genutzten Kern voraussichtlich ungeeignet. **Nicht verwenden.** |
| JSyn | Vollständige Echtzeit-Synthese-Engine mit eigenem Zeit- und Thread-Modell. Würde neben dem eigenen Graphen eine zweite Engine einführen. **Nicht als Kern**; allenfalls später als Klangerzeuger hinter `StemRenderer`. |
| JTransforms | Reine FFT, ausgereift, freizügige Lizenz, zieht aber Hilfsbibliotheken nach. **Optionale Ausweichlösung** für die FFT. |
| JDSP | Filterentwurf und Signalanalyse für Offline-Auswertung, umfangreiche Abhängigkeiten. **Nicht verwenden**; die benötigten Filter sind vorhanden. |
| RaphiMC AudioMixer | Mischt PCM-Quellen auf eine Ausgabeleitung. Deckt den einfachsten Teil ab, den `DeckPlayer` bereits löst; kein Raum, kein Hall. **Kein Gewinn.** |

**AskAI (Annahme, nicht prüfbar).** Vor Beginn des Herauslösens ist eine zeitlich begrenzte Bestandsaufnahme nötig: Welche DSP-Bausteine gibt es, arbeiten sie blockweise mit Float, sind sie frei von Fremdabhängigkeiten, wem gehören sie? Die Schnittstelle `AudioProcessor` ist so schmal, dass vorhandene AskAI-Bausteine dahinter eingehängt werden können. Dafür muss das Repository dem Projekt zugänglich gemacht werden.

**Sprachniveau des Kerns.** Wenn AskAI oder 3d4j unter einem älteren Java laufen, muss `audio-core` für das niedrigste gemeinsame Niveau kompiliert werden. Das ist zu klären (Abschnitt 8).

**Verworfen.** *Neuentwicklung auf der grünen Wiese:* verwirft getesteten, deterministischen Code. *Native Engine (JNI):* widerspricht der Anforderung, native Abhängigkeiten zu vermeiden.

### E9 Migrationsstrategie

Siehe Abschnitt 5. Kernaussage: **Würgefeigen-Muster.** Das neue Modell wird zuerst *hinter* die bestehende Pipeline gesetzt und erzeugt die TSV als Sicht. Die Anwendung bleibt in jedem Schritt lauffähig; jeder Schritt ist einzeln test- und auslieferbar.

### E10 Entwicklungsreihenfolge

Siehe Abschnitt 6. Zwingend sind nur vier Abhängigkeiten:

1. Werkmodell vor allem, was Werke verändert.
2. Parametrische Zeit vor Verlängerung und vor A1.
3. Änderungskern vor Editor und vor KI-Bearbeitung (sonst entstehen zwei Änderungswege).
4. Trennung Track/Part vor Instrumentenwechsel und Audio-Clips.

Nicht zwingend, obwohl es so wirkt: Der Audio-Kern hängt vom Werkmodell nur über den Schlüssel `partId` ab und kann sofort beginnen. Clips sind für Notenbearbeitung und für `REPLACE_REGION` nicht nötig. Der Planer ist für `ADD_TRACK` und `REPLACE_REGION` nicht nötig.

---

## 5. Migrationskonzept

### 5.1 Grundsätze

- Altdateien werden **gelesen, nie verändert, nie gelöscht**.
- Die Umstellung eines Stücks geschieht beim ersten Öffnen, ohne Rückfrage, und ist wiederholbar (Löschen von `composition.json` stellt den Altzustand her).
- Vorhandene gerenderte Stems werden übernommen; ein altes Stück darf beim Öffnen nicht neu rendern müssen.

### 5.2 Import eines Altbestands

| Altdatei | Ziel |
|---|---|
| `arrangement.tsv` | `tempo` → `timeMap`; je `track`-Zeile ein `Part` und eine `Track` (ID = bisherige `trackId`, damit Bühne und Stems passen); alle Noten in einen `MidiClip` über die volle Länge |
| `tracks.json` | Rolle, Instrumentname, Programm, `libraryId` des Parts |
| `composition-plan.json` | Titel, Stimmung, Akkorde → `meta` und ein `Section` mit Harmonieplan |
| `song.properties` | Name, Beschreibung, `plannedFrom` → `meta` |
| `spatial-scene.json` | → `mix.json`; `track` wird `part`; `dynamics` wird eine `AutomationLane` für den Pegel |
| `stems.tsv`, `*-sampled.wav`, `*.wav` | Eintrag im `manifest.json` mit Kennzeichen „übernommen", gilt bis zur ersten Änderung des Parts |
| `<track>[-hash].json`, `instrument-proposal.json`, `mix-proposal.json`, `earlier/` | bleiben liegen; werden als Lauf „Altbestand" referenziert |
| Stimmen | Heuristik aus `Score` einmal ausführen, Ergebnis als `voiceId` speichern |

Fehlende Dateien sind zulässig (ein Ordner nur mit `arrangement.tsv` ist ein gültiges Stück, wie heute in `StageMixService.tracks` vorgesehen). Fest angenommen werden dann 4/4 und C-Dur, weil der Altbestand nichts anderes kennen kann.

### 5.3 Phasen

| Phase | Zustand der Anwendung | Wahrheit |
|---|---|---|
| M0 Sicherheitsnetz | unverändert | TSV |
| M1 Modell hinter der Pipeline | unverändert sichtbar; lädt `Composition`, schreibt TSV als Sicht | `composition.json` |
| M2 Parametrische Zeit | neue Vorgaben wählbar; Altpipeline liest weiter TSV, jetzt ohne feste Grenzen | `composition.json` |
| M3 Änderungskern | „Spur neu komponieren" läuft als Patch mit Vorschau | `composition.json` + Revisionen |
| M4 Ablösung | Renderer, Partitur, Bahnen, MixDirector lesen das Modell direkt; TSV nur noch Export | `composition.json` |

In M1 bis M3 wird die TSV bei jedem Speichern aus dem Modell erzeugt. Dadurch laufen Renderer, Partitur und Mixer unverändert weiter, und der Umstieg der Leser (M4) kann einzeln erfolgen.

### 5.4 Formatversionen

`project.json` trägt `formatVersion`. Ein Leser lehnt höhere Versionen mit klarer Meldung ab und migriert niedrigere in einer Kette von Einzelschritten (v1 → v2 → …), jeder mit eigenem Test und eigener Beispieldatei unter `src/test/resources`.

### 5.5 Verträglichkeit nach außen

`arrangement.tsv` bleibt im bisherigen Format als Export bestehen, solange das Stück darin ausdrückbar ist (ein Tempo, Bahnen mit einem Instrument). Für alles Weitere ist `arrangement.mid` das Austauschformat. Das CLI `music-renderer.jar` und die Beispiele unter `music/examples` bleiben funktionsfähig.

---

## 6. Umsetzungsplan

### 6.1 Arbeitspakete

Größe: S ≈ Tage, M ≈ ein bis zwei Wochen, L ≈ mehrere Wochen. Das sind Einordnungen, keine Zusagen.

**Fundament (nacheinander)**

| AP | Inhalt | Größe | braucht | Ergebnis |
|---|---|---|---|---|
| F0 | Sicherheitsnetz: Golden-Tests für TSV → MIDI, Mix-Hash, Partitur; **ein** `ArrangementFile`-Leser ersetzt die sechs Parser; `Progress` in neutrales Package | S | — | keine Verhaltensänderung, F2 und F8 erledigt |
| F1 | Modul `composition`: Modell Stufe 1, `TimeMap`, IDs; Import nach 5.2; TSV-/MIDI-Export; Projektordner und `ProjectSession`; `stage` entkoppeln (F7) | M | F0 | jedes alte Stück lädt, TSV-Rundreise identisch |
| F2 | Parametrische Zeit und Tonart: `CompositionSpec` in Oberfläche und Prompts; feste Werte aus Composer, Normalizer, MIDI-Schreiber, MixDirector entfernen; `RenderLimits`; `Key.spell`, Vorzeichnung und Taktart in der Partitur; abschnittsweise Generierung | M–L | F1 | A1, A14 |
| F3 | Änderungskern: Patch, `EditScope`, Validator, `Proposal`, Revisionen, Undo; „Spur neu komponieren" als erster Nutzer mit Vorschau | M | F1 | A11; Grundlage für A4, A5, A9, A10 |

**Parallel nach F3** (Audio-Kern bereits nach F0)

| AP | Inhalt | Größe | braucht | Ergebnis |
|---|---|---|---|---|
| R1 | MIDI aus dem Modell, `StemRenderer`-Schnittstelle, Stem-Cache, `manifest.json` | M | F1 | nur geänderte Parts werden gerendert |
| C1 | `ContextBuilder`, `ADD_TRACK` mit Kontext der anderen Parts | M | F3 | A4 |
| C2 | `REPLACE_REGION`, `VARIATE_REGION` mit Bereichsauswahl im Arrangement | M | F3, C1 | A5, A6 |
| C3 | Planer, `Section`-Modell in der Oberfläche, `APPEND` mit Presets, Schlussart, Übergangsmodus | L | F2, C1 | A2, A3 |
| C4 | `INSERT`, `REORCHESTRATE`, Intensitätskurve je Abschnitt | M | C3, B2 | Dramaturgie nach 10.4 |
| B1 | Noteneditor in `ArrangementView`: Auswahl, Ziehen, Länge, Einfügen, Löschen, Raster, Kopieren; alles als Patch | L | F3 | A9, A10 |
| B2 | Trennung Track/Part in der Oberfläche, Clips (Verschieben, Teilen, Kopieren), Instrumentenwechsel je Clip, Bühne je Part | L | F3, R1 | A7 |
| B3 | Audio-Bahnen, `AudioClip`, Assets, Anbindung des Moduls `sounds` | M | B2, D1 | A8 |
| S1 | Explizite Stimmen in Modell, KI-Format und Partitur; Doppelsystem; mehr als zwei Stimmen | M | F2 | Partitur liest Stimmen statt zu raten |
| D0 | Bestandsaufnahme AskAI-DSP und Klärung Sprachniveau | S | Zugang | Entscheidungsgrundlage für D1 |
| D1 | Modul `audio-core`: Blockprozessoren aus dem Mixer herauslösen, streamender Offline-Mix, Gleichheit zum Altmixer | M | F0, D0 | kein Ganzpuffer mehr; `MAX_SECONDS` entfällt |
| D2 | Echtzeit-Graph, `LineSink`, Parameterwechsel mit Glättung, Bühne live | L | D1 | A13 |
| D3 | `AutomationLane` allgemein (Pegel, Position, Hall), Limiter im Master | M | D1 | A12 erweitert, bewegte Quellen vorbereitet |
| D4 | Faltungshall, Höhe, HRTF | L | D2 | 11.2 des Berichts |

### 6.2 Abhängigkeiten

```
F0 ─► F1 ─► F2 ─────────────► C3 ─► C4
      │      └─► S1                 ▲
      ├─► F3 ─► C1 ─► C2            │
      │    ├─► B1                   │
      │    └─► B2 ──────────────────┘
      │         └─► B3 ◄── D1
      └─► R1 ─► (B2)
F0 ─► D0 ─► D1 ─► D2 ─► D4
              └─► D3
```

### 6.3 Empfohlene erste Lieferungen

1. **F0 + F1** als erste Lieferung: unsichtbar, aber danach ist jedes Stück ein Werk mit IDs.
2. **F2**: das erste sichtbare Ergebnis (24 Takte, 96 BPM, d-Moll, korrekte Vorzeichnung).
3. **F3 + C1**: Trompete hinzukomponieren mit Vorschau und Verwerfen. Damit ist das Kernprinzip des Berichts („die KI ändert gezielt Teile") erstmals vollständig erlebbar.
4. Danach zwei bis drei Stränge parallel: B1 (Editor), C2/C3 (KI), D1 (Audio-Kern).

### 6.4 Risiken

| Risiko | Wirkung | Gegenmaßnahme |
|---|---|---|
| Qualität der KI bei langen Formen und bei Anschlüssen | A2, A3 musikalisch unbefriedigend | abschnittsweise Generierung, Planer vor Generatoren, Übergangsmodus; früh mit echten Stücken hören |
| Antwortgröße und Laufzeit bei vielen Parts × Abschnitten | Kosten, Wartezeit | Parallelität je Part vorhanden; Fortschritt je Part und Abschnitt; Wiederholen einzelner Abschnitte |
| Klangänderung durch Ablösung der Normalisierung (F9) | Bestandsstücke klingen leiser oder lauter | Normalisierung beim Export behalten; Vergleichshören vor Umschalten |
| Echtzeit unter Java (GC-Pausen, Puffergröße) | Knackser in A13 | keine Allokation im Audio-Thread, vorab belegte Blöcke, Messung auf Zielhardware; Rückfall auf heutigen Dateitausch bleibt erhalten |
| `AudioStudioFrame` wächst weiter, während migriert wird | Zerlegung wird teurer | Regel: neue Ablauflogik nur noch in Diensten der Anwendungsschicht |
| AskAI-Bestand passt nicht zur Blockschnittstelle | Doppelarbeit | D0 vor D1, zeitlich begrenzt |
| Gervill bei sehr langen Stücken (Speicher, Laufzeit im Kindprozess) | A14 | nach F2 messen; falls nötig abschnittsweises Rendern nachziehen |

---

## 7. Abnahmekonzept

### 7.1 Testmittel

- **`FakeChatModel`**: liefert vorbereitete Antworten je Auftrag, einschließlich absichtlich falscher (Noten außerhalb des Bereichs, fremder Part, defektes JSON). Macht alle KI-Abläufe ohne Netz und deterministisch prüfbar.
- **Golden-Dateien** unter `src/test/resources`: Alt-Stücke in jedem je ausgelieferten Format, erwartete MIDI-Dateien, erwartete Partiturstruktur.
- **Unveränderlichkeitsprüfung**: Hilfsfunktion `hashOutside(composition, scope)`; in jedem Änderungstest vor und nach dem Patch gleich.
- **Audiovergleich**: Abweichung zweier Mischungen als RMS-Differenz mit Toleranz; Stille- und Übersteuerungsprüfung.
- **Kopfloser Rauchtest**: `--smoke-test` wird je Lieferung um den neuen Ablauf erweitert (in CI unter Java 21 und über den Java-8-Start).
- **Hörprüfung auf Zielhardware** (Windows) für alles, was Wiedergabe betrifft; in CI nicht prüfbar.

### 7.2 Abnahme je Arbeitspaket

| AP | Automatisiert | Fachlich |
|---|---|---|
| F0 | Golden: Demo-TSV → identische MIDI-Bytes vor und nach dem Umbau; identischer Mix-Hash; `ScoreTest` unverändert grün | Demo klingt und sieht aus wie zuvor |
| F1 | Rundreise TSV → Modell → TSV zeilengleich für alle Beispiele; Import mit fehlenden Dateien; Stems werden nicht neu gerendert (Manifest „übernommen") | A15: drei echte Alt-Stücke öffnen, vergleichen |
| F2 | Parametrisierte Tests: Taktzahl, Tempo, Tonart, Taktart → MIDI-Länge, Tempo-/Taktart-/Tonart-Meta-Ereignisse; `Key.spell` für alle 24 Tonarten; Normalizer verändert keine korrekten Noten in d-Moll; 12-Minuten-MIDI durchläuft alle Renderer ohne Grenzfehler | A1, A14 |
| F3 | Jede Operation innerhalb und außerhalb des Bereichs; `hashOutside` konstant; Undo/Redo über 100 zufällige Patches ergibt Ausgangszustand; Revision laden = gespeicherter Zustand | A11 |
| R1 | Änderung an einem Part rendert genau einen Stem; Änderung an `mix.json` rendert keinen | A12 |
| C1 | Kontext ist für gleiches Werk und gleichen Bereich bytegleich; `ADD_TRACK` verändert keinen bestehenden Part; Antwort mit fremdem Part wird abgelehnt | A4 |
| C2 | Bereich Takte 5–8 eines Parts: Noten außerhalb identisch; gehaltene Noten über die Grenze bleiben; Antwort mit Noten in Takt 9 wird beschnitten und protokolliert | A5, A6 |
| C3 | Plan deckt genau die neuen Takte ab; Modus „Original schützen": Hash der Takte 1–8 gleich; Modus „Übergang": nur die letzten n Takte dürfen abweichen | A2, A3 (Hörprüfung) |
| B1 | Jede Editorhandlung erzeugt genau einen Patch; nach Speichern und Laden identisch; nachfolgende KI-Änderung an anderem Part lässt die Handänderung unberührt | A9, A10 |
| B2 | Zwei Clips mit verschiedenen Parts auf einer Bahn → zwei Stems, zwei Bühnenplätze, zwei Systeme; Überlappung wird abgelehnt | A7 |
| B3 | Audio-Clip bei Takt 20 beginnt im Mix beim berechneten Sample (± 1 Block) | A8 |
| S1 | KI-Antwort mit `voice` → Partitur zeigt diese Zuordnung; ohne `voice` → Heuristik; Klavier-Part ergibt Doppelsystem | Notenbild zweier gleichzeitig einsetzender Linien |
| D1 | Block-Mixer gegen Altmixer innerhalb Toleranz; gleiches Ergebnis für Blockgrößen 64, 512, 4096; Speicherbedarf unabhängig von der Stücklänge | Vergleichshören |
| D2 | Parameterwechsel ohne Sprung im Signal (Differenz benachbarter Samples begrenzt); keine Allokation im Audio-Thread (Messung) | A13 auf Zielhardware |
| D3 | Automation bei Tempowechsel bleibt am Takt; Limiter hält Spitzenwert | A12 |
| D4 | Faltung mit Dirac-Impuls = Eingang; HRTF links/rechts symmetrisch | Hörprüfung mit Kopfhörer |

### 7.3 Zuordnung der Abnahmeszenarien

| Szenario | erfüllt nach | Bemerkung |
|---|---|---|
| A1 | F2 | |
| A2, A3 | C3 | automatisiert prüfbar ist der Schutz des Originals; „musikalisch zusammenhängend" ist Hörprüfung |
| A4 | C1 | |
| A5, A6 | C2 | |
| A7 | B2 | |
| A8 | B3 | |
| A9, A10 | B1 | A10 wird technisch bereits durch F3 sichergestellt |
| A11 | F3 | |
| A12 | R1 | heute bereits für Pegelkurven erfüllt; R1 sichert es gegen Rückschritt |
| A13 | D2 | |
| A14 | F2 | |
| A15 | F1 | |
| offen aus Rückmeldung | — | Windows-Doppelklick des Launchers: Hörprüfungs-/Handtest auf Zielsystem |

---

## 8. Offene Punkte für den Auftraggeber

| Nr. | Frage | Empfehlung | Wirkt auf |
|---|---|---|---|
| O1 | Ein Begriff „Part" statt getrennter Instrumentinstanz, Schallquelle und Kanalzug (E3)? | ja | F1, B2 |
| O2 | Typisierte Bahnen ohne überlappende Clips (E2)? | ja | B2, B3 |
| O3 | Normalisierung des Gesamtpegels nur noch beim Export, sonst feste Pegelstruktur mit Limiter (F9/E8)? | ja, nach Vergleichshören | D1, D2 |
| O4 | Zugang zum AskAI-Repository für D0, und welches Java-Niveau brauchen AskAI und 3d4j für `audio-core`? | Zugang gewähren; Niveau = niedrigstes gemeinsames | D0, D1 |

Bis zur Antwort gelten die Empfehlungen als Arbeitsannahme; keines der Fundament-Pakete F0 bis F3 hängt von O3 oder O4 ab.
