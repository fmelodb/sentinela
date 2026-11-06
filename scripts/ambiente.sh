#!/usr/bin/env bash
# Sobe, derruba ou destrói o ambiente da POC. Idempotente.
#
#   scripts/ambiente.sh up          desktop: Oracle local + API + simulador
#   scripts/ambiente.sh up --oci    VM na OCI: API + simulador; o banco é o ATP de ORACLE_DSN
#   scripts/ambiente.sh build       reconstrói a imagem e recria API e simulador (depois de mudar o código)
#   scripts/ambiente.sh down        remove os containers (o volume do Oracle fica)
#   scripts/ambiente.sh destroy     remove os containers e o volume do Oracle
. "$(dirname "$0")/lib.sh"

acao=${1:-up}
oci=0
[ "${2:-}" = "--oci" ] && oci=1

oracle_up() {
    "$CONTAINER" image exists "$ORACLE_IMAGE" 2>/dev/null || "$CONTAINER" pull "$ORACLE_IMAGE"
    release=$("$CONTAINER" image inspect "$ORACLE_IMAGE" --format '{{ index .Labels "release" }}')
    case "$release" in 23.26.*) ;; *) die "imagem Oracle com release '$release'; esperado 23.26.x" ;; esac
    "$CONTAINER" volume exists sentinela-oradata 2>/dev/null || "$CONTAINER" volume create sentinela-oradata >/dev/null
    compose --profile local-db up -d oracle

    log "aguardando o Oracle (a primeira criação leva de 10 a 20 minutos)..."
    local deadline=$((SECONDS + 2400))
    until "$CONTAINER" exec sentinela-oracle /opt/oracle/checkDBStatus.sh >/dev/null 2>&1; do
        [ $SECONDS -lt $deadline ] || die "Oracle não ficou pronto em 40 minutos; veja: $CONTAINER logs sentinela-oracle"
        sleep 15
    done

    log "criando o usuário $ORACLE_USER na PDB SENTINELA"
    { echo "DEFINE senha = $ORACLE_PASSWORD"; cat db/00_usuario.sql; } |
        "$CONTAINER" exec -i sentinela-oracle sqlplus -s '/ as sysdba' >/dev/null ||
        die "falha ao criar o usuário da POC"
}

apps_up() {
    log "construindo $IMAGE"
    "$CONTAINER" build --target base -t "$IMAGE" .
    mkdir -p data logs
    "$CONTAINER" rm -f sentinela-api sentinela-sim >/dev/null 2>&1 || true
    compose up -d api sim
    wait_api
}

case "$acao" in
up)
    load_env
    # O ambiente da versão Java usava um pod com o mesmo nome dos containers.
    if [ "$CONTAINER" = podman ] && podman pod exists sentinela 2>/dev/null; then
        log "removendo o pod 'sentinela' da versão anterior (o volume do Oracle fica)"
        podman pod rm -f sentinela >/dev/null
    fi
    if [ $oci -eq 0 ]; then oracle_up; else log "modo OCI: banco em $ORACLE_DSN"; fi
    apps_up
    log "criando as tabelas"
    in_api schema
    log "ambiente pronto: API em http://localhost:8000 (docs em /docs)"
    ;;
build)
    load_env
    apps_up
    log "API e simulador recriados"
    ;;
down)
    load_env
    compose --profile local-db down
    log "containers removidos; volume sentinela-oradata mantido"
    ;;
destroy)
    load_env
    compose --profile local-db down
    "$CONTAINER" volume rm -f sentinela-oradata >/dev/null 2>&1 || true
    log "containers e volume removidos"
    ;;
*)
    die "uso: $0 up [--oci] | build | down | destroy"
    ;;
esac
