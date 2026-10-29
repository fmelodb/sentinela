# Especificação técnica — POC Feature Store de Fraude

Oct 29, 2026 · @Fernando

Base: [Especificação funcional — POC Feature Store de Fraude](./Especificação%20funcional%20—%20POC%20Feature%20Store%20de%20Fraude.md). Esta spec não repete o que a funcional já fixa (regras das features, modelo lógico, parâmetros, cenários). Ela diz como construir.

## 1. Escopo e relação com a funcional

A spec técnica responde às decisões que a funcional delegou e fixa o desenho de código, SQL, scripts Valkey, gerador de carga, logs e ambiente. As premissas da seção 11 da funcional valem sem rediscussão.

### 1.1 Decisões que a funcional delegou

| Questão da funcional | Decisão | Motivo |
| --- | --- | --- |
| Versão LTS do Java | Java 25 (Eclipse Temurin) | LTS corrente; records, sealed e `SplittableRandom` bastam, sem dependência extra |
| Ingestão atômica no Valkey: Lua ou Functions | Script Lua via `EVALSHA`, recarregado com `SCRIPT LOAD` se o servidor responder `NOSCRIPT` | O Valkey roda sem persistência, então Functions também somem no restart; Lua tem o mesmo custo e menos cerimônia |
| Leitura no Valkey: no cliente Java ou em Lua | `HGETALL` e combinação das fatias em Java, pelo `SliceAggregator` do domínio | Mantém a definição das janelas só no domínio, como exige a seção 4 da funcional. Revisitar só se o payload dominar o p99 (seção 13) |

### 1.2 Decisões tomadas nesta spec

| Tema | Decisão |
| --- | --- |
| Fuso do dia civil | `America/Sao_Paulo`, configurável por `FS_ZONE`. Instantes são gravados em UTC; só o corte de dia usa o fuso |
| Valores monetários | Centavos inteiros (`long`) no domínio e no Valkey; `NUMBER(14,2)` no Oracle. Nenhum `double` em lugar nenhum |
| Build | Maven, módulo único, fat jar; cada programa é um subcomando |

### 1.3 Acréscimos e desvios em relação à funcional

Tudo o que esta spec acrescenta ou ajusta, para revisão explícita:

| # | Item | Funcional | Esta spec | Motivo |
| --- | --- | --- | --- | --- |
| 1 | Valor da fatia no Valkey | `3\|450.00\|20.00\|300.00` | `3\|45000\|2000\|30000` (centavos) | Aritmética exata em Lua (só tem `double`) e igualdade bit a bit no cenário C |
| 2 | Colunas de `DAILY_AGG` | `cnt`, `sum`, `min`, `max` | `cnt`, `sum_amount`, `min_amount`, `max_amount` | Evitar nomes de funções SQL como colunas |
| 3 | Índice de `TX` | `(account_id, ts)` | `(account_id, ts, amount)` local | Índice coberto: a leitura não acessa a tabela |
| 4 | Organização de `DAILY_AGG` | PK `(account_id, dia)` | Mesma PK, tabela index-organized | As 92 linhas de uma conta ficam contíguas na PK |
| 5 | Leitura Oracle | Um SELECT em `TX` mais um em `DAILY_AGG` | Um único statement com as duas agregações em `CROSS JOIN` | Uma ida ao banco por leitura, conforme a premissa "um `SELECT` por leitura" |
| 6 | Programas | Seis | Oito: acrescenta `schema` e `equivalencia` | Criar o schema pelo mesmo caminho local e na OCI; tornar o cenário C e os critérios funcionais executáveis |
| 7 | Parâmetros novos | — | `--count` (Gerador), `--run` (Ingestor, Leitor), `--tx-per-day` e `--distribution` (Backfill), `--now` e `--reset` (Equivalência) | Ver seção 9 |
| 8 | Metadados do log | Só o CSV | CSV com linha de cabeçalho de colunas + arquivo `.meta.properties` ao lado | O Relatório recebe só o diretório e precisa de warmup, TPS alvo e início |
| 9 | Backfill | "Popula histórico" | Destrutivo: esvazia `TX`, `DAILY_AGG` e o Valkey antes de popular | Rodadas comparáveis entre modos |
| 10 | Janela de `TX` no Backfill | Últimas 72h | De 00:00 (local) de D-3 até o instante do Backfill | Cobre 72h e permite que `DAILY_AGG` de D-3 a D-1 saia de `TX` pelo SQL do Job |
| 11 | Retenção de `TX` | `DROP PARTITION` | Executado pelo Job diário | A funcional não diz quem faz |
| 12 | Variável de ambiente | — | `FS_ZONE` | Fuso do dia civil |
| 13 | Banco local | Oracle Database 26ai Free | Oracle Database 26ai Enterprise Edition (`container-registry.oracle.com/database/enterprise`), PDB `SENTINELA` | Decisão do usuário: sem os limites de 2 CPUs, 2 GB e 12 GB do Free, e mais perto do motor do ATP-S |

## 2. Stack e versões

| Item | Escolha | Observação |
| --- | --- | --- |
| Linguagem | Java 25 | `--release 25` no compilador |
| Build | Maven 3.9, `maven-shade-plugin` | Gera `target/sentinela.jar` |
| Oracle | `com.oracle.database.jdbc:ojdbc17` e `ucp17`, linha 23.26.x | Linha do cliente que acompanha o 26ai |
| Valkey | `io.lettuce:lettuce-core`, última 6.x estável | Cliente da premissa da funcional |
| NDJSON | `com.fasterxml.jackson.core:jackson-core` (só o streaming) | Sem databind |
| Testes | JUnit 5 | Testes de integração ficam separados por tag (seção 12) |
| CLI | Parser próprio de `--chave valor` | Sem picocli; cerca de 60 linhas, com mensagem de uso e validação de tipos |

Sem framework de aplicação, sem DI, sem logging framework: erros e progresso vão para `stderr` com `System.err`, e a evidência do benchmark é o CSV. Todas as versões ficam fixadas no `pom.xml`; nenhuma faixa (`[6,)`) nem `LATEST`.

## 3. Layout do repositório e do código

```
pom.xml
Containerfile
db/
  00_usuario.sql            -- executado pelo DBA/ADMIN: cria o usuário da POC
  01_schema.sql             -- executado pelo programa `schema`
scripts/
  ambiente.ps1              -- sobe/derruba o pod
  rodada.ps1                -- uma rodada de benchmark em um modo
src/main/java/sentinela/
  domain/                   -- janelas, métricas, vetor, port. Sem dependência externa
  adapter/oracle/           -- adapter do port + manutenção Oracle (job, backfill, reset)
  adapter/valkey/           -- adapter do port + manutenção Valkey
  workload/                 -- amostrador de contas, amostrador de valores, gerador de carga
  infra/                    -- args, env, log CSV, sidecar .meta
  verification/             -- modelo de referência em memória e checagem de equivalência
  app/                      -- um main por programa e a fábrica de modos
src/main/resources/lua/
  ingest.lua
  put_day.lua
src/test/java/sentinela/...
data/  logs/                -- fora do git
```

Regras de dependência entre pacotes:

| Pacote | Pode depender de |
| --- | --- |
| `domain` | Só do JDK |
| `adapter.*` | `domain`, `infra`, driver do respectivo store |
| `workload` | `domain`, `infra` |
| `verification` | `domain`, `adapter.*` |
| `app` | Todos |

Não há verificação automática de dependência (ArchUnit seria cerimônia para quatro pacotes). A regra se confere na revisão de código.

O ponto de entrada é `sentinela.app.Main`. O primeiro argumento escolhe o programa: `gerador`, `ingestor`, `leitor`, `backfill`, `job-diario`, `relatorio`, `equivalencia` ou `schema`.

### 3.1 Fábrica de modos

`app.Modes.build(mode, env)` devolve:

```java
record ModeWiring(
    List<NamedSink> ingestSinks,   // oracle: [ingest_oracle]; oracle+valkey: [ingest_oracle, ingest_valkey]
    FeatureStorePort reader,        // oracle: Oracle; oracle+valkey: Valkey
    AutoCloseable resources) {}

record NamedSink(String operation, FeatureStorePort port) {}
```

Não existe adapter composto. O Ingestor chama cada sink da lista em sequência e mede cada chamada separadamente, o que produz as duas linhas de log por transação que a seção 8 da funcional exige. Uma falha no sink Oracle não impede a chamada ao sink Valkey: cada um é registrado com o próprio `status`, como fariam dois consumer groups independentes.

## 4. Domínio

### 4.1 Tipos

```java
public record Transaction(long txId, long accountId, Instant ts, long amountCents) {}

public interface FeatureStorePort {
    void ingest(Transaction tx);
    FeatureVector read(long accountId, Instant now);
}

public enum Unit { MINUTE, HOUR, DAY }

public record Window(String suffix, Unit unit, int n) {}       // ("5m", MINUTE, 5), ("1h", MINUTE, 60), ("24h", HOUR, 24), ("31d", DAY, 31)

public record Bounds(Unit unit, long lo, long hi) {}          // índices epoch da unidade, ambos inclusivos

public record WindowAgg(long cnt, long sumCents, Long minCents, Long maxCents) {
    public static final WindowAgg EMPTY = new WindowAgg(0, 0, null, null);
    public WindowAgg plus(WindowAgg o) { ... }                 // soma, soma, menor, maior
}

public record Slice(Unit unit, long index, WindowAgg agg) {}
```

O port não lê relógio. Quem decide o instante é o chamador: o Ingestor monta a `Transaction` com `ts = clock.instant()`, o Leitor chama `read(conta, clock.instant())`, a Equivalência passa um instante lógico fixo. Os adapters nunca chamam `Instant.now()` nem `SYSTIMESTAMP`.

Operações de manutenção (Backfill, Job diário, reset) não fazem parte do port. Elas ficam em classes próprias de cada adapter (`OracleMaintenance`, `ValkeyMaintenance`), que os programas usam diretamente.

### 4.2 Catálogo de janelas

`WindowCatalog` é a única implementação da regra de alinhamento da seção 3 da funcional. Contém as 19 janelas em ordem fixa:

| # | Sufixos | Unidade | `n` |
| --- | --- | --- | --- |
| 0–4 | `1m`, `5m`, `10m`, `15m`, `30m` | MINUTE | 1, 5, 10, 15, 30 |
| 5 | `1h` | MINUTE | 60 |
| 6–14 | `2h`, `3h`, `5h`, `6h`, `9h`, `12h`, `24h`, `48h`, `72h` | HOUR | 2 … 72 |
| 15–18 | `7d`, `31d`, `62d`, `92d` | DAY | 7, 31, 62, 92 |

`WindowCatalog.bounds(Instant now, ZoneId zone)` devolve um `Bounds` por janela:

| Unidade | Índice corrente | `lo` | `hi` |
| --- | --- | --- | --- |
| MINUTE | `M = floorDiv(now.epochSecond, 60)` | `M - (n - 1)` | `M` |
| HOUR | `H = floorDiv(now.epochSecond, 3600)` | `H - (n - 1)` | `H` |
| DAY | `T = LocalDate.ofInstant(now, zone).toEpochDay()` | `T - n` | `T - 1` |

Minutos e horas são alinhados em UTC. Para fusos de hora cheia, como `America/Sao_Paulo` (UTC−3, sem horário de verão desde 2019), a fatia de hora UTC coincide com a hora local. Só o dia depende do fuso.

### 4.3 Vetor de features

`FeatureVector` guarda 19 `WindowAgg`. Expõe:

| Método | Devolve |
| --- | --- |
| `agg(i)` | O `WindowAgg` da janela `i` |
| `values()` | 95 `BigDecimal` anuláveis, na ordem `cnt`, `sum`, `min`, `max`, `avg` por janela |
| `names()` (estático) | Os 95 nomes `<metrica>_<sufixo>`, por exemplo `cnt_5m`, `avg_92d` |

Representação dos valores em `values()`: `cnt` com escala 0; `sum`, `min` e `max` em reais com escala 2 (`BigDecimal.valueOf(cents, 2)`); `avg = sumCents / (cnt × 100)` com escala 4 e `RoundingMode.HALF_EVEN`. Quando `cnt = 0`, `sum = 0` e `min`, `max`, `avg` são nulos.

Igualdade de vetores (cenário C) compara os 19 `WindowAgg`, que são inteiros exatos. O `avg` é derivado sempre pela mesma função, então não precisa de comparação própria.

### 4.4 Combinação de fatias

`SliceAggregator.aggregate(Iterable<Slice> slices, List<Bounds> bounds)` devolve o `FeatureVector`. Para cada janela, soma (`WindowAgg.plus`) as fatias da mesma unidade com `lo ≤ index ≤ hi`. Fatias fora de qualquer limite, inclusive as do futuro, são ignoradas. São no máximo 224 fatias × 19 janelas de comparação: não vale otimizar.

## 5. Adapter Oracle

### 5.1 DDL (`db/01_schema.sql`)

```sql
CREATE TABLE IF NOT EXISTS tx (
  tx_id       NUMBER(19)    NOT NULL,
  account_id  NUMBER(10)    NOT NULL,
  ts          TIMESTAMP(6)  NOT NULL,      -- UTC
  amount      NUMBER(14,2)  NOT NULL,
  CONSTRAINT tx_pk PRIMARY KEY (tx_id)
)
PARTITION BY RANGE (ts) INTERVAL (NUMTODSINTERVAL(1, 'DAY'))
( PARTITION p_inicial VALUES LESS THAN (TIMESTAMP '2000-01-01 00:00:00') );

CREATE INDEX IF NOT EXISTS tx_acc_ts_amt_ix ON tx (account_id, ts, amount) LOCAL;

CREATE TABLE IF NOT EXISTS daily_agg (
  account_id  NUMBER(10)    NOT NULL,
  dia         DATE          NOT NULL,      -- dia civil em FS_ZONE, 00:00
  cnt         NUMBER(10)    NOT NULL,
  sum_amount  NUMBER(18,2)  NOT NULL,
  min_amount  NUMBER(14,2)  NOT NULL,
  max_amount  NUMBER(14,2)  NOT NULL,
  CONSTRAINT daily_agg_pk PRIMARY KEY (account_id, dia)
)
ORGANIZATION INDEX;
```

Notas:

- A PK `tx_pk` é um índice global (não contém a chave de partição). `DROP PARTITION` usa `UPDATE GLOBAL INDEXES`, que no 26ai é manutenção assíncrona e não bloqueia.
- O índice local coberto `(account_id, ts, amount)` deixa a leitura em `INDEX RANGE SCAN` sem `TABLE ACCESS BY ROWID`. IOT em `TX` seria a alternativa natural, mas o Oracle não suporta interval partitioning em IOT.
- `p_inicial` existe só porque a partição de transição de um interval não pode ser removida. Ela fica sempre vazia.
- As fronteiras das partições são dias UTC. O dia civil em `America/Sao_Paulo` cruza duas partições, o que não muda a poda: as 72h da leitura tocam no máximo 4 partições em qualquer fuso.

`db/00_usuario.sql` cria o usuário da POC com `DB_DEVELOPER_ROLE` e `QUOTA UNLIMITED ON USERS`. Localmente é executado pelo `scripts/ambiente.ps1` via `sqlplus` dentro do container. Na OCI, o ADMIN do ATP-S executa uma vez.

### 5.2 Conversão de instantes

A JVM roda com `-Duser.timezone=UTC`. Instantes são enviados ao JDBC como `LocalDateTime.ofInstant(i, ZoneOffset.UTC)` via `setObject`, e dias como `LocalDate`. Nunca `java.sql.Timestamp` com fuso implícito.

| Bound do domínio | Valor no SQL |
| --- | --- |
| MINUTE `lo` | `epochSecond = lo × 60` |
| HOUR `lo` | `epochSecond = lo × 3600` |
| Limite superior de `TX` | `(M + 1) × 60`, exclusivo (fim do minuto corrente) |
| DAY `lo`, `hi` | `LocalDate.ofEpochDay(lo)`; o superior é `< LocalDate.ofEpochDay(T)` |

### 5.3 Leitura

Um único statement por leitura. O texto do SQL é gerado uma vez, no construtor do adapter, percorrendo o `WindowCatalog`. Os limites de cada janela entram como colunas de uma linha `b`. Os predicados de poda (`account_id`, faixa de `ts`, faixa de `dia`) usam binds diretos no `WHERE`, para garantir partition pruning `KEY:KEY`.

```sql
WITH b AS (
  SELECT ? AS lo_1m, ? AS lo_5m, ? AS lo_10m, ? AS lo_15m, ? AS lo_30m, ? AS lo_1h,
         ? AS lo_2h, ? AS lo_3h, ? AS lo_5h, ? AS lo_6h, ? AS lo_9h, ? AS lo_12h,
         ? AS lo_24h, ? AS lo_48h, ? AS lo_72h,
         ? AS lo_7d, ? AS lo_31d, ? AS lo_62d, ? AS lo_92d
  FROM dual
)
SELECT t.*, d.*
FROM (
  SELECT
    COUNT(CASE WHEN x.ts >= b.lo_1m THEN 1 END)        AS cnt_1m,
    SUM  (CASE WHEN x.ts >= b.lo_1m THEN x.amount END) AS sum_1m,
    MIN  (CASE WHEN x.ts >= b.lo_1m THEN x.amount END) AS min_1m,
    MAX  (CASE WHEN x.ts >= b.lo_1m THEN x.amount END) AS max_1m,
    -- … repetido para as 14 janelas seguintes, de 5m a 72h
  FROM tx x CROSS JOIN b
  WHERE x.account_id = ? AND x.ts >= ? AND x.ts < ?        -- lo_72h e fim do minuto corrente
) t
CROSS JOIN (
  SELECT
    SUM(CASE WHEN a.dia >= b.lo_7d THEN a.cnt END)        AS cnt_7d,
    SUM(CASE WHEN a.dia >= b.lo_7d THEN a.sum_amount END) AS sum_7d,
    MIN(CASE WHEN a.dia >= b.lo_7d THEN a.min_amount END) AS min_7d,
    MAX(CASE WHEN a.dia >= b.lo_7d THEN a.max_amount END) AS max_7d,
    -- … repetido para 31d, 62d e 92d
  FROM daily_agg a CROSS JOIN b
  WHERE a.account_id = ? AND a.dia >= ? AND a.dia < ?      -- lo_92d e hoje
) d
```

São 19 + 6 binds, gerados no mesmo laço que gera o texto. O resultado é uma linha com 76 colunas. O mapeamento para `WindowAgg`: `COUNT` → `cnt`; `SUM` nulo vira 0; `SUM`, `MIN` e `MAX` são convertidos para centavos com `movePointRight(2).longValueExact()`; `SUM(cnt)` nulo nas janelas de dia vira 0.

Plano esperado, conferido automaticamente pelo Backfill depois da coleta de estatísticas (uma leitura real e `DBMS_XPLAN.DISPLAY_CURSOR`): `PARTITION RANGE ITERATOR` sobre `INDEX RANGE SCAN TX_ACC_TS_AMT_IX`, sem acesso à tabela, mais `INDEX RANGE SCAN DAILY_AGG_PK`. Se o plano for outro, o Backfill imprime o plano e sai com código 4 (rodada inválida).

### 5.4 Ingestão

```sql
INSERT INTO tx (tx_id, account_id, ts, amount) VALUES (?, ?, ?, ?)
```

Uma linha por chamada, com autocommit. `SQLIntegrityConstraintViolationException` com `ORA-00001` é tratada como sucesso, o que dá a idempotência por `tx_id` da funcional. O adapter conta essas duplicatas e o programa imprime o total em `stderr` ao final.

### 5.5 Pool (UCP)

| Propriedade | Valor | Motivo |
| --- | --- | --- |
| `initialPoolSize` = `minPoolSize` = `maxPoolSize` | `--threads + 2` | Pool pronto antes de o relógio do benchmark começar; sem crescimento durante a medição |
| `validateConnectionOnBorrow` | `false` | Tira uma ida ao banco do caminho quente |
| `oracle.jdbc.implicitStatementCacheSize` | 20 | O statement de leitura e o de insert ficam parseados por conexão |
| `oracle.jdbc.ReadTimeout` | 5000 ms | Uma operação travada vira `error`, em vez de travar o worker |
| `oracle.jdbc.defaultRowPrefetch` | 1 na leitura, 1000 no Backfill | A leitura devolve uma linha só |

### 5.6 Manutenção Oracle (`OracleMaintenance`)

| Operação | SQL | Usada por |
| --- | --- | --- |
| `closeDay(D)` | Numa transação: `DELETE FROM daily_agg WHERE dia = :D`; `INSERT INTO daily_agg SELECT account_id, :D, COUNT(*), SUM(amount), MIN(amount), MAX(amount) FROM tx WHERE ts >= :ini AND ts < :fim GROUP BY account_id` | Job diário, Backfill, Equivalência |
| `checkDay(D)` | Diferença simétrica (`MINUS` nos dois sentidos) entre `DAILY_AGG` do dia e o recálculo sobre `TX`; espera 0 linhas | Equivalência |
| `dropOldPartitions(hoje)` | Para cada partição com `partition_position > 1` e limite superior ≤ início UTC de `hoje − 93`: `ALTER TABLE tx DROP PARTITION <nome> UPDATE GLOBAL INDEXES`. O limite vem de `USER_TAB_PARTITIONS.HIGH_VALUE` (coluna LONG, lida como texto pelo JDBC) | Job diário |
| `reset()` | `TRUNCATE TABLE tx DROP ALL STORAGE`; `TRUNCATE TABLE daily_agg` | Backfill, Equivalência |
| `gatherStats()` | `DBMS_STATS.GATHER_TABLE_STATS` em `TX` e `DAILY_AGG` | Backfill, ao final |

`:ini` e `:fim` são o início do dia `D` e do dia `D+1` em `FS_ZONE`, convertidos para UTC. Delete + insert numa transação torna o Job idempotente: reexecutar o mesmo dia produz o mesmo resultado.

## 6. Adapter Valkey

### 6.1 Chave e valores

Conforme a funcional: chave `fs:{<account_id>}` sem TTL; campos `m:<epoch_minuto>`, `h:<epoch_hora>`, `d:<epoch_dia>`. Valor `cnt|sum|min|max` em centavos inteiros (desvio 1 da seção 1.3). `epoch_dia` é `LocalDate.toEpochDay()` do dia civil em `FS_ZONE`.

### 6.2 Ingestão: `ingest.lua`

Chamado com `EVALSHA`. `KEYS[1] = fs:{conta}`. ARGV: minuto da transação `M`, hora `H`, valor em centavos, menor minuto retido `M − 59`, menor hora retida `H − 71`. Todos os índices vêm de `tx.ts`, nunca do relógio do servidor.

```lua
local key, amt = KEYS[1], tonumber(ARGV[3])

local function merge(field)
  local cur = redis.call('HGET', key, field)
  if not cur then
    redis.call('HSET', key, field, '1|' .. ARGV[3] .. '|' .. ARGV[3] .. '|' .. ARGV[3])
    return true
  end
  local c, s, mn, mx = string.match(cur, '^(%d+)|(%d+)|(%d+)|(%d+)$')
  redis.call('HSET', key, field, string.format('%d|%d|%d|%d',
    tonumber(c) + 1, tonumber(s) + amt, math.min(tonumber(mn), amt), math.max(tonumber(mx), amt)))
  return false
end

local newMinute = merge('m:' .. ARGV[1])
merge('h:' .. ARGV[2])

if newMinute then
  local minM, minH = tonumber(ARGV[4]), tonumber(ARGV[5])
  for _, f in ipairs(redis.call('HKEYS', key)) do
    local kind, idx = string.sub(f, 1, 1), tonumber(string.sub(f, 3))
    if (kind == 'm' and idx < minM) or (kind == 'h' and idx < minH) then
      redis.call('HDEL', key, f)
    end
  end
end
return 0
```

A limpeza com `HKEYS` roda só quando a fatia de minuto é nova, ou seja, no máximo uma vez por minuto por conta. Nas outras transações do mesmo minuto o script faz dois `HGET` e dois `HSET`. A retenção é só uma questão de memória: a leitura filtra por limite de qualquer jeito, então um campo antigo que sobreviva não altera o vetor.

Limite numérico: o Lua do Valkey representa números como `double`, exato até 2^53 centavos (cerca de 90 trilhões de reais por fatia). `%d` formata inteiros sem notação exponencial.

### 6.3 Fatia de dia: `put_day.lua`

`KEYS[1] = fs:{conta}`. ARGV: `epoch_dia`, valor `cnt|sum|min|max`, menor dia retido (`T − 92`).

```lua
redis.call('HSET', KEYS[1], 'd:' .. ARGV[1], ARGV[2])
local minD = tonumber(ARGV[3])
for _, f in ipairs(redis.call('HKEYS', KEYS[1])) do
  if string.sub(f, 1, 2) == 'd:' and tonumber(string.sub(f, 3)) < minD then
    redis.call('HDEL', KEYS[1], f)
  end
end
return 0
```

Sobrescreve em vez de combinar: o valor vem de `DAILY_AGG`, que já é o total do dia. Por isso reexecutar o Job é idempotente também no Valkey.

### 6.4 Leitura

`HGETALL fs:{conta}`. Cada campo vira um `Slice` (parse manual de `m:`/`h:`/`d:` e dos quatro inteiros separados por `|`, sem regex). O `SliceAggregator` do domínio combina as fatias com os `Bounds` de `now`. Chave inexistente produz o vetor vazio.

### 6.5 Conexão (Lettuce)

| Item | Valor |
| --- | --- |
| Conexões no caminho quente | Uma `StatefulRedisConnection<String,String>` compartilhada por todos os workers (o Lettuce multiplexa comandos de vários threads numa conexão), API síncrona |
| Timeout de comando | 2 s |
| Scripts | `SCRIPT LOAD` dos dois scripts na inicialização, antes de o relógio começar. `NOSCRIPT` dispara um novo `SCRIPT LOAD` e uma nova tentativa |
| Carga em massa (Backfill, Job, Equivalência) | Uma segunda conexão, só para isso, com API assíncrona, `setAutoFlushCommands(false)` e `flushCommands()` a cada 1.000 comandos, aguardando os futures do lote |

### 6.6 Manutenção Valkey (`ValkeyMaintenance`)

| Operação | Como |
| --- | --- |
| `reset()` | `FLUSHDB`. O Valkey da POC é dedicado |
| `putDays(D, linhas, hoje)` | `put_day.lua` para cada linha de `DAILY_AGG` do dia `D`, em lote assíncrono. Ignora `D < hoje − 92` |
| `rebuildFromOracle(now)` | Três consultas com uma passada só cada (abaixo), gravadas com `HSET` em lote assíncrono, sem Lua: logo depois do `FLUSHDB` não há nada para combinar |

Consultas da reconstrução (com `now` do Backfill):

```sql
SELECT account_id, TRUNC(ts, 'MI') b, COUNT(*), SUM(amount), MIN(amount), MAX(amount)
FROM tx WHERE ts >= :lo_1h GROUP BY account_id, TRUNC(ts, 'MI');

SELECT account_id, TRUNC(ts, 'HH') b, COUNT(*), SUM(amount), MIN(amount), MAX(amount)
FROM tx WHERE ts >= :lo_72h GROUP BY account_id, TRUNC(ts, 'HH');

SELECT account_id, dia, cnt, sum_amount, min_amount, max_amount
FROM daily_agg WHERE dia >= :lo_92d AND dia < :hoje;
```

`TRUNC` de um `TIMESTAMP` em UTC devolve um `DATE` em UTC. O Java converte esse valor para o índice epoch da unidade.

## 7. Fluxo de uma operação

**Ingestão, modo `oracle+valkey`:** o Ingestor tira a próxima transação do dataset e define `ts = clock.instant()`. Em seguida chama `oracle.ingest(tx)` e registra a linha `ingest_oracle`, depois chama `valkey.ingest(tx)` e registra a linha `ingest_valkey`. No modo `oracle`, só o primeiro passo acontece.

**Leitura:** o Leitor sorteia a conta e define `now = clock.instant()`. Chama `reader.read(conta, now)`, que devolve o vetor de 95 valores, e registra a linha `read`. Dentro do adapter: o `WindowCatalog.bounds(now)` gera os limites, que viram binds (Oracle) ou filtro sobre as fatias (Valkey).

## 8. Gerador de carga

Ingestor e Leitor usam o mesmo `LoadRunner<T>`:

```java
LoadRunner<T>(Iterator<T> source, List<Step<T>> steps, int tps, Duration duration, int threads, CsvLog log)
record Step<T>(String operation, Consumer<T> call, ToLongFunction<T> accountOf) {}
```

| Elemento | Comportamento |
| --- | --- |
| Despacho | Um thread despachante em laço aberto (open-loop). Período `1e9 / tps` ns; o próximo instante é `inicio + k × período`, aguardado com `LockSupport.parkNanos`. O despachante só para de emitir em `inicio + duration` ou quando a fonte acaba |
| Fila | `ArrayBlockingQueue` com capacidade `tps` (1 s de folga). Se encher, o despachante bloqueia e a carga oferecida cai, o que aparece no TPS alcançado |
| Workers | `--threads` threads de plataforma, cada um faz `take()` e executa os passos em sequência. Pílula de veneno no fim |
| Medição | Para cada passo: `ts_epoch_ms = System.currentTimeMillis()` e `System.nanoTime()` antes da chamada ao port, `nanoTime` depois. Exceção → `status = error`. A latência cobre só a chamada ao port, como define a funcional |
| Preparação | Pool UCP cheio, conexão Valkey aberta, scripts carregados e dataset em memória **antes** de o despachante começar |

Dimensionamento: `--threads` precisa satisfazer `threads ≥ tps × latência_média_s`, com folga. O default 8 a 600 TPS aguenta latência média de até 13 ms por operação completa (no modo `oracle+valkey`, a soma dos dois sinks).

Limitação conhecida, registrada para quem ler os números: o tempo que a operação passa na fila não entra em `latency_us` (*coordinated omission*). O critério de TPS sozinho não basta para detectar saturação: depois de um travamento, o despachante emite em rajada e os workers esvaziam a fila dentro da janela medida, de modo que o TPS volta ao alvo e a espera não aparece em lugar nenhum (na rodada de aceitação local, cerca de 10 s de fila acumulada no warmup apareceram só como 622 TPS para um alvo de 600). Por isso os workers medem, à parte, o atraso entre o instante agendado de cada item e o início real da execução, contando só os itens que começam depois do warmup. O resultado vai para o `.meta.properties` (`measured_starts`, `late_starts` com atraso acima de 100 ms, `max_start_lag_ms`) e o Relatório marca a rodada com `Fila ok = NÃO` quando mais de 1% dos inícios atrasou.

## 9. Programas

Comando comum: `java -jar sentinela.jar <programa> [--arg valor ...]`. Variáveis de ambiente da funcional, mais `FS_ZONE` (default `America/Sao_Paulo`).

### 9.1 `schema` (novo)

Executa `db/01_schema.sql`, empacotado no jar como recurso, com o usuário de `ORACLE_USER`. Divide o arquivo por `;` no fim de linha. É idempotente graças ao `IF NOT EXISTS`.

### 9.2 `gerador`

| Arg | Default | Novo? |
| --- | --- | --- |
| `--accounts`, `--seed`, `--distribution`, `--dataset` | Da funcional | — |
| `--count` | 200000 | Sim: quantidade de transações. Deve valer `count ≥ tps × duration` do Ingestor (600 × 300 = 180.000) |

Algoritmo: `tx_id` sequencial de 1 a `count`. A conta vem do `AccountSampler` e o valor do `AmountSampler`, cada um com seu próprio `SplittableRandom` derivado do `seed` (salt distinto por uso). Uma linha por transação: `{"tx_id":1,"account_id":4711,"amount":123.45}`.

| Amostrador | Definição |
| --- | --- |
| `AccountSampler` `uniform` | Uniforme em `[1, N]` |
| `AccountSampler` `skewed` | Contas quentes = `[1, max(1, N/100)]`. Com probabilidade 0,5 sorteia uniforme entre as quentes; senão, uniforme em `[1, N]`. Resultado: cerca de 50% das transações em 1% das contas |
| `AmountSampler` | Lognormal com mediana R$ 150 (`μ = ln 150`, `σ = 1,2`), arredondado para centavos e limitado a [R$ 1,00; R$ 50.000,00] |

### 9.3 `ingestor`

Args da funcional, mais `--run` (default `yyyyMMdd-HHmmss` do início, em UTC; o script passa o mesmo valor ao Leitor). Carrega o dataset inteiro em memória (200 mil linhas, alguns MB) e monta o `LoadRunner` com os sinks do modo. Se o dataset acabar antes de `--duration`, a rodada é inválida: aviso em `stderr` e exit code 4.

### 9.4 `leitor`

Args da funcional, mais `--run`. A fonte é um iterador infinito do `AccountSampler`, com salt próprio do Leitor, executado no thread despachante. Assim a sequência de contas é determinística, seja qual for `--threads`.

### 9.5 `backfill`

| Arg | Default | Novo? |
| --- | --- | --- |
| `--mode`, `--accounts`, `--days`, `--seed` | Da funcional | — |
| `--distribution` | `skewed` | Sim |
| `--tx-per-day` | 5 | Sim: média de transações por conta por dia; total diário = `accounts × tx-per-day` |

Passos, com `now` = instante de início e `T` = hoje em `FS_ZONE`:

1. `OracleMaintenance.reset()`; no modo `oracle+valkey`, também `ValkeyMaintenance.reset()`.
2. `TX` de 00:00 de `T − 3` até `now`: em cada dia, `accounts × tx-per-day` transações (proporcional no dia corrente), conta via `AccountSampler`, valor via `AmountSampler`, `ts` uniforme no dia. `tx_id` a partir de 10^15, uma faixa que nunca colide com o dataset. Inserção em batch JDBC de 5.000 linhas, com commit a cada 50.000.
3. `closeDay` para `T − 3`, `T − 2` e `T − 1`: `DAILY_AGG` sai de `TX` pelo mesmo SQL do Job.
4. `DAILY_AGG` sintético para `T − days` até `T − 4`: para cada dia, sorteia as transações do dia em memória, agrega por conta em arrays indexados pela conta e insere em batch só as contas com `cnt > 0`. Nenhuma transação desses dias vai para `TX`.
5. `gatherStats()`.
6. No modo `oracle+valkey`: `rebuildFromOracle(now)`.

Exige `days ≥ 3`. Volume local a 100 mil contas: cerca de 1,75 milhão de linhas em `TX` e 8 milhões em `DAILY_AGG`.

### 9.6 `job-diario`

`--mode` e `--day` (default `T − 1`), como na funcional. Passos: `closeDay(day)`; no modo `oracle+valkey`, `putDays(day, linhas de DAILY_AGG do dia, T)`; `dropOldPartitions(T)`. Idempotente.

### 9.7 `relatorio`

Argumento único: o diretório de logs. Lê todo `*.csv` que tenha um `.meta.properties` correspondente e escreve em `<dir>/report/`.

| Passo | Regra |
| --- | --- |
| Agrupamento | Por `run`, `program`, `mode` e `operation` |
| Warmup | Descarta linhas com `ts_epoch_ms < start_epoch_ms + warmup × 1000` |
| Operações e erros | `ok` e `error` contados à parte; taxa de erro = `error / (ok + error)` |
| Duração e TPS | Do primeiro ao último `ts_epoch_ms` após o warmup; TPS = operações `ok` / duração |
| Desvio de TPS | `|alcançado − alvo| / alvo`, com o alvo vindo do `.meta` |
| Percentis | Nearest-rank sem interpolação: com a amostra ordenada, valor na posição `ceil(q × n)` (base 1) |
| Unidade | Milissegundos com uma casa, `HALF_UP` |
| Critérios de carga | Colunas `tps_ok` (desvio < 5%), `erro_ok` (taxa < 0,1%) e `lag_ok` (no máximo 1% dos inícios após o warmup com atraso de fila acima de 100 ms; seção 8), mais `max_start_lag_ms` |

Saídas:

| Arquivo | Conteúdo |
| --- | --- |
| `report.md` | Uma tabela por `run`; se houver os dois modos, uma tabela lado a lado usando o `run` mais recente de cada modo, linha por operação equivalente: `read` × `read`, `ingest_oracle` × `ingest_oracle`, mais `ingest_valkey` só no modo `oracle+valkey` |
| `report.csv` | Mesmas linhas das tabelas por `run` |
| `tps-series.csv` | `run, program, mode, operation, segundo, operacoes`, com o segundo relativo a `start_epoch_ms` e incluindo o warmup (marcado em coluna) |

Os dados cabem em memória com folga: cerca de 600 mil linhas por rodada.

### 9.8 `equivalencia` (novo)

Executa o cenário C e os critérios funcionais que não dependem de carga. Usa os dois adapters ao mesmo tempo, sem `--mode`.

| Arg | Default | Significado |
| --- | --- | --- |
| `--accounts` | 1000 | Contas verificadas (critério: pelo menos 1.000) |
| `--seed` | 42 | Reprodutibilidade |
| `--now` | Instante atual | Instante lógico das leituras, ISO-8601 |
| `--reset` | ausente | Sem esta flag, o programa se recusa a rodar se `TX` ou o Valkey tiverem dados |

Passos:

1. Reset dos dois stores.
2. Gera em memória as transações de `now − 93 dias` até `now` para as contas, em quatro classes por `conta mod 4`:

   | Classe | Conteúdo | Critério que exercita |
   | --- | --- | --- |
   | 0, normal | De 0 a 300 transações espalhadas nos 93 dias | Equivalência geral |
   | 1, silenciosa | Como a 0, sem nada nas 60 fatias de minuto da janela de 1h (do minuto corrente até 59 minutos antes) | `cnt = 0` nos minutos e em 1h |
   | 2, só hoje | Transações só no dia corrente | Dias não incluem o dia corrente |
   | 3, fronteiras | Uma transação exatamente em cada limite inferior das 19 janelas e outra 1 µs antes; também em 00:00 local e 00:00 UTC | Erros de ±1 fatia e de fuso |

3. Aplica as transações em ordem de `ts` nos dois adapters, via `ingest` do port, de forma síncrona. São cerca de 150 mil transações, menos de um minuto por adapter.
4. Para cada dia `D` de `T − 92` a `T − 1`: `closeDay(D)` e `putDays(D, …, T)`.
5. Lê cada conta em `now` nos dois adapters e calcula um terceiro vetor com `ReferenceModel`. Esse modelo filtra as transações em memória com aritmética de `ZonedDateTime` e não usa `WindowCatalog`: assim os dois adapters não podem estar errados do mesmo jeito.
6. Verificações (cada uma imprime `PASS` ou `FAIL` com até 20 divergências: conta, nome da posição, valores):
   - vetores com 95 posições e nomes na ordem de `FeatureVector.names()`;
   - Oracle = Valkey = referência para todas as contas;
   - classe 1: `cnt = 0` nas 5 janelas de minutos e em 1h;
   - classe 2: `cnt = 0` nas 4 janelas de dias;
   - `checkDay(D)` com 0 linhas para todos os dias fechados;
   - reaplicar todas as transações no adapter Oracle não muda `COUNT(*)` de `TX` nem os vetores Oracle.
7. Exit code 0 se todas passarem; 1 caso contrário.

### 9.9 Códigos de saída

| Código | Significado |
| --- | --- |
| 0 | Sucesso |
| 1 | Verificação falhou (`equivalencia`) |
| 2 | Argumento ou variável de ambiente inválida |
| 3 | Store indisponível na inicialização |
| 4 | Rodada inválida (dataset esgotado antes do fim) |
| 5 | Erro inesperado (I/O, falha não prevista), com stack trace em `stderr` |

## 10. Logs

| Item | Regra |
| --- | --- |
| Caminho | `--log`, com default `./logs/<programa>-<mode>-<run>.csv` (`programa` = `ingestor` ou `reader`) |
| Formato | Colunas da seção 8 da funcional, nessa ordem, separadas por vírgula, com uma primeira linha de cabeçalho com os nomes das colunas |
| Escrita | Os workers põem registros numa `ArrayBlockingQueue` (capacidade 100.000). Um thread escritor dedicado esvazia a fila num `BufferedWriter` de 64 KB, com `flush` a cada segundo e no fechamento. O worker nunca faz I/O |
| Sidecar | `<log sem .csv>.meta.properties` com `program`, `mode`, `run`, `tps`, `duration`, `warmup`, `threads`, `accounts`, `distribution`, `seed`, `dataset`, `start_epoch_ms`, `java.version`, `host`. Escrito no início; `end_epoch_ms` é acrescentado no fim |
| Erros | Os 10 primeiros com stack trace em `stderr`; depois, um resumo por tipo de exceção a cada 10 s |

## 11. Ambiente local (podman)

### 11.1 Containers

| Container | Imagem | Configuração |
| --- | --- | --- |
| `sentinela-oracle` | `container-registry.oracle.com/database/enterprise:latest` (Oracle Database 26ai Enterprise Edition). O script confere o label `release` da imagem e aborta se não for `23.26.x` | `ORACLE_SID=ORCLCDB`, `ORACLE_PDB=SENTINELA`, `ORACLE_PWD` vindo de `.env.local`, `INIT_SGA_SIZE=4096`, `INIT_PGA_SIZE=2048` (MB), `ORACLE_CHARACTERSET=AL32UTF8`; volume nomeado `sentinela-oradata` em `/opt/oracle/oradata`; serviço `SENTINELA` |
| `sentinela-valkey` | `valkey/valkey:7.2` | `valkey-server --save "" --appendonly no`; demais parâmetros no default, para paridade com o OCI Cache |
| `poc` (efêmero) | `sentinela-poc`, construída pelo `Containerfile` | `--rm --pod sentinela --env-file .env.local -v ${PWD}/data:/data -v ${PWD}/logs:/logs` |

Todos ficam no pod `sentinela`, então se enxergam por `localhost`:

```
ORACLE_URL=jdbc:oracle:thin:@//localhost:1521/SENTINELA
VALKEY_URL=redis://localhost:6379
```

O pod publica 1521 e 6379 no host, para inspeção com SQLcl e `valkey-cli`. `.env.local` fica fora do git, e um `.env.example` é versionado.

### 11.2 Imagem da POC (`Containerfile`)

Multi-stage. O primeiro estágio usa `maven:3.9-eclipse-temurin-25` e roda `mvn -B package -DskipTests`. O segundo usa `eclipse-temurin:25-jre` e copia o jar. Entrypoint:

```
java -XX:+UseZGC -Xms1g -Xmx1g -XX:+AlwaysPreTouch -Duser.timezone=UTC -jar /app/sentinela.jar
```

ZGC (geracional, único modo no Java 25) e heap fixo deixam pausas de GC fora dos percentis de latência.

### 11.3 Scripts (PowerShell 7, que também roda em Linux na OCI)

| Script | Faz |
| --- | --- |
| `scripts/ambiente.ps1 -Acao up` | Cria o pod e os dois containers; espera o healthcheck do Oracle; executa `db/00_usuario.sql` via `podman exec -i … sqlplus`; constrói a imagem `sentinela-poc`; roda `schema` |
| `scripts/ambiente.ps1 -Acao down` | Remove o pod. O volume do Oracle fica |
| `scripts/ambiente.ps1 -Acao destroy` | Remove o pod e o volume |
| `scripts/rodada.ps1 -Mode <modo> [-Run <id>] [-Tps 600] [-Duration 300] [-Accounts 100000]` | Gera o dataset se ele não existir; roda `backfill`; sobe `ingestor` e `leitor` com `podman run -d`, com o mesmo `--run`, e espera os dois com `podman wait`; roda `relatorio` sobre `./logs` |

Sequência completa dos cenários A e B: `ambiente.ps1 -Acao up`, depois `rodada.ps1 -Mode oracle`, depois `rodada.ps1 -Mode oracle+valkey`. O segundo relatório já traz os dois modos lado a lado. O cenário C roda com `podman run … sentinela-poc equivalencia --reset`, fora das rodadas de carga, e a rodada seguinte refaz os dados pelo Backfill.

### 11.4 Recursos e volume

A Enterprise Edition não tem os limites de CPU, memória e dados da edição Free. A máquina podman precisa de pelo menos 8 CPUs e 16 GB: 6 GB para SGA e PGA do Oracle, mais o Valkey e os dois programas Java com 1 GB de heap cada. A criação inicial do banco leva de 10 a 20 minutos e só acontece uma vez, porque o volume persiste.

| Escala | `TX` | `DAILY_AGG` | Oracle (dados + índices) | Valkey |
| --- | --- | --- | --- | --- |
| 100 mil contas | ~1,9 milhão de linhas | ~8 milhões | ~0,7 GB | < 1 GB |
| 300 mil contas | ~5,6 milhões | ~25 milhões | ~2 GB | < 3 GB |

Subir de 100 mil para 300 mil contas muda só `--accounts`.

## 12. Testes e verificação

### 12.1 Unitários (`mvn test`, sem infraestrutura)

| Alvo | Casos |
| --- | --- |
| `WindowCatalog` | `bounds` nas viradas de minuto, hora e dia; `now` exatamente na fronteira e 1 µs antes; dia civil em `America/Sao_Paulo` contra UTC entre 21:00 e 23:59 locais; ordem e sufixos das 19 janelas |
| `WindowAgg` / `SliceAggregator` | Combinação com vazio; mínimo e máximo; fatias fora do limite e no futuro; janela de 1h usando fatias de minuto |
| `FeatureVector` | 95 nomes e valores; `avg` com escala 4 `HALF_EVEN`; nulos quando `cnt = 0` |
| Parser de fatia do Valkey | Campos `m:`/`h:`/`d:` válidos; valor malformado vira exceção |
| `AccountSampler` / `AmountSampler` | Mesma sequência para o mesmo seed; proporção de contas quentes perto de 50% |
| Relatório | Percentil nearest-rank em amostras pequenas conhecidas; descarte do warmup; TPS; agrupamento por `run` |
| Parser de argumentos | Defaults, tipos inválidos, argumento desconhecido |

### 12.2 Integração (`mvn verify -Pintegration`)

Testes com a tag `integration`. Só rodam com `ORACLE_URL` e `VALKEY_URL` definidas. Os destrutivos (que esvaziam `TX`, `DAILY_AGG` ou o Valkey) só rodam se os dois apontarem para `localhost`, ou com `FS_IT_DESTRUCTIVE=1` explícito, para nunca apagarem um ambiente remoto por engano. Contêm:

- `EquivalenceIT`: chama a mesma classe `EquivalenceCheck` do programa `equivalencia`, com 1.000 contas;
- teste de cada script Lua isolado: fatia nova, combinação e limpeza da retenção;
- teste do SQL de leitura contra uma conta conhecida, inclusive conferindo o plano (seção 5.3).

### 12.3 Matriz de aceitação

| Critério da funcional | Onde é provado |
| --- | --- |
| `read` devolve 95 valores na ordem | `equivalencia` (nomes e tamanho) e unitários de `FeatureVector` |
| Sem transação na última hora → `cnt = 0` nos minutos e em 1h | `equivalencia`, classe 1 |
| Dias não incluem o dia corrente | `equivalencia`, classe 2, e unitários de `WindowCatalog` |
| Cenário C sem divergência em pelo menos 1.000 contas | `equivalencia --accounts 1000` |
| Reaplicar o dataset não altera os contadores | `equivalencia`, passo de reaplicação |
| Job diário = recálculo sobre `TX` | `equivalencia`, `checkDay` para cada dia |
| Relatório com TPS e latências por programa, modo e operação | `relatorio` e unitários do Relatório |
| TPS sustentado com desvio < 5% | Coluna `tps_ok` do `report.md` |
| Erro < 0,1% | Coluna `erro_ok` do `report.md` |

## 13. Riscos e pontos de atenção

| Risco | Efeito | Mitigação |
| --- | --- | --- |
| Tudo na mesma máquina | Ingestor, Leitor, Oracle e Valkey disputam CPU; os números absolutos não valem para a OCI | A POC compara modos (premissa da funcional); registrar CPU e memória da máquina no `.meta` (`host`) e no relatório |
| Contas quentes no modo `skewed` | Contenção em folhas do índice `(account_id, ts, amount)` e leituras que varrem cerca de 1.000 entradas | É a condição realista que se quer medir; observar o p99 de `read` no modo `oracle` |
| Payload do `HGETALL` | Contas quentes têm até 224 campos (alguns KB) por leitura | Se o p99 de `read` no Valkey ficar muito acima do RTT, testar uma variante Lua que devolva só as 19 agregações. Isso exigiria duplicar a definição de janelas no Lua, coberta pela `equivalencia` |
| Hashes acima de 128 campos | O Valkey troca a codificação listpack por hashtable e usa mais memória | Aceito: mantém a paridade com o default do OCI Cache |
| `UPDATE GLOBAL INDEXES` no `DROP PARTITION` | Custo assíncrono de limpeza do índice global | Fora da janela de medição; o Job roda à parte |
| Relógio do pod e `now` lógico | A `equivalencia` usa `--now`, e as janelas no futuro em relação ao relógio são válidas | Os adapters não leem relógio (seção 4.1), então o resultado não depende do relógio do pod |
| Fuso com offset fracionário | A fatia de hora UTC deixaria de coincidir com a hora local | Fora de escopo; `America/Sao_Paulo` tem offset de hora cheia |
