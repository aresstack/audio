# App-Icons

Vier Entwürfe für das Icon von Audio Studio. Standard ist **AS**; in den Einstellungen
(Ansicht · App-Icon) wählt man eines der vier, es wirkt sofort auf Fenster und Taskleiste.

| Schlüssel | Entwurf | Motiv |
|---|---|---|
| `as` | `entwurf-as.png` | Blau-violett: Note mit Wellenform und „AS“ |
| `klavier` | `entwurf-klavier.png` | Türkis: Klaviertasten mit Wellenform |
| `regler` | `entwurf-regler.png` | Orange: Drehregler mit Note und Fadern |
| `dirigent` | `entwurf-dirigent.png` | Rot-violett: Dirigentenstab mit Note und Pianoroll |

Die Entwürfe hier sind die Originale (1254 × 1254, schwarze Ecken). Das Studio lädt freigestellte
Fassungen in 16 bis 256 Pixeln aus `studio/src/main/resources/com/aresstack/audio/studio/icons/<schlüssel>/`.
Sie entstehen mit `freistellen.py` (Python 3 mit Pillow).

Den Standard ändert man an einer Stelle: `AppIcon.DEFAULT` in
`studio/src/main/java/com/aresstack/audio/studio/ui/AppIcon.java`. Ein weiteres Icon ist ein weiterer
Eintrag in diesem Enum und ein Ordner mit denselben Größen.
