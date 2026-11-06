#!/usr/bin/env bash
# Rodada completa de benchmark:
#   1. gera o dataset se data/tx.ndjson não existir (simulador);
#   2. roda o backfill, que é destrutivo (container da API);
#   3. roda ingestor e leitor em paralelo contra a API (simulador);
#   4. gera o relatório em logs/report/.
#
# Uso: scripts/rodada.sh [--run NOME] [--tps 600] [--duration 300] [--warmup 30] [--accounts 100000]
#                        [--concurrency 64] [--seed 42] [--sem-backfill]
. "$(dirname "$0")/lib.sh"

run=$(date -u +%Y%m%d-%H%M%S)
tps=600 duration=300 warmup=30 accounts=100000 concurrency=64 seed=42 backfill=1
while [ $# -gt 0 ]; do
    case "$1" in
    --run) run=$2; shift 2 ;;
    --tps) tps=$2; shift 2 ;;
    --duration) duration=$2; shift 2 ;;
    --warmup) warmup=$2; shift 2 ;;
    --accounts) accounts=$2; shift 2 ;;
    --concurrency) concurrency=$2; shift 2 ;;
    --seed) seed=$2; shift 2 ;;
    --sem-backfill) backfill=0; shift ;;
    *) die "argumento desconhecido: $1" ;;
    esac
done
load_env

count=$((tps * duration * 11 / 10))
if [ ! -f data/tx.ndjson ] || [ "$(wc -l <data/tx.ndjson)" -lt "$count" ]; then
    log "gerando o dataset ($count transações, $accounts contas)"
    in_sim gerador --accounts "$accounts" --count "$count" --seed "$seed"
fi

if [ $backfill -eq 1 ]; then
    log "backfill ($accounts contas, 92 dias)"
    in_api backfill --accounts "$accounts" --seed "$seed"
fi

log "carga: $tps TPS de ingestão + $tps TPS de leitura por $duration s (run $run)"
status=0
in_sim carga --run "$run" --tps-ingest "$tps" --tps-read "$tps" --duration "$duration" --warmup "$warmup" \
    --accounts "$accounts" --seed "$seed" --concurrency "$concurrency" || status=$?

in_sim relatorio /logs
log "relatório: logs/report/report.md"
exit $status
