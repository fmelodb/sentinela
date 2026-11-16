#!/usr/bin/env bash
# Rodada completa de benchmark das 10 variáveis:
#   1. gera data/{trx,abr,vu}.ndjson se faltarem eventos (simulador);
#   2. roda o backfill de 92 dias das três fontes, que é destrutivo e confere o plano (container da API);
#   3. roda os ingestores de TRX, ABR e VU e o leitor em paralelo contra a API (simulador);
#   4. gera o relatório em logs/report/.
#
# Uso: scripts/rodada.sh [--run NOME] [--tps-trx 600] [--tps-abr 200] [--tps-vu 100] [--tps-read 600]
#                        [--fracao-get 0.5] [--duration 300] [--warmup 30] [--clientes 100000]
#                        [--trx-dia 1] [--abr-dia 0.5] [--vu-dia 0.3] [--concurrency 64] [--seed 42]
#                        [--sem-backfill]
# Para o Oracle local com outro arquivo de ambiente: ENV_FILE=.env.podman scripts/rodada.sh ...
. "$(dirname "$0")/lib.sh"

run=$(date -u +%Y%m%d-%H%M%S)
tps_trx=600 tps_abr=200 tps_vu=100 tps_read=600 fracao_get=0.5 duration=300 warmup=30 clientes=100000
trx_dia=1 abr_dia=0.5 vu_dia=0.3 concurrency=64 seed=42 backfill=1
while [ $# -gt 0 ]; do
    case "$1" in
    --run) run=$2; shift 2 ;;
    --tps-trx) tps_trx=$2; shift 2 ;;
    --tps-abr) tps_abr=$2; shift 2 ;;
    --tps-vu) tps_vu=$2; shift 2 ;;
    --tps-read) tps_read=$2; shift 2 ;;
    --fracao-get) fracao_get=$2; shift 2 ;;
    --duration) duration=$2; shift 2 ;;
    --warmup) warmup=$2; shift 2 ;;
    --clientes) clientes=$2; shift 2 ;;
    --trx-dia) trx_dia=$2; shift 2 ;;
    --abr-dia) abr_dia=$2; shift 2 ;;
    --vu-dia) vu_dia=$2; shift 2 ;;
    --concurrency) concurrency=$2; shift 2 ;;
    --seed) seed=$2; shift 2 ;;
    --sem-backfill) backfill=0; shift ;;
    *) die "argumento desconhecido: $1" ;;
    esac
done
load_env

lines() { if [ -f "data/$1.ndjson" ]; then wc -l <"data/$1.ndjson"; else echo 0; fi; }
n_trx=$((tps_trx * duration * 11 / 10)) n_abr=$((tps_abr * duration * 11 / 10)) n_vu=$((tps_vu * duration * 11 / 10))
if [ "$(lines trx)" -lt "$n_trx" ] || [ "$(lines abr)" -lt "$n_abr" ] || [ "$(lines vu)" -lt "$n_vu" ]; then
    log "gerando os datasets ($n_trx TRX, $n_abr ABR, $n_vu VU; $clientes clientes)"
    in_sim gerador --dir /data --clientes "$clientes" --seed "$seed" --trx "$n_trx" --abr "$n_abr" --vu "$n_vu"
fi

if [ $backfill -eq 1 ]; then
    log "backfill ($clientes clientes, 92 dias)"
    in_api backfill --clientes "$clientes" --seed "$seed" --trx-dia "$trx_dia" --abr-dia "$abr_dia" --vu-dia "$vu_dia"
fi

log "carga: $tps_trx TRX + $tps_abr ABR + $tps_vu VU por s; $tps_read leituras por s ($fracao_get por GET); $duration s (run $run)"
status=0
in_sim carga --run "$run" --dir /data --tps-trx "$tps_trx" --tps-abr "$tps_abr" --tps-vu "$tps_vu" \
    --tps-read "$tps_read" --fracao-get "$fracao_get" --duration "$duration" --warmup "$warmup" \
    --clientes "$clientes" --seed "$seed" --concurrency "$concurrency" || status=$?

in_sim relatorio /logs
log "relatório: logs/report/report.md"
exit $status
