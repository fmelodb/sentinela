# Como os dados são consultados no Oracle

A leitura do vetor de features de uma conta é feita em **um único statement**, gerado pela classe
`OracleReadSql` (`src/main/java/sentinela/adapter/oracle/OracleReadSql.java`) a partir do `WindowCatalog`.
O resultado é **sempre uma única linha larga**, com contagem, soma, mínimo e máximo de cada janela.

O statement combina duas fontes:

- **`TX`**: as transações cruas, usadas nas 15 janelas deslizantes (minutos/horas) e no dia corrente.
- **`DAILY_AGG`**: os agregados por dia, usados nos dias já fechados das 4 janelas de dias.

## 1. A estrutura da consulta

Simplificando para uma janela deslizante (`5m`) e uma janela de dias (`7d`), o SQL gerado tem esta forma:

```sql
WITH b AS (                                   -- (A) a linha dos limites
  SELECT ? AS lo_5m, ? AS lo_hoje, ? AS lo_7d FROM dual
)
SELECT t.*, d.*
FROM (                                        -- (B) agregados sobre TX
  SELECT COUNT(CASE WHEN x.ts >  b.lo_5m   THEN 1 END)        AS cnt_5m,
         SUM  (CASE WHEN x.ts >  b.lo_5m   THEN x.amount END) AS sum_5m,
         COUNT(CASE WHEN x.ts >= b.lo_hoje THEN 1 END)        AS cnt_hoje,
         ...
  FROM tx x CROSS JOIN b
  WHERE x.account_id = ? AND x.ts > ? AND x.ts <= ?
) t
CROSS JOIN (                                  -- (C) agregados sobre DAILY_AGG
  SELECT SUM(CASE WHEN a.dia >= b.lo_7d THEN a.cnt END)        AS cnt_7d,
         SUM(CASE WHEN a.dia >= b.lo_7d THEN a.sum_amount END) AS sum_7d,
         ...
  FROM daily_agg a CROSS JOIN b
  WHERE a.account_id = ? AND a.dia >= ? AND a.dia < ?
) d
```

São três partes:

| Parte | O que faz | Linhas que devolve |
|---|---|---|
| (A) `b` | Guarda o limite inferior de cada janela como coluna | 1 (`FROM dual`) |
| (B) `t` | Agrega as transações cruas de `TX` | 1 |
| (C) `d` | Agrega os dias fechados de `DAILY_AGG` | 1 |

## 2. Por que cada subquery devolve exatamente uma linha

**Um SELECT com função de agregação (`COUNT`, `SUM`, `MIN`, `MAX`) e sem `GROUP BY` devolve sempre
exatamente uma linha.** Isso vale para 0, 3 ou 1 milhão de linhas de entrada.

Exemplo: a conta tem três linhas em `DAILY_AGG`.

| dia | cnt | sum_amount |
|---|---|---|
| T−1 | 2 | 5.00 |
| T−3 | 1 | 2.00 |
| T−10 | 4 | 8.00 |

A subquery (C) **lê** essas três linhas, mas não as **devolve**. Os `SUM(...)` as reduzem a uma só:

| cnt_7d | sum_7d |
|---|---|
| 3 | 7.00 |

A linha T−10 fica de fora da janela `7d` porque o `CASE` devolve `NULL` para ela, e as funções de agregação
ignoram `NULL`. Cada janela é um par `CASE` + agregado sobre o **mesmo** conjunto de linhas. Por isso uma
única passada pelos dados calcula todas as janelas ao mesmo tempo (agregação condicional).

O mesmo vale para (B): a conta pode ter centenas de transações em `TX`, e a subquery devolve uma linha com
`cnt_5m`, `sum_5m`, `cnt_hoje` etc.

Sem nenhuma linha de entrada (por exemplo, uma conta nova), a subquery também devolve uma linha, com
`COUNT = 0` e `SUM`/`MIN`/`MAX = NULL`. Assim o join externo nunca perde a linha do resultado.

## 3. O papel de cada CROSS JOIN

Um `CROSS JOIN` combina cada linha de um lado com cada linha do outro: N × M linhas. Na consulta, todos os
CROSS JOINs têm pelo menos um lado de uma linha só, então nenhum deles multiplica o resultado.

### `tx CROSS JOIN b` e `daily_agg CROSS JOIN b`

`b` tem uma linha só, com os limites das janelas. O CROSS JOIN com ela não multiplica nada (N × 1 = N). O
efeito é anexar as colunas de limite a cada linha de `tx` (ou de `daily_agg`), para os `CASE WHEN` poderem
compará-las:

| x.ts | x.amount | b.lo_5m | b.lo_hoje |
|---|---|---|---|
| 10:01 | 50 | 09:58 | 00:00 |
| 09:40 | 30 | 09:58 | 00:00 |

Cada limite é passado uma vez, como bind de `b`, e referenciado por nome em quantos `CASE` precisar. Os `?`
do `WHERE` são binds diretos: eles fazem a **poda**, isto é, restringem a leitura pela conta e pelo
intervalo total (da janela mais antiga até `now`), o que permite usar o índice por `account_id` e
timestamp/dia.

### `t CROSS JOIN d`

`t` e `d` não têm coluna em comum para um `ON`, e não precisam ter. O objetivo é só pôr os dois resumos lado
a lado:

```
t (1 linha): cnt_5m | sum_5m | cnt_hoje | ...
d (1 linha): cnt_7d | sum_7d | ...
t × d      : cnt_5m | sum_5m | cnt_hoje | ... | cnt_7d | sum_7d | ...   ← 1 linha
```

É a forma mais simples de juntar dois resultados de uma linha numa linha larga, com uma única ida ao banco.

## 4. Como as janelas de dias são montadas

Uma janela de dias (por exemplo `7d`) não vem inteira de `DAILY_AGG`, porque o dia corrente ainda não foi
fechado. Ela é composta de duas partes:

- **dias fechados**: as colunas `cnt_7d`, `sum_7d`, `min_7d`, `max_7d` de `d`, filtradas por
  `a.dia >= lo_7d` e `a.dia < hoje`;
- **dia corrente**: as colunas `cnt_hoje`, `sum_hoje`, `min_hoje`, `max_hoje` de `t`, calculadas sobre `TX`
  a partir do início do dia (`x.ts >= lo_hoje`).

A aplicação combina as duas partes depois de ler a linha (`OracleFeatureStore.read`, com
`WindowAgg.plus`): soma as contagens e as somas e toma o menor mínimo e o maior máximo.

## 5. Resumo

| Etapa | Linhas |
|---|---|
| `b` | 1 |
| `tx × b` | N × 1 = N, reduzidas a **1** pelos agregados (`t`) |
| `daily_agg × b` | M × 1 = M, reduzidas a **1** pelos agregados (`d`) |
| `t × d` | 1 × 1 = **1 linha final**, com todas as janelas |

O CROSS JOIN é seguro aqui porque a própria forma da consulta garante uma linha em cada lado: agregados sem
`GROUP BY` ou `FROM dual`. Se alguma subquery tivesse `GROUP BY`, o CROSS JOIN multiplicaria as linhas, e a
consulta precisaria de outra estrutura.
