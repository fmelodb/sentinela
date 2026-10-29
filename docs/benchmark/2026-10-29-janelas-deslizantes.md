# Benchmark comparativo — janelas deslizantes ao segundo

29/10/2026 · branch `feat/janelas-deslizantes` · rodadas `20261029-215735` (`oracle`) e `20261029-221522` (`oracle+valkey`)

Primeira medição com a semântica decidida com o cliente (spec funcional, seção 3): janelas de 1m a 72h deslizantes `(now − N, now]` ao microssegundo e janelas de dias com N−1 dias fechados mais o dia corrente até `now`. Não há rodada anterior com a semântica antiga neste ambiente para comparar antes e depois.

## Ambiente e parâmetros

| Item | Valor |
| --- | --- |
| Máquina | Uma VM podman (WSL), 24 CPUs, 64 GB; Oracle, Valkey, Ingestor e Leitor na mesma VM |
| Oracle | 26ai Enterprise Edition, release 23.26.0, SGA 4 GB, PGA 2 GB, 3 grupos de redo de 200 MB |
| Valkey | 7.2, sem persistência |
| Carga | 100 mil contas, distribuição `skewed` (≈50% das transações em 1% das contas), seed 42 |
| Ingestor e Leitor | 600 TPS cada, em paralelo, 300 s, warmup de 30 s descartado, 8 threads |
| Histórico | Backfill de 92 dias: ~1,9 milhão de linhas em `TX` (desde 00:00 de D−3) e 8,2 milhões em `DAILY_AGG` |
| Valkey reconstruído | 9,86 milhões de comandos (transações de 72h nos sorted sets e dias fechados nos hashes) |

Antes das rodadas: 117 testes unitários e 23 de integração passando, e o programa `equivalencia` (1.000 contas, Oracle = Valkey = modelo de referência nas 95 posições) com 7 `PASS`.

## Resultado

Latências em milissegundos, nearest-rank, após o warmup.

| Operação | Medida | `oracle` | `oracle+valkey` |
| --- | --- | ---: | ---: |
| read | TPS alcançado | 600,0 | 600,0 |
| read | avg | 0,5 | 0,4 |
| read | p95 | 0,9 | 0,8 |
| read | p99 | 1,3 | 1,0 |
| read | max | 5,9 | 4,9 |
| ingest_oracle | TPS alcançado | 623,8 | 600,0 |
| ingest_oracle | avg | 8,8 | 5,3 |
| ingest_oracle | p95 | 21,2 | 10,1 |
| ingest_oracle | p99 | 30,7 | 23,0 |
| ingest_oracle | max | 1.002,3 | 2.085,2 |
| ingest_valkey | TPS alcançado | — | 600,0 |
| ingest_valkey | avg / p95 / p99 / max | — | 0,2 / 0,3 / 0,5 / 1,8 |

Zero erros em todas as operações.

## Validade

| Critério | `oracle` | `oracle+valkey` |
| --- | --- | --- |
| Leitor: TPS (desvio < 5%), erro (< 0,1%), fila (≤ 1% de inícios com atraso > 100 ms) | ok, ok, ok (atraso máximo 1 ms) | ok, ok, ok (atraso máximo 1 ms) |
| Ingestor: TPS, erro | ok, ok | ok, ok |
| Ingestor: fila | **não**: 75.467 de 168.421 inícios atrasaram, máximo 23,8 s | **não**: 11.964 de 162.000 inícios atrasaram, máximo 3,0 s |

**A leitura é válida nos dois modos. A ingestão não é válida em nenhum dos dois**, e os números de `ingest_oracle` acima não devem ser usados para concluir nada sobre o custo de gravação. A causa é o ambiente, não o código. O caminho de ingestão no Oracle (um `INSERT` com autocommit) não mudou neste branch, e o alert log mostra:

- Rodada `oracle`: a carga começou às 22:09:58 UTC, dentro da janela de manutenção automática do Oracle (o SQL Tuning Advisor terminou às 22:09:56; SYSTEM e UNDO foram redimensionados às 22:12). O Ingestor ficou em ~400 TPS nos primeiros ~100 s e depois esvaziou a fila a ~1.100 TPS até o segundo 133. Daí em diante, sustentou 600 TPS.
- Rodada `oracle+valkey`: quedas curtas nos segundos 151 e 204, a primeira junto da troca de redo log das 22:31:02. Às 22:33, o Oracle registra "online redo log writes that are taking longer than expected". É o disco virtual do WSL no caminho do commit.

O Leitor não sentiu nenhum dos dois eventos: o atraso máximo de fila foi de 1 ms nas duas rodadas.

## Leitura dos números

1. **Leitura: os dois modos empatam.** A 600 TPS, o Oracle sozinho serve o vetor de 95 features com p99 de 1,3 ms. O Valkey fica em 1,0 ms. A diferença é de 0,3 ms no p99, e ambos ficam muito abaixo de qualquer orçamento razoável de scoring. Com a semântica nova, os dois stores fazem o mesmo trabalho por leitura: varrem as transações da conta em 72h (cerca de 1.000 nas contas quentes) e somam os dias fechados. O Oracle faz isso num `INDEX RANGE SCAN` do índice coberto; o Valkey, num `ZRANGE BYSCORE` mais um `HGETALL`, com agregação em Java.
2. **O Valkey deixou de ter vantagem estrutural.** Na semântica antiga, o Valkey lia fatias pré-agregadas (até 224 campos por conta), e o Oracle varria as transações de 72h. Com precisão ao segundo, o Valkey também precisa guardar e ler as transações individuais.
3. **Custo da dupla ingestão:** gravar no Valkey custa 0,2 ms em média (p99 de 0,5 ms) por transação, além da gravação no Oracle.
4. **O que esta rodada não responde:**
   - o custo de ingestão no Oracle sob carga limpa, por causa da invalidação acima;
   - os números absolutos na OCI, porque tudo roda numa VM só (spec funcional, seção 9).

## Próximos passos sugeridos

- Repetir as duas rodadas fora da janela de manutenção do Oracle, que começa às 22:00 no fuso do banco (UTC) nos dias úteis, ou com o autotask desligado durante a medição.
- Aumentar o redo (por exemplo, 3 grupos de 2 GB) no `scripts/ambiente.ps1`, para que trocas de log e checkpoints não parem o commit no meio da carga.
- Com a ingestão válida, comparar `ingest_oracle` entre os modos e repetir na OCI (ATP-S e OCI Cache).
