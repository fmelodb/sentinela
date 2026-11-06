# Especificação técnica — POC Feature Store de Fraude

v2 · Nov 6, 2026 · @Fernando

Como a [spec funcional](Especificação%20funcional%20—%20POC%20Feature%20Store%20de%20Fraude.md) é implementada. A v1 (Java 25, Valkey, PowerShell) está na tag `java-v1`. O SQL de leitura, o schema, os critérios de validade e a verificação foram portados sem mudança de semântica.

## 1. Stack

| Item | Versão | Uso |
| --- | --- | --- |
| Python | 3.13 (imagem `python:3.13-slim`) | Tudo |
| FastAPI | 0.115.12 | Ingest API e query API |
| uvicorn[standard] | 0.34.2 | Servidor ASGI com uvloop e httptools, N processos |
| python-oracledb | 3.1.1 | Driver em modo thin: sem Instant Client, TLS nativo para o ATP |
| aiohttp | 3.11.18 | Cliente HTTP do simulador e da equivalência |
| orjson | 3.10.18 | JSON das respostas e do dataset |
| numpy | 2.2.6 | Sorteios em massa do gerador e do backfill |
| pytest, pytest-asyncio, httpx | 8.3.5, 0.26.0, 0.28.1 | Testes |

As versões estão fixadas no `pyproject.toml`.

## 2. Layout

```
src/sentinela/
  domain/windows.py     catálogo das 19 janelas e limits(now, zone)
  domain/agg.py         Transaction, WindowAgg, FeatureVector (95 valores, to_json)
  db/read_sql.py        statement único da leitura, binds e tipos, parse da linha de 80 colunas
  db/store.py           AsyncStore: ingest e read no caminho quente (pool assíncrono)
  db/maintenance.py     reset, close_day, check_day, retenção, bulk insert, leitura síncrona, plano
  db/pool.py            conexão síncrona (CLI) e pool assíncrono (API)
  db/plan.py, schema.py conferência do plano; execução de db/01_schema.sql
  api/app.py            FastAPI: create_app(store_factory)
  sim/                  amostradores, dataset, runner open-loop, log CSV, programas de carga
  report/               leitura dos logs, estatísticas e escrita
  verification/         modelo de referência, dados e checagens do cenário C
  backfill.py           gerador do histórico e o programa backfill
  cli.py                ponto de entrada único: sentinela <programa>
db/                     00_usuario.sql (local), 00_usuario_atp.sql (ATP), 01_schema.sql
scripts/                ambiente.sh, rodada.sh, test.sh, lib.sh (bash)
tests/unit, tests/integration
```

## 3. Domínio

Todos os instantes são `datetime` com fuso UTC, com precisão de microssegundo (a mesma de `TIMESTAMP(6)`). `limits(now, zone)` devolve:

- `rolling_lo[i] = now − span_i`: janela deslizante i é `rolling_lo[i] < ts ≤ now`;
- `today`, o dia civil de `now` em `zone`, e `today_start`, a 00:00 local como instante UTC;
- `closed_lo[j] = today − (n_j − 1)`: dias fechados de `closed_lo[j]` a `today − 1`.

`WindowAgg` guarda `cnt`, `sum`, `min` e `max` em centavos inteiros, e `plus` combina dois agregados. `FeatureVector.values()` expande para os 95 valores (`Decimal` para dinheiro; `avg` com 4 casas, `ROUND_HALF_EVEN`). `to_json()` converte para tipos JSON. O `float` de um `Decimal` com 2 ou 4 casas é exato o bastante para ida e volta, e a equivalência compara esses valores.

## 4. Oracle

### 4.1 Schema

`db/01_schema.sql`, igual à v1: `TX` particionada por intervalo diário sobre `ts`, índice local coberto `TX_ACC_TS_AMT_IX (account_id, ts, amount)` e `DAILY_AGG` como IOT com PK `(account_id, dia)`.

### 4.2 Leitura

O SQL é gerado por `db/read_sql.py` a partir do catálogo (explicação em [leitura-oracle.md](../leitura-oracle.md)): CTE `b` com os 20 limites como colunas, agregados condicionais sobre `TX` (15 janelas + dia corrente) e, em `CROSS JOIN`, agregados sobre `DAILY_AGG` (4 janelas de dias). Os binds são posicionais (`:1` … `:26`). Os tipos são fixados com `setinputsizes` (`DB_TYPE_TIMESTAMP` nos instantes, `DB_TYPE_DATE` nos dias), para o cursor ser sempre o mesmo e o driver nunca truncar microssegundos. A aplicação soma o dia corrente aos dias fechados.

O backfill executa uma leitura, acha o cursor em `V$SQL` pelo prefixo `WITH b AS (SELECT :1 AS lo_1m` e confere o plano com `DBMS_XPLAN.DISPLAY_CURSOR`. O plano precisa ter `PARTITION RANGE ITERATOR`, `TX_ACC_TS_AMT_IX` e `DAILY_AGG_PK`, e nenhum `TABLE ACCESS`. Com outro plano, o backfill sai com código 4.

### 4.3 Ingestão

Um `INSERT` com autocommit por requisição. `ORA-00001` (PK de `tx_id`) vira `200 duplicada`.

### 4.4 Conexões

- **API:** um `AsyncConnectionPool` por processo do uvicorn, de tamanho fixo `DB_POOL_SIZE`, aquecido no `lifespan` antes de aceitar requisições, com cache de 20 statements. Sessões no banco: `API_WORKERS × DB_POOL_SIZE`.
- **CLI:** uma conexão síncrona.
- `fetch_decimals = True`: `NUMBER` volta como `Decimal` e é convertido para centavos.
- **ATP:** `ORACLE_DSN` recebe a connect string TLS. O modo thin dispensa a wallet quando o mTLS está desligado.

### 4.5 Manutenção

| Operação | Implementação |
| --- | --- |
| `close_day(d)` | `DELETE` do dia + `INSERT … SELECT … GROUP BY account_id` sobre `[00:00 local de d, 00:00 de d+1)`, numa transação |
| `check_day(d)` | `(armazenado MINUS recálculo) UNION ALL (recálculo MINUS armazenado)`: 0 linhas = igual |
| `drop_old_partitions(today)` | Lê `HIGH_VALUE` de `USER_TAB_PARTITIONS` e remove as partições inteiramente anteriores a `today − 93` |
| `bulk_insert_*` | `executemany` em lotes de 5.000, commit a cada 50.000. Valores em centavos, com divisão por 100 no SQL |
| `reset` | `TRUNCATE TABLE tx DROP ALL STORAGE` e `TRUNCATE TABLE daily_agg` |

## 5. API

`create_app(store_factory)` monta a aplicação. O `store_factory` padrão abre o pool a partir do ambiente. Os testes passam um store em memória, que usa o modelo de referência. Para não serializar duas vezes, as respostas são `Response` com bytes do orjson, e o corpo da ingestão é validado por um modelo pydantic:

| Campo | Regra |
| --- | --- |
| `tx_id` | inteiro ≥ 1 |
| `account_id` | inteiro > 0 |
| `ts` | ISO-8601 **com fuso** (`AwareDatetime`) |
| `amount` | `Decimal` > 0, até 14 dígitos e 2 casas |

O tempo do banco é medido com `perf_counter_ns` em volta da chamada ao store, incluindo a espera por conexão do pool. Ele volta como `Server-Timing: db;dur=<ms com 3 casas>`. Erros `oracledb.Error` viram `503`.

O uvicorn sobe com `--workers $API_WORKERS`, uvloop, httptools e sem access log.

## 6. Simulador

### 6.1 Runner open-loop (`sim/runner.py`)

Um event loop asyncio por processo. O despachante calcula `due_k = start + k·10⁹/tps` (relógio monotônico), dorme até lá, cria o item e o põe numa fila limitada a `tps` itens. `--concurrency` workers consomem a fila. Para cada item, o worker:

1. mede o atraso `início − due` (só depois do warmup) e conta os inícios com atraso > 100 ms;
2. faz a requisição e mede a latência só em volta dela;
3. grava `ts_epoch_ms, program, operation, account_id, latency_us, db_us, status`.

O ingestor carimba `ts = agora` quando o item é criado, no instante agendado. O ingestor e o leitor rodam em **processos separados** (`carga`, multiprocessing `spawn`), cada um com seu GIL e seu event loop. Os dois usam `aiohttp` com keep-alive e limite de conexões igual à concorrência.

### 6.2 Amostradores

As distribuições são as da v1: `skewed` (com probabilidade 0,5, uma das contas quentes `[1, N/100]`) e valores lognormais (mediana R$ 150, σ = 1,2, limitados a R$ 1 e R$ 50.000). Cada propósito tem a sua semente, `sha256(f"{seed}:{propósito}")`. Há uma versão escalar (`random.Random`) e uma vetorizada (`numpy`). Com a mesma seed, os datasets não são idênticos aos da v1, mas as distribuições são as mesmas.

### 6.3 Logs

CSV com buffer em memória, gravado a cada 4.096 linhas. O `.meta.json` ao lado traz run, tps, warmup, concorrência, início, fim e a medição de fila (`measured_starts`, `late_starts`, `max_start_lag_ms`).

## 7. Backfill

1. `reset`.
2. Transações de 00:00 de D-3 até `now`, proporcionais ao tempo decorrido de cada dia (`accounts × tx_per_day` por dia inteiro), com instantes uniformes no dia, em `TX`.
3. `close_day` de D-3 a D-1, a partir de `TX`.
4. Dias D-92 a D-4 sintéticos, direto em `DAILY_AGG`: o numpy agrega cada dia com `bincount` e `minimum.at`/`maximum.at`.
5. `DBMS_STATS.GATHER_TABLE_STATS` nas duas tabelas e conferência do plano (4.2).

## 8. Equivalência (cenário C)

`verification/data.py` gera 93 dias de transações para 1.000 contas em quatro classes (conta mod 4): normal, sem transação na última hora, só hoje, e fronteiras. A classe de fronteiras usa `now − span` e +1 µs, `now` e +1 µs, as 00:00 locais de hoje e de cada primeiro dia fechado e −1 µs, e a 00:00 UTC.

As transações entram pela **ingest API** (32 em voo). O job diário fecha os 92 dias e cada dia é conferido com `check_day`. Os vetores saem da **query API** com `now` fixo e são comparados com `verification/reference.py`, que filtra a lista de transações com os predicados da seção 3 da funcional, sem usar `limits()` nem o SQL. Por fim, a sequência é reaplicada: todas as respostas precisam ser `200 duplicada`, `COUNT(*)` não muda, e os vetores também não.

São 7 verificações, uma linha `PASS`/`FAIL` cada, e o programa sai com 0 se todas passarem ou 1 se alguma falhar.

## 9. Containers e scripts

| Arquivo | Conteúdo |
| --- | --- |
| `Containerfile` | Alvo `base` (runtime, `CMD sentinela api`) e alvo `test` (base + extras de teste + `tests/`) |
| `compose.yaml` | `oracle` (profile `local-db`, volume externo `sentinela-oradata`), `api` (porta 8000, `cpus: $API_CPUS`), `sim` (`sleep infinity`, `./data` e `./logs` montados, `cpus: $SIM_CPUS`) |
| `scripts/lib.sh` | Carrega `.env.local` (normaliza CRLF), funções `compose`, `in_api`, `in_sim`, `wait_api` |
| `scripts/ambiente.sh` | `up [--oci]`, `build`, `down`, `destroy` |
| `scripts/rodada.sh` | Gerador (se faltar), backfill, carga e relatório |
| `scripts/test.sh` | Constrói o alvo `test` e roda o pytest na rede do compose |

`CONTAINER=docker` e `COMPOSE="docker compose"` trocam o podman pelo Docker.

## 10. Testes

| Suíte | Conteúdo |
| --- | --- |
| `tests/unit` | Domínio e limites ao microssegundo, SQL (placeholders × binds × tipos, parse), plano, schema, amostradores, dataset, backfill, runner (TPS, fila, erros), relatório, referência e dados do cenário C, API com store em memória (contrato HTTP, 201/200/422) |
| `tests/integration` | Contra o Oracle local: ingestão idempotente, leitura nas fronteiras igual à referência, job diário + `check_day`, retenção de partições, backfill pequeno e plano legível, API com pool real |

Os testes de integração são destrutivos. Só rodam se o host do `ORACLE_DSN` for local (`oracle`, `localhost`) ou com `FS_IT_DESTRUCTIVE=1`.

## 11. Riscos e pontos de atenção

- **CPU do Python:** a 600 + 600 TPS, a API gasta CPU com pydantic, JSON e o driver. Se o relatório mostrar HTTP muito acima de DB, ou fila no simulador, aumente `API_WORKERS` e `API_CPUS` antes de concluir algo sobre o banco.
- **Sessões no ATP:** `API_WORKERS × DB_POOL_SIZE` sessões abertas o tempo todo. Ajuste ao limite de sessões do ATP.
- **Relógio:** o ingestor carimba `ts` no relógio do simulador, e a query API usa o relógio da API. Na mesma VM eles coincidem. Com o simulador em outra VM, mantenha o NTP ativo.
- **Janela de manutenção do banco:** igual à v1. No desktop, evite rodar às 22:00 UTC. No ATP, registre o horário da rodada.
