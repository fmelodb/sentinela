#!/usr/bin/env bash
# Testes no container (o host não precisa de Python). Os de integração precisam do Oracle local no ar
# (scripts/ambiente.sh up) e são DESTRUTIVOS: esvaziam TX e DAILY_AGG.
#
#   scripts/test.sh                  todos
#   scripts/test.sh tests/unit       só unitários
#   scripts/test.sh -k backfill      argumentos vão direto para o pytest
. "$(dirname "$0")/lib.sh"
load_env

"$CONTAINER" build -q --target test -t localhost/sentinela-test:latest . >/dev/null
net=$("$CONTAINER" inspect sentinela-api --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' 2>/dev/null || true)
"$CONTAINER" run --rm ${net:+--network "$net"} --env-file "$ENV_FILE" localhost/sentinela-test:latest \
    pytest -q "$@"
