# Roda um programa da POC num container efêmero dentro do pod sentinela.
# Exemplo: pwsh scripts/poc.ps1 gerador --count 1000
param([Parameter(ValueFromRemainingArguments = $true)][string[]] $PocArgs)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$envFile = Join-Path $root '.env.local'
if (-not (Test-Path $envFile)) { throw "Crie $envFile a partir de .env.example" }
$data = Join-Path $root 'data'
$logs = Join-Path $root 'logs'
New-Item -ItemType Directory -Force $data, $logs | Out-Null

podman run --rm --pod sentinela --env-file $envFile `
  -v "${data}:/data" -v "${logs}:/logs" `
  localhost/sentinela-poc @PocArgs
exit $LASTEXITCODE
