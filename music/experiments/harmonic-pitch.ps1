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
