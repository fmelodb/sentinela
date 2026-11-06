# Funções comuns dos scripts. Uso: . "$(dirname "$0")/lib.sh"
# Variáveis que podem ser sobrescritas: CONTAINER (podman|docker), COMPOSE ("podman compose", "docker compose").
set -euo pipefail
# Git Bash (Windows) converte argumentos como /opt/... em caminhos C:/...; desliga. No Linux não tem efeito.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT"
ENV_FILE=.env.local  # relativo: o script já está na raiz (caminhos /d/... não servem ao podman.exe)
CONTAINER=${CONTAINER:-podman}
COMPOSE=${COMPOSE:-"$CONTAINER compose"}
IMAGE=localhost/sentinela:latest
ORACLE_IMAGE=container-registry.oracle.com/database/enterprise:latest

die() { echo "erro: $*" >&2; exit 1; }
log() { echo "==> $*" >&2; }

load_env() {
    [ -f "$ENV_FILE" ] || die "crie $ENV_FILE a partir de .env.example"
    # Arquivos editados no Windows podem ter CRLF, que vira parte do valor; normaliza.
    if grep -q $'\r' "$ENV_FILE"; then
        sed -i 's/\r$//' "$ENV_FILE"
        log "$ENV_FILE convertido para fim de linha LF"
    fi
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
}

compose() {
    # shellcheck disable=SC2086
    $COMPOSE --env-file "$ENV_FILE" "$@"
}

# Roda um programa da POC num dos containers: in_api schema / in_sim gerador ...
in_api() { "$CONTAINER" exec sentinela-api sentinela "$@"; }
in_sim() { "$CONTAINER" exec sentinela-sim sentinela "$@"; }

wait_api() {
    local deadline=$((SECONDS + 120))
    until "$CONTAINER" exec sentinela-api python -c \
        "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/health', timeout=3).status == 200 else 1)" \
        >/dev/null 2>&1; do
        [ $SECONDS -lt $deadline ] || die "API não respondeu /health em 120 s; veja: $CONTAINER logs sentinela-api"
        sleep 2
    done
}
