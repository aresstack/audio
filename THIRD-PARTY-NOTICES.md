# Drittanbieterhinweise

## Mitgelieferter Code

### `FourierTransform` (audio-core, `com.aresstack.audio.core.fft`)

Die Schnittstelle stammt unverändert aus AskAI (aresstack/askai, MIT-Lizenz,
Stand `5b788e2`). Die Implementierung `RadixTwoFourierTransform` ist eigener Code.

## Optionale Downloads (nicht mitgeliefert)

Diese Daten liegen nicht im Repository und nicht im Jar. Sie werden nur geladen, wenn der
Benutzer es ausdrücklich auslöst; Quelle, Version, Prüfsumme, Bedingungen und Zitat werden
neben den Daten in `QUELLE.txt` abgelegt.

### MIT KEMAR HRTF, kompakter Satz

- Quelle: https://sound.media.mit.edu/resources/KEMAR/compact.zip
  (Beschreibung: https://sound.media.mit.edu/resources/KEMAR.html)
- Version: kompakter Satz, MIT Media Lab Perceptual Computing, 1994 (Dokumentation zuletzt
  überarbeitet Juli 2000); Prüfsumme in `HrtfDataset.MIT_KEMAR`
- Bedingungen (Datensatz-Freigabe, nicht die MIT-Softwarelizenz), im Wortlaut:
  „This data is Copyright 1994 by the MIT Media Laboratory. It is provided free with no
  restrictions on use, provided the authors are cited when the data is used in any research or
  commercial application.“
- Zitat: Bill Gardner and Keith Martin, "HRTF Measurements of a KEMAR Dummy-Head Microphone",
  MIT Media Lab Perceptual Computing Technical Report #280, May 1994.

### Soundbanks

Siehe `instruments` (`InstrumentDownloadCatalog`) und die Bibliotheksbeschreibungen dort.
