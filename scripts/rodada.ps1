# Uma rodada de benchmark num modo: dataset (se faltar), backfill, ingestor + leitor em paralelo, relatório.
# O dataset é reaproveitado entre modos; apague data/tx.ndjson ao mudar --accounts ou --seed.
param(
    [Parameter(Mandatory = $true)][ValidateSet('oracle', 'oracle+valkey')][string] $Mode,
    [string] $Run = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss'),
    [int] $Tps = 600,
    [int] $Duration = 300,
    [int] $Warmup = 60,
    [int] $Accounts = 100000,
    [int] $Threads = 16,
    [int] $Seed = 42
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$poc = Join-Path $PSScriptRoot 'poc.ps1'
$envFile = Join-Path $root '.env.local'
$data = Join-Path $root 'data'
$logs = Join-Path $root 'logs'

function Invoke-Poc {
    & $poc @args
    if ($LASTEXITCODE -ne 0) { throw "programa '$($args[0])' falhou com código $LASTEXITCODE" }
}

if (-not (Test-Path (Join-Path $data 'tx.ndjson'))) {
    $count = [Math]::Max(200000, $Tps * ($Duration + 30))
    Invoke-Poc gerador --accounts $Accounts --seed $Seed --count $count
}
Invoke-Poc backfill --mode $Mode --accounts $Accounts --seed $Seed

$common = @('--mode', $Mode, '--tps', $Tps, '--duration', $Duration, '--warmup', $Warmup, '--threads', $Threads, '--run', $Run)
$ingestor = "sentinela-ingestor-$Run"
$leitor = "sentinela-leitor-$Run"
$mounts = @('-v', "${data}:/data", '-v', "${logs}:/logs")
podman run -d --name $ingestor --pod sentinela --env-file $envFile @mounts localhost/sentinela-poc ingestor @common | Out-Null
podman run -d --name $leitor --pod sentinela --env-file $envFile @mounts localhost/sentinela-poc leitor @common --accounts $Accounts --seed $Seed | Out-Null
Write-Host "rodada $Run ($Mode): ingestor e leitor em execução por $Duration s..."
$codes = @(podman wait $ingestor $leitor)
podman logs $ingestor
podman logs $leitor
podman rm $ingestor $leitor | Out-Null
if ($codes | Where-Object { $_ -ne '0' }) { throw "ingestor/leitor terminaram com códigos: $($codes -join ', ')" }

Invoke-Poc relatorio /logs
Write-Host "rodada $Run ($Mode) concluída; relatório em logs/report/report.md"
