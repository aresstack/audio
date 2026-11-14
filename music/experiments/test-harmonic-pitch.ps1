# Run tests both as a saved script and as pasted inline PowerShell text.
$ErrorActionPreference = "Stop"

$MusicScript = Join-Path $PSScriptRoot "kipitz-gemma-multitrack.ps1"
& $MusicScript -SelfTest

$PastedScript = [ScriptBlock]::Create((Get-Content -Path $MusicScript -Raw -Encoding UTF8))
& $PastedScript -SelfTest

Write-Host "Saved-file and pasted-script modes passed."
