# Leisere Wandechos gegen Löcher beim Gleiten

Nach D2 (Lauf B, Hörbericht „Loch im Ton“ beim Gleitsprung). PO-Entscheidung auf PR #22 am 15.11.2026: Wandechos ×0,5, kein Allpass. Die Messungen liegen in den Projektdateien unter `d4-wandechos/messung-streuung.md`.

## Was sich ändert

`SpatialStemMixer.wallEchoLevel = 0.5` halbiert die Gains der frühen Wandechos, und zwar für den Datei-Mix und für den Live-Mix gleich, weil beide dieselbe Formel benutzen (`echo`, `echoInto`). Direktschall, Hall-Anteil, Shelf, Panorama und die Verzögerungen bleiben, wie sie waren.

Warum: Jedes Echo ist eine verzögerte Kopie des Direktschalls. Gleitet die Quelle, ändert sich der Abstand der beiden, und gehaltene Töne laufen durch Auslöschungen. Wie tief die werden, hängt vom Pegel des Echos ab. Ein Allpass je Wand wurde gemessen und verworfen: Er dreht nur die Phase und behält den Betrag, deshalb bleibt die Auslöschung gleich tief (bestenfalls 8 statt 10 Löcher).

## Abnahme: `WallEchoMotionTest`

Lauf B reproduzierbar für alle vier Raum-Presets: die Melodie von district-dawn, vier Gleitsprünge zu 600 ms, live in Blöcken zu 512. Alle 20 ms (exaktes Raster) wird das 10-ms-Fenster mit demselben Stück verglichen, gerendert mit der stehenden Quelle an dem Ort, an dem die bewegte Quelle gerade zu hören ist. Das ist 20 ms nach dem Setzen (Kanal-Glättung 20 ms, 10-ms-Stufen, Blockgrenze). Ein Loch ist ein Fenster, das mehr als 1,5 dB leiser ist.

| Raum | heute (×1) | ×0,5 | nur frühe Echos (×0,5) | nur Hall | Pegel je Ort, Max−Min (×1 → ×0,5) |
|---|---|---|---|---|---|
| Studio | 0 / −0,72 | 0 / −0,37 | 0 | 0 | 1,93 → 0,97 dB |
| Kammermusiksaal | 3 / −2,35 | **0** / −1,15 | 0 | 0 | 5,36 → 2,85 dB |
| Konzertsaal | 5 / −2,47 | **0** / −1,41 | 0 | 0 | 6,07 → 3,03 dB |
| Kathedrale | 8 / −4,16 | 3 / −3,05 | 1 / −1,56 | 5 / −2,05 | 6,41 → 3,43 dB |

Löcher / tiefstes Loch in dB. Unverschoben gemessen, also zum Zeitpunkt des Setzens, ergibt ×0,5: 0 / 2 / 3 / 8 (heute: 0 / 8 / 14 / 18). Die volle Tabelle gibt `-Dwall.echo.report=true` aus.

- Studio, Kammermusiksaal und Konzertsaal: 0 Löcher, das wird geprüft.
- Kathedrale (eigenes Kriterium, PO): Ihr Hall von 4,5 s trägt noch die Orte, an denen die Quelle vorher stand. Eine stehende Vergleichsquelle hat das nie, deshalb behält sie auch ohne Wandechos 5 Löcher. Geprüft wird: Bei gleichem Hall und gleicher Bewegung entsteht gegenüber dem alten Klang kein neues Loch, und keines wird tiefer. Frühe Echos allein und Hall allein werden zur Diagnose gemessen.
- Kein Raum zeigt beim Gleiten einen Aussetzer (ein Fenster mehr als 6 dB unter beiden Nachbarn), den die stehende Quelle nicht auch hat.
- Eine stehende Quelle klingt live in Blöcken zu 128 und zu 512 bitgleich.

## Regression

- Der Vergleich mit dem alten Mixer (`SpatialStemMixerEquivalenceTest`, Kompatibilitätsmodus in `MixLevelTest`) läuft mit Wandechos ×1 und bleibt bitgleich. Alles außer dem beschlossenen Echopegel ist also unverändert.
- Golden: Nur der Hash des Bühnen-Mixes (`golden/mix/*.txt`) ändert sich, gewollt durch die leiseren Echos. Die Referenz-Renderings (`golden/render`) und alle anderen Golden-Dateien bleiben gleich.

## Hörvergleich

In den Projektdateien unter `d4-wandechos/hoerproben/` liegt je Preset `lauf-b-<raum>-vorher.wav` (×1), `-nachher.wav` (×0,5) und `-nachher-gleich-laut.wav`, die Nachher-Fassung auf die Energie der Vorher-Fassung angehoben, damit nur die frühen Echos verschieden sind. Geschrieben werden sie mit `-Dwall.echo.samples=<ordner>`. Die klangliche Freigabe erteilt der PO nach dem Hören.
