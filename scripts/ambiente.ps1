# Sobe, derruba ou destrói o ambiente local da POC. Idempotente.
#   up      cria/reinicia pod, Oracle e Valkey; cria usuário; constrói a imagem; roda schema
#   down    remove o pod (o volume do Oracle fica)
#   destroy remove o pod e o volume do Oracle
param([ValidateSet('up', 'down', 'destroy')][string] $Acao = 'up')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$envFile = Join-Path $root '.env.local'
$oracleImage = 'container-registry.oracle.com/database/enterprise:latest'
$valkeyImage = 'docker.io/valkey/valkey:7.2'

function Get-EnvValue([string] $name) {
    $line = Get-Content $envFile | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    if (-not $line) { throw "$name ausente em $envFile" }
    return $line.Substring($name.Length + 1)
}

function Test-Podman([string[]] $podmanArgs) {
    & podman @podmanArgs *> $null
    return $LASTEXITCODE -eq 0
}

function Invoke-Podman {
    & podman @args
    if ($LASTEXITCODE -ne 0) { throw "podman $($args -join ' ') falhou com código $LASTEXITCODE" }
}

switch ($Acao) {
    'up' {
        if (-not (Test-Path $envFile)) { throw "Crie $envFile a partir de .env.example" }
        $oraclePwd = Get-EnvValue 'ORACLE_PWD'

        if (-not (Test-Podman @('image', 'exists', $oracleImage))) { Invoke-Podman pull $oracleImage }
        $release = & podman image inspect $oracleImage --format '{{ index .Labels "release" }}'
        if ($release -notlike '23.26.*') { throw "imagem Oracle com release '$release'; esperado 23.26.x" }

        if (-not (Test-Podman @('pod', 'exists', 'sentinela'))) {
            Invoke-Podman pod create --name sentinela -p 1521:1521 -p 6379:6379 | Out-Null
        }
        if (-not (Test-Podman @('volume', 'exists', 'sentinela-oradata'))) {
            Invoke-Podman volume create sentinela-oradata | Out-Null
        }
        if (Test-Podman @('container', 'exists', 'sentinela-oracle')) {
            Invoke-Podman start sentinela-oracle | Out-Null
        } else {
            Invoke-Podman run -d --pod sentinela --name sentinela-oracle `
                -e ORACLE_SID=ORCLCDB -e ORACLE_PDB=SENTINELA -e "ORACLE_PWD=$oraclePwd" `
                -e INIT_SGA_SIZE=4096 -e INIT_PGA_SIZE=2048 -e ORACLE_CHARACTERSET=AL32UTF8 `
                -v sentinela-oradata:/opt/oracle/oradata $oracleImage | Out-Null
        }
        if (Test-Podman @('container', 'exists', 'sentinela-valkey')) {
            Invoke-Podman start sentinela-valkey | Out-Null
        } else {
            Invoke-Podman run -d --pod sentinela --name sentinela-valkey $valkeyImage `
                valkey-server --save '' --appendonly no | Out-Null
        }

        Write-Host 'aguardando o Oracle (a primeira criação leva de 10 a 20 minutos)...'
        $deadline = (Get-Date).AddMinutes(40)
        while ($true) {
            & podman exec sentinela-oracle /opt/oracle/checkDBStatus.sh *> $null
            if ($LASTEXITCODE -eq 0) { break }
            if ((Get-Date) -gt $deadline) { throw 'Oracle não ficou pronto em 40 minutos; veja podman logs sentinela-oracle' }
            Start-Sleep -Seconds 15
        }

        $sql = "DEFINE senha = $(Get-EnvValue 'ORACLE_PASSWORD')`n" + (Get-Content -Raw (Join-Path $root 'db/00_usuario.sql'))
        $sql | podman exec -i sentinela-oracle sqlplus -s '/ as sysdba'
        if ($LASTEXITCODE -ne 0) { throw 'falha ao criar o usuário da POC' }

        Invoke-Podman build -t sentinela-poc $root
        & (Join-Path $PSScriptRoot 'poc.ps1') schema
        if ($LASTEXITCODE -ne 0) { throw 'falha ao criar o schema' }
        Write-Host 'ambiente pronto'
    }
    'down' {
        if (Test-Podman @('pod', 'exists', 'sentinela')) { Invoke-Podman pod rm -f sentinela | Out-Null }
        Write-Host 'pod removido; volume sentinela-oradata mantido'
    }
    'destroy' {
        if (Test-Podman @('pod', 'exists', 'sentinela')) { Invoke-Podman pod rm -f sentinela | Out-Null }
        if (Test-Podman @('volume', 'exists', 'sentinela-oradata')) { Invoke-Podman volume rm -f sentinela-oradata | Out-Null }
        Write-Host 'pod e volume removidos'
    }
}
