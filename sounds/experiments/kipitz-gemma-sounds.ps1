# Run in the existing PowerShell session with $ApiKey and $CompletionsUrl.
# Ask Gemma to design four game sound effects, then synthesize WAV files locally.

$ErrorActionPreference = "Stop"

if (-not $ApiKey) { throw "ApiKey is not set in this PowerShell session." }
if (-not $CompletionsUrl) { throw "CompletionsUrl is not set in this PowerShell session." }

$Model = "google/gemma-4-31B-it"
$OutputDirectory = Join-Path $env:TEMP "kipitz-gemma-sounds"
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

$Prompt = @'
Design FOUR distinctive short, cheerful, original retro-game sound effects for a cozy pastel city simulation.
Return exactly ONE JSON object. No Markdown, no text outside JSON.

Use this exact schema:
{"sounds":[{"id":"new-sea-creature","durationMs":900,"events":[{"waveform":"sine","startMs":0,"durationMs":200,"startHz":350,"endHz":850,"volume":0.20,"attackMs":8,"releaseMs":90}]}]}

Required sound IDs in this exact order:
1. new-sea-creature: three or more staggered rising bubbles, watery and playful.
2. new-land-creature: friendly little chirps or earthy bouncing notes.
3. new-visitor: a delightful musical welcome with three ascending notes.
4. new-district: a slightly longer celebratory fanfare using overlapping notes.

Constraints:
- Each sound has only id, durationMs, events.
- Each event has exactly waveform, startMs, durationMs, startHz, endHz, volume, attackMs, releaseMs.
- waveform is one of: sine, triangle, square.
- Each sound durationMs is between 550 and 1700.
- Each sound has 3 to 8 events.
- startMs is between 0 and 1400; durationMs is between 60 and 850.
- Each event must end at or before the sound durationMs.
- startHz and endHz are numbers between 120 and 1600.
- volume is a number between 0.05 and 0.28.
- attackMs is 5 to 50; releaseMs is 40 to 250.
- Use smooth sine tones for sea bubbles, triangle for soft character sounds, and limited square for playful accents.
- Compose pleasant musical intervals; avoid harsh or loud results.
- No extra fields, comments, code, audio data, or explanations.
'@

$Body = @{
    model = $Model
    messages = @(
        @{ role = "system"; content = "Return valid JSON matching the requested schema. No Markdown." },
        @{ role = "user"; content = $Prompt }
    )
    temperature = 0.5
    top_p = 0.9
    top_k = 40
    max_tokens = 8192
    presence_penalty = 0.0
    frequency_penalty = 0.0
    user = "3d4j-sound-experiment"
    stream = $false
} | ConvertTo-Json -Depth 10

$RequestParameters = @{
    Uri = $CompletionsUrl
    Method = "Post"
    Headers = @{ Authorization = "Bearer $ApiKey" }
    ContentType = "application/json; charset=utf-8"
    Body = $Body
    TimeoutSec = 300
    ErrorAction = "Stop"
}

Write-Host "Requesting four sound designs from $Model ..."
try {
    $Response = Invoke-RestMethod @RequestParameters
} catch {
    Write-Host "HTTP error: $($_.Exception.Message)"
    Write-Host "Server response: $($_.ErrorDetails.Message)"
    throw
}

$FinishReason = [string]$Response.choices[0].finish_reason
Write-Host "finish_reason=$FinishReason; completion_tokens=$($Response.usage.completion_tokens)"
if ($FinishReason -ne "stop") { throw "Gemma did not finish normally." }

$RawAnswer = [string]$Response.choices[0].message.content
$RawFile = Join-Path $OutputDirectory "gemma-raw.txt"
Set-Content -Path $RawFile -Value $RawAnswer -Encoding UTF8

$Json = $RawAnswer -replace '(?s)^.*?(?=\{)', ''
$Json = $Json -replace '(?s)(\})[^}]*$', '$1'
$SoundPlan = $Json | ConvertFrom-Json
$RequiredIds = @("new-sea-creature", "new-land-creature", "new-visitor", "new-district")
if (@($SoundPlan.sounds).Count -ne 4) { throw "Gemma must return exactly four sounds. Inspect $RawFile" }

$PlanFile = Join-Path $OutputDirectory "sound-plan.json"
Set-Content -Path $PlanFile -Value $Json -Encoding UTF8

function Write-UInt16LE {
    param([byte[]]$Bytes, [int]$Offset, [int]$Number)
    $Bytes[$Offset] = [byte]($Number -band 255)
    $Bytes[$Offset + 1] = [byte](($Number -shr 8) -band 255)
}

function Write-UInt32LE {
    param([byte[]]$Bytes, [int]$Offset, [int]$Number)
    $Bytes[$Offset] = [byte]($Number -band 255)
    $Bytes[$Offset + 1] = [byte](($Number -shr 8) -band 255)
    $Bytes[$Offset + 2] = [byte](($Number -shr 16) -band 255)
    $Bytes[$Offset + 3] = [byte](($Number -shr 24) -band 255)
}

function Convert-SoundToWav {
    param($Sound, [string]$OutputFile)

    $SampleRate = 16000
    $DurationMs = [int]$Sound.durationMs
    if ($DurationMs -lt 550 -or $DurationMs -gt 1700) { throw "Invalid sound duration: $DurationMs" }
    if (@($Sound.events).Count -lt 3 -or @($Sound.events).Count -gt 8) { throw "Invalid event count in $($Sound.id)" }

    $SampleCount = [int]($SampleRate * $DurationMs / 1000)
    $Samples = [double[]](@(0) * $SampleCount)

    foreach ($Event in $Sound.events) {
        $Waveform = [string]$Event.waveform
        if (@("sine", "triangle", "square") -notcontains $Waveform) { throw "Unsupported waveform: $Waveform" }

        $StartMs = [int]$Event.startMs
        $EventDurationMs = [int]$Event.durationMs
        $StartHz = [double]$Event.startHz
        $EndHz = [double]$Event.endHz
        $Volume = [double]$Event.volume
        $AttackMs = [int]$Event.attackMs
        $ReleaseMs = [int]$Event.releaseMs
        if ($StartMs -lt 0 -or $StartMs -gt 1400 -or $EventDurationMs -lt 60 -or $EventDurationMs -gt 850 -or ($StartMs + $EventDurationMs) -gt $DurationMs) { throw "Invalid event timing in $($Sound.id)" }
        if ($StartHz -lt 120 -or $StartHz -gt 1600 -or $EndHz -lt 120 -or $EndHz -gt 1600) { throw "Invalid event frequency in $($Sound.id)" }
        if ($Volume -lt 0.05 -or $Volume -gt 0.28 -or $AttackMs -lt 5 -or $AttackMs -gt 50 -or $ReleaseMs -lt 40 -or $ReleaseMs -gt 250) { throw "Invalid event envelope in $($Sound.id)" }

        $StartSample = [int]($StartMs * $SampleRate / 1000)
        $EventSamples = [int]($EventDurationMs * $SampleRate / 1000)
        $AttackSamples = [int]($AttackMs * $SampleRate / 1000)
        $ReleaseSamples = [int]($ReleaseMs * $SampleRate / 1000)
        $Phase = 0.0

        for ($Index = 0; $Index -lt $EventSamples; $Index++) {
            $Position = $Index / [double]$EventSamples
            $Frequency = $StartHz + ($EndHz - $StartHz) * $Position
            $Phase += $Frequency / $SampleRate
            if ($Phase -ge 1.0) { $Phase -= [int]$Phase }

            if ($Waveform -eq "square") {
                $Signal = if ($Phase -lt 0.5) { 1.0 } else { -1.0 }
            } elseif ($Waveform -eq "triangle") {
                $Distance = $Phase - 0.5
                if ($Distance -lt 0) { $Distance = -$Distance }
                $Signal = 1.0 - 4.0 * $Distance
            } else {
                # Approximate a sine wave using only arithmetic supported by Constrained Language Mode.
                $X = $Phase * 2.0 - 1.0
                $AbsX = if ($X -lt 0) { -$X } else { $X }
                $Y = 4.0 * $X * (1.0 - $AbsX)
                $AbsY = if ($Y -lt 0) { -$Y } else { $Y }
                $Signal = -($Y + 0.225 * ($Y * $AbsY - $Y))
            }

            $Envelope = 1.0
            if ($Index -lt $AttackSamples) { $Envelope = $Index / [double]$AttackSamples }
            $Remaining = $EventSamples - $Index
            if ($Remaining -lt $ReleaseSamples) {
                $ReleaseEnvelope = $Remaining / [double]$ReleaseSamples
                if ($ReleaseEnvelope -lt $Envelope) { $Envelope = $ReleaseEnvelope }
            }
            $SampleIndex = $StartSample + $Index
            $Samples[$SampleIndex] += $Signal * $Volume * $Envelope
        }
    }

    $DataSize = $SampleCount * 2
    $Bytes = [byte[]](@(0) * (44 + $DataSize))
    $Bytes[0] = 82; $Bytes[1] = 73; $Bytes[2] = 70; $Bytes[3] = 70
    Write-UInt32LE -Bytes $Bytes -Offset 4 -Number (36 + $DataSize)
    $Bytes[8] = 87; $Bytes[9] = 65; $Bytes[10] = 86; $Bytes[11] = 69
    $Bytes[12] = 102; $Bytes[13] = 109; $Bytes[14] = 116; $Bytes[15] = 32
    Write-UInt32LE -Bytes $Bytes -Offset 16 -Number 16
    Write-UInt16LE -Bytes $Bytes -Offset 20 -Number 1
    Write-UInt16LE -Bytes $Bytes -Offset 22 -Number 1
    Write-UInt32LE -Bytes $Bytes -Offset 24 -Number $SampleRate
    Write-UInt32LE -Bytes $Bytes -Offset 28 -Number ($SampleRate * 2)
    Write-UInt16LE -Bytes $Bytes -Offset 32 -Number 2
    Write-UInt16LE -Bytes $Bytes -Offset 34 -Number 16
    $Bytes[36] = 100; $Bytes[37] = 97; $Bytes[38] = 116; $Bytes[39] = 97
    Write-UInt32LE -Bytes $Bytes -Offset 40 -Number $DataSize

    for ($Index = 0; $Index -lt $SampleCount; $Index++) {
        $Amplitude = $Samples[$Index] * 0.65
        if ($Amplitude -gt 1) { $Amplitude = 1 }
        if ($Amplitude -lt -1) { $Amplitude = -1 }
        $Pcm = [int]($Amplitude * 32767)
        if ($Pcm -lt 0) { $Pcm += 65536 }
        $Offset = 44 + $Index * 2
        $Bytes[$Offset] = [byte]($Pcm -band 255)
        $Bytes[$Offset + 1] = [byte](($Pcm -shr 8) -band 255)
    }

    # Windows PowerShell 5.1 writes the byte array without a BOM.
    Set-Content -Path $OutputFile -Encoding Byte -Value $Bytes
}

for ($SoundIndex = 0; $SoundIndex -lt 4; $SoundIndex++) {
    $Sound = $SoundPlan.sounds[$SoundIndex]
    $SoundId = [string]$Sound.id
    if ($SoundId -ne $RequiredIds[$SoundIndex]) { throw "Unexpected sound ID: $SoundId" }
    $WavFile = Join-Path $OutputDirectory ($SoundId + ".wav")
    Write-Host "Synthesizing $SoundId ..."
    Convert-SoundToWav -Sound $Sound -OutputFile $WavFile
    Write-Host "  WAV: $WavFile"
}

$ZipFile = Join-Path $env:TEMP "kipitz-gemma-sounds.zip"
try {
    Compress-Archive -Path "$OutputDirectory\*.wav", $PlanFile -DestinationPath $ZipFile -Force
    Write-Host "Upload this ZIP for review: $ZipFile"
} catch {
    Write-Warning "Could not create ZIP. Upload the WAV files and sound-plan.json from $OutputDirectory."
}
Write-Host "All sound files are in: $OutputDirectory"
