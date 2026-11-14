# sounds

Game-event sound effects for 3d4j. Preserve the original KIPITZ/Gemma PowerShell experiment in `experiments/kipitz-gemma-sounds.ps1`, and the real model-generated plan in `examples/sound-plan.json`.

The Java CLI reads a JSON file containing a `sounds` array and renders each effect to 16-bit mono PCM WAV using Java Sound. It is a separate Gradle subproject from `music`.

```sh
gradle :sounds:run --args="sounds/examples/sound-plan.json sounds/build/effects"
```

Run the original PowerShell experiment in an existing session that contains `$ApiKey` and `$CompletionsUrl`; it remains independent of Gradle and generates its own WAVs.
