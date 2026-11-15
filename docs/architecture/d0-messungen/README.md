# D0: Messprogramme

Messprogramme zu Abschnitt 4b der Architekturprüfung. **Kein Produktionscode**: Die Dateien liegen bewusst außerhalb aller Source-Sets und werden vom Gradle-Build nicht übersetzt.

| Datei | misst |
|---|---|
| `FftBench.java` | Vorwärts- plus Rücktransformation bei N = 128 bis 16384: Mittelwert, Perzentile, Allokation je Paar, Rundreisefehler. Verglichen werden der Aufrufweg von AskAI `CommonsMathFourierTransform`, Commons Math mit wiederverwendetem Hüllfeld, eine reine JDK-Radix-2-FFT mit vorab berechneten Tabellen und JTransforms (komplex und reell, einfädig). |
| `MacBench.java` | Spektrale Multiplikation mit Aufsummierung einer gleichförmig partitionierten Faltung (2,5 s Impulsantwort, ein Kanal) als Anteil am Echtzeit-Budget eines Blocks. |
| `LimiterProto.java` | Prüfmuster des vorgeschlagenen Master-Limiters: Deckenwert, Verzerrungsfreiheit unterhalb der Schwelle, Stetigkeit, Kosten und Allokation. |
| `ergebnisse-2026-11-15.txt` | Rohausgabe der Läufe, die in 4b zitiert sind. |

Ausführen (JDK 21; Jars von Maven Central: `commons-math3-3.6.1`, `JTransforms-3.1`, `JLargeArrays-1.5`):

```
CP=commons-math3-3.6.1.jar:JTransforms-3.1.jar:JLargeArrays-1.5.jar
javac -cp "$CP" -d out FftBench.java MacBench.java LimiterProto.java
java -cp "$CP:out" -Xms512m -Xmx512m FftBench
java -cp out MacBench
java -cp out LimiterProto
```

Die Zahlen stammen aus einem gemeinsam genutzten Cloud-Container (4 vCPU, OpenJDK 21.0.12, keine Kernisolierung). Mittelwerte und Median sind belastbar, Maximalwerte nicht: Sie treten bei allen Implementierungen auf, auch bei den allokationsfreien, und spiegeln die Planung der virtuellen Maschine wider. Für Aussagen zur Echtzeit auf Zielrechnern ist mit D2 erneut zu messen.
