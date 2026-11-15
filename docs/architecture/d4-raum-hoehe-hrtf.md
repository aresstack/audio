# D4: Faltungshall, Höhe und HRTF

Umsetzung des Arbeitspakets D4 der [Architekturprüfung](2026-11-architekturpruefung.md) (6.1 D4, 2.6, E8, 4b). Stand 2026-11-15, aufgebaut auf D2 (#22, darunter D1b #9) und D3 (#19).

## Was neu ist

| Baustein | Ort | Inhalt |
|---|---|---|
| FFT | `audio-core` `fft` | Schnittstelle `FourierTransform` unverändert aus AskAI (MIT), eigene `RadixTwoFourierTransform`: Tabellen im Konstruktor, keine Allokation, kein Commons Math, kein JTransforms (Entscheidung 4b.2). |
| Faltung | `audio-core` `conv` | `PartitionedConvolver`: Kopf direkt gefaltet (keine Latenz), dahinter Segmente mit wachsender Partitionsgröße (64, 256, 1024, 4096), je Segment Overlap-Save mit Frequenzbereichs-Verzögerungskette. Links und rechts in einer komplexen FFT (4b.2, Punkt 3). 2,5 s Antwort: rund 19 komplexe Produkte je Sample statt 1 700 bei gleichförmigen 64er-Partitionen. |
| Raum | `audio-core` `dsp` | `RoomBus` als austauschbarer Raum (2.6): `RoomReverb` wie bisher, `ConvolutionReverb` neu. `MixGraph` und `LiveGraph` nehmen jeden `RoomBus`. |
| Impulsantworten | `conv.ImpulseResponse`, `conv.SyntheticRoom`, `wav.ImpulseResponseFile` | Antwort aus den Raumzahlen (Rauschen, 60 dB in der Nachhallzeit, Höhen klingen schneller ab, fester Seed) oder aus einer WAV-Datei. Das Studio gleicht die synthetische Antwort an die Energie des algorithmischen Raums an. |
| HRTF | `audio-core` `spatial` | `HrirSet` (Raster 5° × 10°, minimalphasige Filter plus Laufzeit je Ohr, bilinear), `SphericalHeadModel` (Brown & Duda 1998, ohne Datensatz), `HrirMeasurements` (gemessene Sätze, Leser für MIT KEMAR), `BinauralPanner` (gleitende Richtung: Laufzeit gleitet, Filter blenden über). |
| Folgende Quelle | `audio-core` `mix.SpatialChannel` | Pegel, Hall-Anteil, Distanz-Shelf, frühe Reflexionen (Verzögerung zwischen Samples, gleitend) und Richtung folgen einer `SpatialMotion` im 64-Frame-Raster. Ohne Bewegung bitgleich zu `StemChannel`, nur mit Pegelbewegung bitgleich zum bewegten Kanal aus D3. |
| Live | `audio-core` `live` | `LiveChannel` optional binaural, `ChannelParameters` mit Richtung. |
| Höhe | `composition`, `studio` | `SoundSourcePlacement.height` (0 = Ohrhöhe … 1 = 6 m darüber), `spatial-scene.json` schreibt `height` nur wenn > 0, Automationsspur `height` (0 … 1). `AcousticRoom.heightMetres`. Auf Kopfhörern antworten zusätzlich Boden und Decke. |
| Studio | `studio` `mix` | `StageSound` (Lautsprecher/Kopfhörer, algorithmisch/Faltung), `SpatialStemMixer.withSound`, `LiveStage.of(…, sound)`, `StageMixService.setSound`. Standard bleibt `StageSound.LOUDSPEAKERS`: Lautsprecher und algorithmischer Raum, klanggleich zu vorher (Golden-Tests unverändert grün). |

## Abnahme (7.2)

| Kriterium | Test |
|---|---|
| Faltung mit Dirac-Impuls = Eingang | `PartitionedConvolverTest.aDiracGivesTheInputBack`, dazu verzögerte Diracs und Vergleich mit direkter Faltung |
| HRTF links/rechts symmetrisch | `BinauralPannerTest.leftAndRightAreMirrorImages`, `LiveBinauralTest`, im Studio `SpatialStemMixerSpaceTest.onHeadphonesAMirroredStageGivesMirroredEars` |
| Faltungshall = algorithmischer Raum mit dessen eigener Antwort | `ConvolutionReverbTest` (der algorithmische Raum ist linear und zeitinvariant) |
| Blockgrößenunabhängigkeit | Faltung, `SpatialChannel` und Studio-Mix mit Höhe, Kopfhörer und Faltung je 512 gegen 37 Frames, bitgleich |
| Audio-Thread-Regeln | Allokationstests für FFT, Faltung, `BinauralPanner`, `SpatialChannel`, binauralen `LiveChannel` |
| Bisheriger Klang | Golden-Tests unverändert; Höhe 0 = vorher (`heightZeroIsTheStageAsBefore`) |
| Hörprüfung mit Kopfhörer | **offen**, braucht Menschen (siehe unten) |

## Kosten

Gemessen mit [`d4-messungen/D4Bench.java`](d4-messungen/D4Bench.java) im Cloud-Container (Xeon 2,1 GHz, JDK 21, ein Kern):

| Fall | Mittel je Block | Anteil am Budget | Spitze |
|---|---|---|---|
| Faltung 2,5 s, B = 128 | 70 µs | 2,4 % | 1,8 ms |
| Faltung 2,5 s, B = 512 | 291 µs | 2,5 % | 1,7 ms |
| Faltung 4,5 s, B = 512 | 405 µs | 3,5 % | 4,4 ms |
| HRTF, 16 stehende Quellen, B = 512 | 660 µs | 5,7 % | |
| HRTF, 16 bewegte Quellen, B = 512 | 994 µs | 8,6 % | |

Die Spitzen kommen aus den großen Partitionen, die in dem Block rechnen, in dem sie voll werden (alle 4096 Samples). Bei B = 512 (Studio) bleiben sie weit im Budget von 11,6 ms. Bei sehr kleinen Blöcken (B = 128, Budget 2,9 ms) ist eine kleinere größte Partition ratsam (`new ConvolutionReverb(ir, 64, 1024)`); das Ergebnis hängt dann nicht mehr von der Blockgröße ab, wohl aber von der Partitionierung (Rundung).

## HRTF-Datensätze und Lizenzen

Geprüft am 2026-11-15. **Keiner ist eingebaut**; der Kern bringt das Kugelkopfmodell mit (veröffentlichter Algorithmus, kein Datensatz, keine Lizenzfrage).

| Satz | Lizenz | Format | Anbindung |
|---|---|---|---|
| MIT KEMAR (Gardner & Martin, MIT Media Lab 1994) | „provided free with no restrictions on use, provided the authors are cited when the data is used in any research or commercial application“ ([KEMAR.html](https://sound.media.mit.edu/resources/KEMAR.html)); Datensatz-Freigabe, nicht die MIT-Softwarelizenz | `.zip`: WAV 16 bit, `.tar.Z`: `.dat` 16 bit big-endian; 44,1 kHz; kompakt 128 Samples stereo (368 Richtungen, eine Seite), voll 512 Samples je Ohr | `HrirMeasurements.readMitKemar(ordner, taps)` liest beides; gegen das Originalarchiv `compact.zip` geprüft (CI `hrtf-download.yml`: Prüfsumme, 368 Dateien, Raster −40° bis 90°, Kanalzuordnung: von rechts ist das rechte Ohr lauter und früher, links gespiegelt). Der erste Lauf gegen das Original zeigte, dass das Zip WAV statt `.dat` enthält; der Leser kann seitdem beides |
| SADIE II (University of York 2018) | Apache License 2.0, Namensnennung ([Datenbank](https://www.york.ac.uk/sadie-project/database.html)) | WAV und SOFA, 44,1/48/96 kHz; Azimut gegen den Uhrzeigersinn | WAV mit `WavFileSource` lesen und `HrirMeasurements.toGrid` übergeben (Azimut umrechnen) |

**PO-Entscheidung (PR #33):** MIT KEMAR als optionaler, ausdrücklich vom Benutzer ausgelöster Download; nicht in `audio-core` eingebettet, keine Laufzeitabhängigkeit, kein ungefragter Netzaufruf. Umgesetzt im Studio als `studio.hrtf`:

- `HrtfDataset.MIT_KEMAR`: Originalquelle `https://sound.media.mit.edu/resources/KEMAR/compact.zip`, Version (kompakter Satz 1994), Nutzungsbedingungen im Wortlaut, Zitat (Gardner & Martin, Perceptual Computing Technical Report #280, 1994), festgeschriebene SHA-256 des Archivs.
- `HrtfLibrary.download(...)` lädt nur auf Aufruf, verwirft jedes Archiv mit anderer Prüfsumme oder Übergröße, entpackt nur die `.dat`-Antworten (flach, ohne Pfade aus dem Archiv) nach `~/.aresstack/audio/hrtf/mit-kemar-compact/` und legt `QUELLE.txt` mit Quelle, Version, Prüfsumme, Bedingungen und Zitat daneben. Danach offline nutzbar (`load`).
- `HrtfLibrary.onStage(sound, set)` setzt den geladenen Satz als Kopf ein; fehlt er oder ist er unlesbar, bleibt das Kugelkopfmodell.
- CI `hrtf-download.yml` prüft bei Änderungen, dass die Originalquelle noch genau das festgeschriebene Archiv liefert, und installiert es einmal echt.
- Drittanbieterhinweis: `THIRD-PARTY-NOTICES.md`.

## Grenzen und offene Punkte

- **Hörprüfung** mit Kopfhörer: Richtung links/rechts, vorne/hinten, Höhe, Bewegung um den Kopf, Faltungsraum gegen algorithmischen Raum. Das Kugelkopfmodell ortet links/rechts gut, Höhe und vorne/hinten nur grob; ein gemessener Satz ist dort besser.
- Auf Kopfhörern wird jede Spur als Punktquelle gehört (Summe beider Seiten); die Breite wirkt dort nicht. Die frühen Reflexionen werden auch auf Kopfhörern mit Pegeln platziert, nicht mit HRTF (Kosten).
- `MixChannel` im Mix-Dokument hat keine Höhe; eine Höhe gibt es dort nur über die Automationsspur `height`. Die stehende Höhe liegt in `spatial-scene.json`.
- Die Wahl Kopfhörer/Faltung wird noch nicht gespeichert; das ist eine Einstellung der Oberfläche (Hooks unten).
- Wechsel von Lautsprecher/Kopfhörer oder Raumart während der Wiedergabe bedeutet eine neue `LiveStage`, wie ein Raumwechsel.

## Hooks für die Oberfläche (nicht Teil dieses Pakets)

1. Bühne: Höhe je Spur setzen (`SoundSourcePlacement.atHeight`), z. B. Mausrad oder Schieber im StagePanel, Live über `LiveStage.move`.
2. Umschalter „Kopfhörer (3D)“ und „Faltungshall“: `StageSound` bauen, an `StageMixService.setSound` und `LiveStage.of(…, sound)` geben; Wahl in den Studio-Einstellungen merken.
3. Eigene Impulsantwort laden: `ImpulseResponseFile.read(path)` → `StageSound.withImpulseResponse`.
4. HRTF-Satz laden: Knopf „MIT KEMAR laden“ mit Hinweis auf Quelle, Bedingungen und Zitat vor dem Download → `new HrtfLibrary(HrtfLibrary.standardRoot()).download(HrtfDataset.MIT_KEMAR, routes::proxyFor, progress)` im Hintergrund; beim Start und nach dem Download `library.onStage(sound, HrtfDataset.MIT_KEMAR)`; Quelle (`HrirSet.source()`) im Kopfhörermodus anzeigen. Wahlweise weiterhin eigener Ordner über `HrirMeasurements.readMitKemar(ordner, 128)`.
5. Automationsspur `height` im Spureditor anbieten (wie x/depth/reverb aus D3).
