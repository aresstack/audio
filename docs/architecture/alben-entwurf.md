# Entwurfsnotiz: Funktion „Alben“

**Stand:** 15. November 2026 · **Geprüfter Code:** `aresstack/audio` `main` (nach F2, C1, C2, R1) · **Status:** Entwurf zur Prüfung durch die Architektur. Noch nichts davon ist umgesetzt.

Ein Album ist ein **Aggregat eigenständiger Stücke**, kein neuer Kompositionstyp. Jedes Stück eines Albums ist ein gewöhnliches Stück der Bibliothek mit eigenem Ordner und lässt sich im bisherigen Editor öffnen, ändern und einzeln abspielen. Die Albumfunktion plant, stößt an, merkt sich den Stand und stellt zusammen. Sie komponiert, rendert und mischt nicht selbst.

Nicht Teil der ersten Version: Cover-Art, gemeinsame Leitmotive über mehrere Stücke, Album-Mastering.

---

## 1. Was der Code heute hergibt

Geprüft wurden die Aufrufwege eines Einzelstücks vom Musikwunsch bis zur gemischten Datei.

| Schritt | Heutige Klasse | Befund für Alben |
| --- | --- | --- |
| Besetzung vorschlagen | `InstrumentProposalService.proposeStaged(description, directory, progress)` | Nimmt `KipitzChatClient`, nicht `ChatModel`. Für Tests mit `FakeChatModel` und für die globale Grenze muss der Dienst auf `ChatModel` umgestellt werden (kleine, verhaltensneutrale Änderung). |
| Komponieren | `GemmaMusicComposer.compose(description, directory, tracks, spec, progress)` | Nimmt bereits eine `CompositionSpec` (Takte, Tempo, Tonart, Taktart) und komponiert abschnittsweise. `MAX_BARS = 1024` reicht für fünf Minuten. `maxParallel` gilt je Stück. |
| Ins Werkmodell übernehmen | `ComposingIntoPiece.run(composer, folder, prompt, songs, model)` | Ohne Swing nutzbar. |
| Rendern | `StudioMusicRenderer.render(...)`, künftig `RenderService.render(...)` mit `StemCache` (R1) | Bereits erzeugte Spuren werden wiederverwendet. Die Einstellungen (`RenderSettings`) werden heute im Fenster eingesammelt. |
| Mischen und Export | `StageMixService.mix(...)` und `export(..., MixLevel)` | Die Normalisierung auf Lautheit kommt mit D1b (#9, `ExportNormalization`, `LoudnessMeter`). Der Album-Export hängt davon ab. |
| Bibliothek | `SongLibrary` (`create`, `createUntitled`, `save`, `songs`) | `songs()` zählt jeden Unterordner als Stück. Ein Albumordner in der Bibliothek darf dort nicht erscheinen. |

**Der wichtigste Befund:** Die Abfolge „Besetzung vorschlagen, Bühne übernehmen, komponieren, rendern“ (der Autopilot) steht heute als Methode im Fenster (`AudioStudioFrame.proposeInstruments(true)`), verwoben mit `SwingUtilities.invokeAndWait`. Eine Albumproduktion kann sie so nicht benutzen. Bevor Alben entstehen, wird diese Abfolge in einen Dienst ohne Swing herausgelöst, den dann das Fenster und die Albumproduktion gleichermaßen rufen. Das ist der erste PR.

---

## 2. Begriffe und Daten

Neues Paket `com.aresstack.audio.studio.album` (ohne Swing). Die Oberfläche liegt in `ui`.

```java
/** Ein Album: Konzept und Trackliste. Unveränderlich; jede Änderung ergibt ein neues Album. */
public record Album(String id, String title, AlbumConcept concept, List<AlbumTrack> tracks) { }

/** Was der Benutzer einmal für das ganze Album vorgibt. */
public record AlbumConcept(String description, String style, int trackCount, int secondsPerTrack) { }

/** Ein geplanter Titel. `piece` ist der Ordnername des Stücks in der Bibliothek, sobald es einen gibt. */
public record AlbumTrack(String id, String title, String mood, int bpm, String key, String meter,
                         String instrumentation, String form, int targetSeconds, String piece) { }
```

**Ablage.** `<Bibliothek>/.albums/<id>/album.json` (Konzept und Trackliste) und daneben `production.json` (Warteschlange, Abschnitt 5). Die Stücke selbst liegen als gewöhnliche Ordner in der Bibliothek; das Album nennt nur ihre Ordnernamen. `SongLibrary.songs()` überspringt künftig Ordner, deren Name mit einem Punkt beginnt. Ein Stück kennt sein Album nicht: Wird das Album gelöscht, bleiben die Stücke; wird ein Stück gelöscht, zeigt das Album den Titel als „fehlt“ und kann ihn neu produzieren.

**Von der Dauer zur Taktzahl.** `AlbumTrack.spec()` rechnet die Ziellänge um:

`Takte = runde(Sekunden × BPM ÷ (60 × Viertel je Takt))`, begrenzt auf `1..CompositionSpec.MAX_BARS`.

Bei 120 BPM im 4/4-Takt ergeben 300 Sekunden 150 Takte. Die tatsächliche Länge wird zweimal geprüft: vor der Produktion über `CompositionSpec.seconds()` (Abweichung durch das Runden) und danach an der gemischten Datei. Weicht ein Stück um mehr als 10 % von der Ziellänge ab, bekommt es in der Trackliste einen Hinweis; es wird nicht automatisch neu komponiert.

---

## 3. Schnittstellen

```java
/** Der Einstieg für Oberfläche und Tests. */
public interface AlbumApplicationService {
    Album create(String title, AlbumConcept concept) throws IOException;
    List<Album> albums();
    /** Lässt die KI die Trackliste entwerfen; ersetzt nur Titel, die noch kein Stück haben. */
    Album plan(String albumId, Progress progress) throws IOException;
    /** Die Trackliste von Hand ändern: Titel ändern, entfernen, ergänzen, umsortieren. */
    Album edit(String albumId, List<AlbumTrack> tracks) throws IOException;
    /** Startet die Produktion oder setzt sie fort; kehrt sofort zurück. */
    AlbumProduction produce(String albumId, ProductionListener listener) throws IOException;
    void cancel(String albumId);
    AlbumExport export(String albumId, Path target, AlbumExportOptions options, Progress progress) throws Exception;
}

/** Entwirft aus dem Konzept eine Trackliste. Die einzige Stelle der Albumfunktion mit einem Prompt. */
public interface AlbumPlanner {
    List<AlbumTrack> plan(AlbumConcept concept, List<AlbumTrack> kept, Path cache, Progress progress) throws IOException;
}

/** Liest und schreibt Alben und den Stand ihrer Produktion; schreibt atomar über eine .part-Datei. */
public interface AlbumRepository {
    List<Album> albums();
    Optional<Album> album(String id);
    void save(Album album) throws IOException;
    void delete(String id) throws IOException;
    AlbumProductionState state(String id);
    void save(String id, AlbumProductionState state) throws IOException;
}

/** Arbeitet die Warteschlange ab: Reihenfolge, Parallelität über Stücke, Fehler, Fortsetzen. */
public interface AlbumProductionCoordinator {
    AlbumProduction start(Album album, ProductionListener listener);
}

/** Der einzige Weg, auf dem die Albumproduktion ein Stück entstehen lässt. */
public interface PieceProduction {
    /** Bringt ein Stück bis zur gemischten Datei und sagt, was dabei herauskam. Wiederholbar. */
    ProducedPiece produce(PieceOrder order, Progress progress) throws Exception;
}

public record PieceOrder(Path folder, String name, String description, CompositionSpec spec) { }
public record ProducedPiece(Path folder, Path mix, double seconds) { }

/** Stellt die fertigen Stücke zusammen. */
public interface AlbumExporter {
    AlbumExport export(Album album, List<ProducedPiece> pieces, Path target, AlbumExportOptions options,
                       Progress progress) throws Exception;
}
```

**Die Grenze, auf die es ankommt:** `AlbumProductionCoordinator` kennt nur `PieceProduction`. Er kennt weder `GemmaMusicComposer` noch `StudioMusicRenderer`, `RenderService`, `StageMixService` oder einen Prompt. Die eine Umsetzung von `PieceProduction` (`AutopilotPieceProduction`) ruft den in Abschnitt 1 herausgelösten Autopilot-Dienst. Ein Architekturtest hält fest, dass im Paket `album` außer in dieser einen Klasse keine der genannten Klassen vorkommt.

**Zusammenhang zwischen den Stücken.** Jedes Stück bekommt beim Komponieren eine kurze Beschreibung des Albums und der übrigen Titel mit. Das geschieht über `PieceOrder.description`: Der Koordinator setzt den Text aus dem Albumkonzept, dem eigenen Titel (Stimmung, Besetzung, Form) und einer Zeile je übrigem Titel zusammen. Die bestehende Pipeline bekommt also nur einen längeren Musikwunsch und braucht keine Änderung. Die Form (Intro, Hauptthema, Variation, Mittelteil, Wiederaufnahme, Outro) steht als Text im Titel und geht so in den Kompositionsplan ein, damit fünf Minuten nicht aus der Wiederholung einer kurzen Idee bestehen.

---

## 4. Globale Grenze für KI-Anfragen

Innerhalb eines Stücks begrenzt `GemmaMusicComposer.maxParallel` die Anfragen wie bisher. Über Stücke hinweg gilt eine zweite Grenze: ein `ChatModel`, das ein anderes umhüllt und jede Anfrage durch eine gemeinsame `Semaphore` führt (`LimitedChatModel`). Alle Stücke einer Albumproduktion teilen sich dieselbe Instanz. So bleibt die Summe aller gleichzeitigen Anfragen unter dem eingestellten Wert, gleich wie viele Stücke gerade laufen.

Zwei Einstellungen: Stücke gleichzeitig (Vorgabe 2) und KI-Anfragen gleichzeitig insgesamt (Vorgabe: der heutige Wert „parallele Anfragen“).

---

## 5. Warteschlange, Fehler, Fortsetzen

`production.json` hält je Titel einen Zustand: `PLANNED`, `QUEUED`, `RUNNING`, `DONE`, `FAILED` (mit Grund und Zeitpunkt). Jeder Zustandswechsel wird sofort geschrieben.

- **Ein Fehler bricht das Album nicht ab.** Schlägt ein Stück fehl, wird es `FAILED`, die übrigen laufen weiter. „Fehlgeschlagene erneut versuchen“ stellt sie wieder in die Schlange.
- **Fortsetzen nach Abbruch oder Neustart.** Beim Start gelten Titel im Zustand `RUNNING` wieder als `QUEUED`. `DONE` wird nicht erneut produziert, solange Ordner und gemischte Datei da sind. Ein unterbrochenes Stück beginnt nicht von vorn: Die Antworten der KI liegen im Ordner des Stücks (der `cache` von `ChatModel.generate`), die gerenderten Spuren im `StemCache`. `PieceProduction.produce` ist deshalb wiederholbar und holt nur nach, was fehlt.
- **Abbrechen** stoppt die Annahme neuer Titel und ruft `ChatModel.cancel()`; laufende Titel gehen auf `QUEUED` zurück.
- Die PO-Regel bleibt gewahrt: Die Albumproduktion schreibt nur in Stücke, die sie selbst angelegt hat und die noch nicht `DONE` sind. Ein fertiges Stück, das der Benutzer im Editor geändert hat, fasst sie nicht mehr an, außer er verlangt ausdrücklich „neu produzieren“.

---

## 6. Export

Zwei Formen, beide in einen gewählten Ordner:

1. **WAV-Sammlung:** `01 - Titel.wav` bis `12 - Titel.wav`, dazu `album.txt` mit Titeln und Längen.
2. **Durchgehendes WAV:** alle Stücke hintereinander mit einstellbarer Pause (Vorgabe 2 Sekunden), dazu eine `.cue`-Datei mit den Startzeiten.

**Einheitliche Ziellautheit.** Jedes Stück wird über `StageMixService.export(..., MixLevel)` mit der Lautheitsnormalisierung aus D1b auf denselben Zielwert gebracht (Vorgabe −16 LUFS, einstellbar), mit der Spitzengrenze von −1 dBFS. Das verändert nur die Gesamtverstärkung je Stück; der Dynamikumfang innerhalb eines Stücks bleibt. Der Export verändert keine gespeicherten Stücke. Ein gemeinsames Mastering über das ganze Album kommt später.

---

## 7. Umsetzung in kleinen PRs

| PR | Inhalt | Hängt ab von |
| --- | --- | --- |
| A0 | Diese Notiz | – |
| A1 | Autopilot aus dem Fenster in einen Dienst ohne Swing herauslösen; `InstrumentProposalService` auf `ChatModel`; Fenster ruft den Dienst. Verhaltensneutral. | – |
| A2 | `Album`, `AlbumTrack`, `AlbumRepository` (Datei), Dauer → Taktzahl, `SongLibrary` überspringt Punkt-Ordner | – |
| A3 | `AlbumPlanner` mit KI (Tests mit `FakeChatModel`), Trackliste bearbeiten | A2 |
| A4 | `PieceProduction`, `AlbumProductionCoordinator`, `LimitedChatModel`, Warteschlange mit Fortsetzen | A1, A2 |
| A5 | `AlbumExporter` (Sammlung, durchgehendes WAV, Ziellautheit) | A2, D1b (#9) |
| A6 | Oberfläche „Alben“ neben dem Einzelstück: Liste, Konzept, Trackliste, Fortschritt je Titel, Stück im Editor öffnen | A3, A4, A5 |

End-to-End-Ziel ist ein Weihnachtsalbum mit zwölf Stücken von je etwa fünf Minuten; die Abnahme mit echtem Modell folgt später. Bis dahin läuft derselbe Ablauf in einem Test mit `FakeChatModel` und kurzen Stücken.

---

## 8. Fragen an die Architektur

1. **Ablage:** `<Bibliothek>/.albums/` mit der Änderung an `SongLibrary.songs()`, oder ein Ordner neben der Bibliothek?
2. **A1:** Soll der herausgelöste Autopilot schon auf `RenderService` (R1) aufsetzen oder zunächst auf `StudioMusicRenderer`, wie das Fenster heute?
3. **Bühne je Stück:** Die Albumproduktion übernimmt die von der KI vorgeschlagene Bühne wie der Autopilot. Reicht das für die erste Version, oder soll ein Album eine gemeinsame Raumvorgabe haben?
4. **Paket:** `studio.album` im Modul `studio`, weil die Funktion die Dienste dort orchestriert. Die reinen Daten (`Album`, `AlbumTrack`) könnten später nach `composition` wandern.
