# Validate cached Gemma output through the actual parser and Java renderer without API requests.
$ErrorActionPreference = "Stop"
$MusicScript = Join-Path $PSScriptRoot "kipitz-gemma-multitrack.ps1"
$RendererJar = Join-Path (Split-Path $PSScriptRoot -Parent) "build/libs/music-renderer.jar"
if (-not (Test-Path $RendererJar)) {
    throw "Build :music:jar before running cached rendering tests."
}

$SavedTemp = $env:TEMP
$Sandbox = Join-Path ([System.IO.Path]::GetTempPath()) ("audio-offline-test-" + [Guid]::NewGuid().ToString("N"))
$env:TEMP = $Sandbox
$Cache = Join-Path $Sandbox "kipitz-gemma-multitrack"
New-Item -ItemType Directory -Path $Cache -Force | Out-Null

function New-TestNote {
    param([double]$Beat, [double]$Duration, [int]$Pitch, [int]$Velocity = 70)
    return @{ beat = $Beat; duration = $Duration; pitch = $Pitch; velocity = $Velocity }
}

function Save-TestJson {
    param([string]$Name, [object]$Data)
    $Path = Join-Path $Cache ($Name + ".json")
    Set-Content -Path $Path -Value ($Data | ConvertTo-Json -Depth 16) -Encoding UTF8
}

try {
    Save-TestJson -Name "composition-plan" -Data @{
        title = "Offline smoke test"
        mood = "light"
        tempoBpm = 108
        key = "C major"
        timeSignature = "4/4"
        bars = 8
        chords = @("C", "G", "Am", "F", "C", "G", "F", "G")
        arrangementNotes = "Four original eight-bar instrument parts."
    }

    $MelodyNotes = @()
    for ($i = 0; $i -lt 16; $i++) {
        $MelodyNotes += New-TestNote -Beat ($i * 2) -Duration 1 -Pitch @(72, 76, 79, 74)[$i % 4]
    }
    Save-TestJson -Name "melody" -Data @{ part = "melody"; program = 10; notes = $MelodyNotes }

    $HarmonyNotes = @()
    $Chords = @(
        @(60, 64, 67), @(55, 59, 62), @(57, 60, 64), @(53, 57, 60),
        @(60, 64, 67), @(55, 59, 62), @(53, 57, 60), @(55, 59, 62)
    )
    for ($Bar = 0; $Bar -lt 8; $Bar++) {
        foreach ($Pitch in $Chords[$Bar]) {
            $HarmonyNotes += New-TestNote -Beat ($Bar * 4) -Duration 3.75 -Pitch $Pitch
        }
    }
    Save-TestJson -Name "harmony" -Data @{ part = "harmony"; program = 48; notes = $HarmonyNotes }

    $BassNotes = @()
    for ($Bar = 0; $Bar -lt 8; $Bar++) {
        $BassNotes += New-TestNote -Beat ($Bar * 4) -Duration 1 -Pitch 48
        $BassNotes += New-TestNote -Beat ($Bar * 4 + 2) -Duration 1 -Pitch 48
    }
    # The following were the precise boundaries previously assigned to the wrong bar.
    $BassNotes += New-TestNote -Beat 19 -Duration 0.5 -Pitch 48
    $BassNotes += New-TestNote -Beat 22 -Duration 0.5 -Pitch 50
    $BassNotes += New-TestNote -Beat 23 -Duration 0.5 -Pitch 47
    $BassNotes += New-TestNote -Beat 27 -Duration 0.5 -Pitch 45
    $BassNotes += New-TestNote -Beat 31.75 -Duration 0.25 -Pitch 48
    Save-TestJson -Name "bass" -Data @{ part = "bass"; program = 32; notes = $BassNotes }

    $DrumNotes = @()
    for ($Beat = 0; $Beat -lt 32; $Beat++) {
        $DrumNotes += New-TestNote -Beat $Beat -Duration 0.25 -Pitch $(if ($Beat % 2 -eq 0) { 36 } else { 38 })
    }
    Save-TestJson -Name "drums" -Data @{ part = "drums"; program = 0; notes = $DrumNotes }

    # Test both a saved script and an interactive paste. Cache the locally built JAR to
    # avoid any network dependency when the pasted script resolves its renderer.
    & $MusicScript
    $OutputFiles = @("arrangement.mid", "arrangement.wav", "melody.wav",
                     "harmony.wav", "bass.wav", "drums.wav", "harmonic-corrections.txt")
    foreach ($FileName in $OutputFiles) {
        $Output = Join-Path $Cache $FileName
        if (-not (Test-Path $Output) -or (Get-Item $Output).Length -eq 0) {
            throw "Missing or empty generated file: $Output"
        }
    }
    $Corrections = Get-Content (Join-Path $Cache "harmonic-corrections.txt")
    if ($Corrections -match "beat 19 pitch 48\b|beat 22 pitch 50\b|beat 23 pitch 47\b|beat 27 pitch 45\b") {
        throw "Previously valid chord tones were incorrectly corrected: $($Corrections -join '; ')"
    }

    $RendererCache = Join-Path $Sandbox "aresstack-audio-renderers"
    New-Item -ItemType Directory -Path $RendererCache -Force | Out-Null
    Copy-Item $RendererJar (Join-Path $RendererCache "music-renderer.jar")
    $Pasted = [ScriptBlock]::Create((Get-Content -Path $MusicScript -Raw -Encoding UTF8))
    & $Pasted
    foreach ($FileName in $OutputFiles) {
        if (-not (Test-Path (Join-Path $Cache $FileName))) {
            throw "Pasted-script execution failed to produce $FileName"
        }
    }

    # Exercise the actual Windows PowerShell 5.1 restricted execution path when available.
    $WindowsPowerShell = Get-Command powershell.exe -ErrorAction SilentlyContinue
    if ($null -ne $WindowsPowerShell) {
        $ConstrainedRunner = Join-Path $Sandbox "run-constrained.ps1"
        $EscapedMusicScript = $MusicScript.Replace("'", "''")
        $RunnerText = @'
$ErrorActionPreference = "Stop"
$ExecutionContext.SessionState.LanguageMode = "ConstrainedLanguage"
& '__SCRIPT__'
'@.Replace("__SCRIPT__", $EscapedMusicScript)
        Set-Content -Path $ConstrainedRunner -Value $RunnerText -Encoding UTF8
        & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $ConstrainedRunner
        if ($LASTEXITCODE -ne 0) {
            throw "Windows PowerShell 5.1 ConstrainedLanguage execution failed."
        }
        foreach ($FileName in $OutputFiles) {
            $Output = Join-Path $Cache $FileName
            if (-not (Test-Path $Output) -or (Get-Item $Output).Length -eq 0) {
                throw "ConstrainedLanguage execution failed to produce $FileName"
            }
        }
        Write-Host "Windows PowerShell ConstrainedLanguage end-to-end render passed."
    }

    # Verify that Windows can assemble the final ZIP without importing PowerShell.Archive.
    if (Get-Command tar.exe -ErrorAction SilentlyContinue) {
        $Archive = Join-Path $Sandbox "kipitz-gemma-multitrack.zip"
        if (-not (Test-Path $Archive) -or (Get-Item $Archive).Length -lt 1000) {
            throw "Windows archive was not generated by tar.exe."
        }
    }

    Write-Host "Offline cached generation passed for saved script and pasted execution."
} finally {
    $env:TEMP = $SavedTemp
    Remove-Item -Path $Sandbox -Recurse -Force -ErrorAction SilentlyContinue
}
