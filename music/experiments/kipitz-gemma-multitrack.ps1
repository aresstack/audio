# Run in the existing PowerShell session with $ApiKey and $CompletionsUrl.
# Let Gemma compose an original eight-bar song in separate instrument parts.
# Render an editable multitrack MIDI and preview WAVs using the Java 8 renderer compiled by Gradle.

param(
    [switch]$Regenerate,
    [switch]$SelfTest
)

$ErrorActionPreference = "Stop"

# Keep the original Gemma JSON intact. Correct only notes used for the rendered arrangement.
# Choose the nearest allowed chord tone within the instrument range; on a tie prefer the lower note.
function Resolve-HarmonicPitch {
    param(
        [int]$Pitch,
        [string]$Chord,
        [string]$PartId
    )

    $AllowedClasses = switch ($Chord) {
        "C" { @(0, 4, 7) }
        "G" { @(7, 11, 2) }
        "Am" { @(9, 0, 4) }
        "F" { @(5, 9, 0) }
        default { throw "Unsupported chord: $Chord" }
    }

    if ($PartId -eq "bass") {
        $Minimum = 36
        $Maximum = 60
    } elseif ($PartId -eq "harmony") {
        $Minimum = 48
        $Maximum = 76
    } else {
        throw "Unsupported harmonic part: $PartId"
    }

    if ($Pitch -ge $Minimum -and $Pitch -le $Maximum -and $AllowedClasses -contains ($Pitch % 12)) {
        return $Pitch
    }

    $Nearest = -1
    $SmallestDistance = 999
    for ($Candidate = $Minimum; $Candidate -le $Maximum; $Candidate++) {
        if ($AllowedClasses -notcontains ($Candidate % 12)) {
            continue
        }
        if ($Candidate -gt $Pitch) {
            $Distance = $Candidate - $Pitch
        } else {
            $Distance = $Pitch - $Candidate
        }
        if ($Distance -lt $SmallestDistance) {
            $Nearest = $Candidate
            $SmallestDistance = $Distance
        }
    }

    if ($Nearest -lt 0) {
        throw "No suitable chord tone for $PartId and chord $Chord."
    }
    return $Nearest
}

function Test-HarmonicPitch {
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
}

if ($SelfTest) {
    Test-HarmonicPitch
    return
}

$Model = "google/gemma-4-31B-it"
$OutputDirectory = Join-Path $env:TEMP "kipitz-gemma-multitrack"
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

function Invoke-KipitzCompletion {
    param([string]$Prompt, [int]$TokenLimit, [string]$StepName)

    $CachedJsonFile = Join-Path $OutputDirectory ($StepName + ".json")
    if (-not $Regenerate -and (Test-Path $CachedJsonFile)) {
        try {
            $CachedResult = Get-Content -Path $CachedJsonFile -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($null -ne $CachedResult) {
                Write-Host "Reusing $StepName from $CachedJsonFile (no API call)."
                return $CachedResult
            }
        } catch {
            Write-Warning "Could not reuse $StepName. Requesting it again: $($_.Exception.Message)"
        }
    }

    if (-not $ApiKey) {
        throw "ApiKey is not set in this PowerShell session, and no cached result is available for $StepName."
    }
    if (-not $CompletionsUrl) {
        throw "CompletionsUrl is not set in this PowerShell session, and no cached result is available for $StepName."
    }

    $Body = @{
        model = $Model
        messages = @(
            @{ role = "system"; content = "Compose ORIGINAL game music. Return only valid JSON, no Markdown." },
            @{ role = "user"; content = $Prompt }
        )
        temperature = 0.55
        top_p = 0.9
        top_k = 40
        max_tokens = $TokenLimit
        presence_penalty = 0.0
        frequency_penalty = 0.0
        user = "3d4j-multitrack-music"
        stream = $false
    } | ConvertTo-Json -Depth 10

    $Parameters = @{
        Uri = $CompletionsUrl
        Method = "Post"
        Headers = @{ Authorization = "Bearer $ApiKey" }
        ContentType = "application/json; charset=utf-8"
        Body = $Body
        TimeoutSec = 300
        ErrorAction = "Stop"
    }

    Write-Host "Generating $StepName using $Model ..."
    try {
        $Response = Invoke-RestMethod @Parameters
    } catch {
        Write-Host "HTTP error: $($_.Exception.Message)"
        Write-Host "Server response: $($_.ErrorDetails.Message)"
        throw
    }

    $Reason = [string]$Response.choices[0].finish_reason
    Write-Host "  finish_reason=$Reason; completion_tokens=$($Response.usage.completion_tokens)"
    if ($Reason -ne "stop") { throw "The $StepName output did not finish normally." }

    $Raw = [string]$Response.choices[0].message.content
    Set-Content -Path (Join-Path $OutputDirectory ($StepName + "-raw.txt")) -Value $Raw -Encoding UTF8
    if (-not $Raw) { throw "The $StepName answer is empty." }

    $Json = $Raw -replace '(?s)^.*?(?=\{)', ''
    $Json = $Json -replace '(?s)(\})[^}]*$', '$1'
    $Data = $Json | ConvertFrom-Json
    Set-Content -Path (Join-Path $OutputDirectory ($StepName + ".json")) -Value $Json -Encoding UTF8
    return $Data
}

$PlanPrompt = @'
Create an ORIGINAL cozy orchestral game loop for a charming pastel city simulation called Nur zur ZIHR.
The soundtrack celebrates the birth of a new city district. Whimsical, melodic, uplifting, soft and memorable.
Return exactly one JSON object with these fields only:
{"title":"...","mood":"...","tempoBpm":108,"key":"C major","timeSignature":"4/4","bars":8,"chords":["C","G","Am","F","C","G","F","G"],"arrangementNotes":"..."}

Keep the fixed tempo, key, time signature, bar count and exact chord progression above unchanged.
The song is 8 bars long: 32 quarter-note beats, numbered from beat 0 to 32.
Use four instruments: music box for melody, string ensemble for harmony, acoustic bass for bass, and percussion.
Describe an 8-bar musical motif with contrasting question/answer phrases, coordinated chords, and a satisfying ending.
Write an original composition, not an imitation or quotation of any existing song.
Do not include individual notes yet. No Markdown, explanations, or extra fields.
'@

$Plan = Invoke-KipitzCompletion -Prompt $PlanPrompt -TokenLimit 2048 -StepName "composition-plan"
if ([int]$Plan.tempoBpm -ne 108 -or [int]$Plan.bars -ne 8 -or [string]$Plan.key -ne "C major" -or [string]$Plan.timeSignature -ne "4/4") {
    throw "Gemma changed required timing/key. Inspect composition-plan.json."
}
$ExpectedChords = @("C", "G", "Am", "F", "C", "G", "F", "G")
if (@($Plan.chords).Count -ne 8) { throw "Expected eight chords." }
for ($Index = 0; $Index -lt 8; $Index++) {
    if ([string]$Plan.chords[$Index] -ne $ExpectedChords[$Index]) { throw "Wrong chord at bar $Index." }
}

$Instructions = @{
    melody = @'
Compose a memorable single-note lead melody for a soft music box (General MIDI program 10, zero-based).
Use 16 to 40 notes, primarily quarter and eighth notes. Include a recognisable motif in bars 1-2 that returns in bars 5-6.
Use MIDI pitches 60..88 in C major (pitch classes 0,2,4,5,7,9,11); lean on chord tones on strong beats.
Avoid aimless scales and random notes; make two related musical phrases and leave occasional rests.
'@
    harmony = @'
Compose soft string harmonies (General MIDI program 48, zero-based), 20 to 32 note events.
Use three-note triads across the eight measures. Hold chords for 2 or 4 beats; introduce elegant voice leading.
Use only C-major chord tones from the active chord in each bar. MIDI pitches 48..76.
Keep the arrangement below the lead melody; do not overpower it.
'@
    bass = @'
Compose an acoustic bass line (General MIDI program 32, zero-based), 12 to 28 notes.
Use only chord roots, fifths, and occasional thirds. Strong bass on beat 0 of each bar, gentle rhythmic motion.
MIDI pitches 36..60. Keep the bass supportive and steady, with no random chromatic movement.
'@
    drums = @'
Compose very gentle, sparse drums for a cozy orchestral game theme, 32 to 80 drum hits.
Use ONLY General MIDI percussion pitches 36 (kick), 38 (snare), 42 (closed hi-hat), 46 (open hi-hat).
Soft kick on beat 0 and beat 2 of many measures; light snare on beats 1 and 3; quiet hi-hats on some eighth notes.
Make the last bar a slightly celebratory fill, not a heavy electronic beat.
'@
}

$PartDefinitions = @(
    @{ id = "melody"; program = 10; channel = 0 },
    @{ id = "harmony"; program = 48; channel = 1 },
    @{ id = "bass"; program = 32; channel = 2 },
    @{ id = "drums"; program = 0; channel = 9 }
)

$Parts = @{}
$HarmonicCorrections = @()
$Lines = @("tempo`t108")
$MelodyContext = ""

foreach ($Definition in $PartDefinitions) {
    $PartId = [string]$Definition.id
    $Program = [int]$Definition.program
    $Channel = [int]$Definition.channel
    $Lines += "track`t$PartId`t$Program`t$Channel"
    $PartInstructions = [string]$Instructions[$PartId]

    $Prompt = @"
Compose the $PartId part for the ORIGINAL 8-bar instrumental song described below.
The result must fit EXACTLY 8 bars of 4/4 at 108 BPM, key C major, 32 total quarter-note beats.
Chord for each bar, in order: C | G | Am | F | C | G | F | G.
Plan description: $($Plan.arrangementNotes)
$PartInstructions
$MelodyContext
Return ONLY one JSON object with EXACTLY these fields:
{"part":"$PartId","program":$Program,"notes":[{"beat":0,"duration":1,"pitch":60,"velocity":75}]}
- Each note beat and duration is in quarter-note beats. Beat starts at 0, last playable instant is before beat 32.
- Each note ends no later than beat 32.
- Every beat and duration must be a multiple of 0.25 beats, duration at least 0.25 and at most 4.
- MIDI pitch integer 0..127, velocity integer 35..105.
- Use the requested part name and program number unchanged.
- Align all notes to the same shared global timeline; do not start each bar from beat zero.
- Write every note explicitly. No loops or shorthand. No Markdown, no commentary.
"@

    $Part = Invoke-KipitzCompletion -Prompt $Prompt -TokenLimit 8192 -StepName $PartId
    if ([string]$Part.part -ne $PartId -or [int]$Part.program -ne $Program) {
        throw "Wrong part identity or program: $PartId"
    }
    $Notes = @($Part.notes)
    if ($Notes.Count -lt 8 -or $Notes.Count -gt 90) { throw "Invalid note count for $PartId" }

    foreach ($Note in $Notes) {
        $Beat = [double]$Note.beat
        $Duration = [double]$Note.duration
        $Pitch = [int]$Note.pitch
        $Velocity = [int]$Note.velocity
        if ($Beat -lt 0 -or $Duration -lt 0.25 -or $Duration -gt 4 -or ($Beat + $Duration) -gt 32) {
            throw "Invalid note timing in ${PartId}: beat=$Beat duration=$Duration"
        }
        if (($Beat * 4) % 1 -ne 0 -or ($Duration * 4) % 1 -ne 0) {
            throw "A note in $PartId is not aligned to 16th-note units."
        }
        if ($Pitch -lt 0 -or $Pitch -gt 127 -or $Velocity -lt 35 -or $Velocity -gt 105) {
            throw "Invalid pitch or velocity in $PartId."
        }
        if ($PartId -eq "drums") {
            if (@(36, 38, 42, 46) -notcontains $Pitch) { throw "Unexpected drum pitch $Pitch" }
        } elseif ($PartId -eq "melody") {
            if ($Pitch -lt 60 -or $Pitch -gt 88 -or @(0,2,4,5,7,9,11) -notcontains ($Pitch % 12)) {
                throw "Melody pitch outside C major: $Pitch"
            }
        } else {
            $BarIndex = [int]($Beat / 4)
            if ($BarIndex -ge 8) { throw "Note starts after the last bar." }
            $Chord = $ExpectedChords[$BarIndex]
            $CorrectedPitch = Resolve-HarmonicPitch -Pitch $Pitch -Chord $Chord -PartId $PartId
            if ($CorrectedPitch -ne $Pitch) {
                $Correction = "Repaired $PartId bar $($BarIndex + 1) beat $Beat pitch $Pitch -> $CorrectedPitch for $Chord."
                Write-Warning $Correction
                $HarmonicCorrections += $Correction
                $Pitch = $CorrectedPitch
            }
        }
        $StartTick = [int]($Beat * 480)
        $LengthTick = [int]($Duration * 480)
        $Lines += "note`t$PartId`t$StartTick`t$LengthTick`t$Pitch`t$Velocity"
    }
    if ($PartId -eq "melody") {
        $MelodyContext = "Already composed lead melody (coordinate harmony with this): " + ($Part | ConvertTo-Json -Depth 10 -Compress)
    }
    $Parts[$PartId] = $Part
    Write-Host "  $PartId notes: $($Notes.Count)"
}

# Preserve the original model output and record deterministic pitch adjustments separately.
$CorrectionsFile = Join-Path $OutputDirectory "harmonic-corrections.txt"
if ($HarmonicCorrections.Count -gt 0) {
    Set-Content -Path $CorrectionsFile -Value $HarmonicCorrections -Encoding UTF8
    Write-Host "Applied $($HarmonicCorrections.Count) harmonic pitch corrections; details: $CorrectionsFile"
} elseif (Test-Path $CorrectionsFile) {
    Remove-Item -Path $CorrectionsFile -Force
}

$ArrangementFile = Join-Path $OutputDirectory "arrangement.tsv"
Set-Content -Path $ArrangementFile -Value $Lines -Encoding UTF8
$FullPlan = @{ composition = $Plan; tracks = $Parts }
Set-Content -Path (Join-Path $OutputDirectory "full-composition.json") -Value ($FullPlan | ConvertTo-Json -Depth 20) -Encoding UTF8

# Prefer the local Gradle-built JAR when running from a saved script.
# When pasted interactively, PSScriptRoot is empty, so use the public rolling release instead.
$RendererJar = $null
if (-not [string]::IsNullOrWhiteSpace($PSScriptRoot)) {
    $LocalRenderer = Join-Path $PSScriptRoot "..\build\libs\music-renderer.jar"
    if (Test-Path $LocalRenderer -PathType Leaf) {
        $RendererJar = (Resolve-Path $LocalRenderer).Path
    }
}
if (-not $RendererJar) {
    $RendererDirectory = Join-Path $env:TEMP "aresstack-audio-renderers"
    New-Item -ItemType Directory -Path $RendererDirectory -Force | Out-Null
    $RendererJar = Join-Path $RendererDirectory "music-renderer.jar"
    if (-not (Test-Path $RendererJar -PathType Leaf) -or (Get-Item $RendererJar).Length -lt 1000) {
        $RendererUrl = "https://github.com/aresstack/audio/releases/download/latest/music-renderer.jar"
        Write-Host "Downloading the music renderer from $RendererUrl"
        try {
            Invoke-WebRequest -Uri $RendererUrl -OutFile $RendererJar -UseBasicParsing -ErrorAction Stop
        } catch {
            if (Test-Path $RendererJar) { Remove-Item $RendererJar -Force }
            throw "Could not download music-renderer.jar from the rolling release: $($_.Exception.Message)"
        }
        if ((Get-Item $RendererJar).Length -lt 1000) {
            Remove-Item $RendererJar -Force
            throw "Downloaded music-renderer.jar is unexpectedly small."
        }
    }
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "Java is not on PATH. Add a Java 8+ runtime to PATH and rerun rendering."
}

Write-Host "Rendering MIDI and WAV using Java..."
& java -jar $RendererJar $ArrangementFile $OutputDirectory
if ($LASTEXITCODE -ne 0) { throw "The Java renderer failed (exit code $LASTEXITCODE)." }

$ZipFile = Join-Path $env:TEMP "kipitz-gemma-multitrack.zip"
Compress-Archive -Path "$OutputDirectory\*" -DestinationPath $ZipFile -Force
Write-Host ""
Write-Host "Music ready: " (Join-Path $OutputDirectory "arrangement.wav")
Write-Host "MIDI:        " (Join-Path $OutputDirectory "arrangement.mid")
Write-Host "Upload ZIP:  $ZipFile"
