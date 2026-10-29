# Executa o Maven num container, dentro do pod sentinela quando ele existir,
# para que os testes de integração enxerguem Oracle e Valkey em localhost.
param([Parameter(ValueFromRemainingArguments = $true)][string[]] $MvnArgs)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

$podArgs = @()
podman pod exists sentinela
if ($LASTEXITCODE -eq 0) { $podArgs = @('--pod', 'sentinela') }

$envArgs = @()
$envFile = Join-Path $root '.env.local'
if (Test-Path $envFile) { $envArgs = @('--env-file', $envFile) }

podman run --rm @podArgs @envArgs `
  -v "${root}:/w" -v sentinela-m2:/root/.m2 -w /w `
  docker.io/library/maven:3.9-eclipse-temurin-25 mvn -B @MvnArgs
exit $LASTEXITCODE
