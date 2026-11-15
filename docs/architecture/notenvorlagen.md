# Notenvorlagen: eigene Noten statt freier Komposition

Stand 2026-11-15. Wunsch von Angelo: bei einem Stück und bei einem ganzen Album eigene Noten
vorgeben können, z. B. gemeinfreie Werke wie Vivaldis „Vier Jahreszeiten“ (vier Konzerte mit je
drei Sätzen, also zwölf Titel). Die KI komponiert dann nicht frei, sondern bringt die Vorlage ins
Format der App und berichtigt nur offensichtliche Fehler. Die Originalnoten bleiben, wie sie sind.

## Ablauf

1. **Lesen** (`studio.template.TemplateReader`)
   - MIDI (`MidiTemplate`) und MusicXML, auch komprimiert als `.mxl` (`MusicXmlTemplate`), liest
     das Studio selbst, genau wie geschrieben: Tonhöhen, Dauern, Akkorde, Haltebögen, Stimmen,
     zwei Systeme beim Klavier (rh/lh), Dynamik als Anschlagstärke, Artikulationen, Auftakt.
     Tempo, Taktart und Tonart kommen aus der Vorlage.
   - Andere Textformate (ABC, LilyPond, Humdrum, eigene Listen) überträgt die KI abschnittsweise
     in Noten (`TextTemplate`, Rolle „Komposition“). Einstellung „Zeilen der Vorlage je Anfrage“.
   - PDF und Bilder werden mit einem Hinweis abgelehnt (bitte MIDI/MusicXML/ABC geben).
2. **Offensichtliche Fehler** (`TemplateCleanup`, ohne KI): Noten ohne Länge, doppelte Noten,
   überlappende gleiche Tonhöhen, ungültige Lautstärken, leere Stimmen.
3. **Zweiter Blick der KI** (`TemplateReview`, Rolle „Planung“): Besetzung je Stimme (Id, Rolle,
   Instrument, GM-Programm, Klaviersystem), Titel, Stimmung, Tonart falls die Vorlage keine nennt,
   und eine Entscheidung zu jeder auffälligen Stelle (einzelne Note eine Oktave und mehr neben
   ihren Nachbarn: lassen, weglassen, Oktave, andere Tonhöhe). Die KI schreibt keine Noten.
   Ohne verwertbare Antwort entscheidet das Studio aus der Vorlage allein.
4. **Ins Stück** (`application.TemplatePiece`, aufgerufen vom `PieceAutopilot`): `arrangement.tsv`,
   `composition-plan.json` (mit `template`), `composition-spec.json`, `tracks.json`, Bühne,
   `template-corrections.txt` (was berichtigt wurde). Danach wird gerendert wie sonst.

Die Noten erscheinen sofort nach dem Lesen in der Partitur und noch einmal nach der Prüfung.

## Wo die Vorlage liegt

- Stück: Kopie der Datei in `<Stück>/score-template/` (`ScoreTemplates`). Ein Stück mit Vorlage
  wird immer aus ihr gemacht.
- Album: je Titel `.albums/<album>/titles/<titel-id>/score-template/` (`AlbumTemplates`). Die
  Produktion legt die Vorlage des Titels vor dem Machen ins Stück.

## Oberfläche

- Kompositionsseite, Entwurf: Zeile „Notenvorlage“ mit „Noten vorgeben …“ und „Ohne Vorlage“.
  Wählen startet sofort; der Kopf-Knopf heißt dann „Aus Noten übernehmen“.
- Albumseite: „Noten vorgeben …“ (eine Datei für den gewählten Titel, mehrere für die Titel ab dem
  gewählten, neue Titel je Datei, benannt nach der Datei) und „Ohne Vorlage“. Die Spalte „Länge“
  zeigt „aus den Noten“: Die Minuten des Albums gelten für Vorlagentitel nicht, die Länge ergibt
  sich aus der Vorlage.

## Offen

- Interpretation durch die KI über die Vorlage hinaus (Tempo-/Dynamikverlauf, Bühne) läuft heute
  über die vorhandenen Werkzeuge („Vortrag“, „KI mischt ab“), nicht automatisch beim Import.
- Tempo- und Taktwechsel innerhalb der Vorlage: übernommen wird der erste Wert (Hinweis im Protokoll).

## „Original nachbilden“ ohne Notendatei

Checkbox auf der Kompositionsseite (Entwurf, Zeile „Notenvorlage“) und auf der Albumseite (neben
den Minuten). Der Text nennt ein bestehendes Werk oder Quellen; die KI erfindet dann nichts Neues
darüber, sondern gibt das Original so getreu wie möglich wieder (`application.OriginalWork`).
Beim Album plant sie die echten Sätze in ihrer Reihenfolge mit echten Titeln, Tonarten, Taktarten,
Tempi und üblicher Länge; die Minuten des Albums gelten dann nicht (`AlbumConcept.original`, in
`album.json` als `concept.original`). Ohne Notendatei hängt die Treue am Wissen des Modells; genau
wird es nur mit „Noten vorgeben …“. Vorgegebene Dateien geben ihren Titel aus den Noten
(MusicXML Werk und Satz, MIDI), nicht von der KI.
