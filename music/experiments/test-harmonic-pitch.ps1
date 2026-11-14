# Run offline to verify deterministic pitch repair without using an AI model or API key.
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "harmonic-pitch.ps1")

$Cases = @(
    @{ pitch = 48; chord = "G"; part = "bass"; expected = 47 },
    @{ pitch = 43; chord = "G"; part = "bass"; expected = 43 },
    @{ pitch = 52; chord = "C"; part = "harmony"; expected = 52 },
    @{ pitch = 50; chord = "Am"; part = "harmony"; expected = 48 },
    @{ pitch = 55; chord = "F"; part = "bass"; expected = 53 },
    @{ pitch = 60; chord = "C"; part = "bass"; expected = 60 },
    @{ pitch = 120; chord = "G"; part = "bass"; expected = 59 }
)
foreach ($Case in $Cases) {
    $Actual = Resolve-HarmonicPitch -Pitch $Case.pitch -Chord $Case.chord -PartId $Case.part
    if ($Actual -ne $Case.expected) {
        throw "Pitch test failed for $($Case.part) $($Case.chord): expected $($Case.expected), got $Actual."
    }
}
Write-Host "Harmonic pitch tests passed: $($Cases.Count) cases."
