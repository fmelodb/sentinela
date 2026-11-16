# Como os dados são consultados no Oracle

As 10 variáveis de uma compra avaliada (T0) saem de **um único statement**, gerado pelo módulo `read_sql`
(`src/sentinela/db/read_sql.py`). O resultado é **sempre uma única linha larga**, com 50 agregados brutos. A
aplicação calcula as razões e aplica as regras de NULL do Excel sobre essa linha (`domain/variables.py`).

O statement lê as três fontes:

- **`TRX`**: as transações do cliente nos 92 dias antes de T0, só as linhas base (`ORIGEN='atmpos_trxs'` e
  `MTI=200`). Daqui saem V1–V4 e V7–V10.
- **`ABR`**: as notificações de dispositivo válidas, contadas por segundo distinto. Daqui sai V5.
- **`VU`**: os eventos de segurança, no total e só os CPASS. Daqui sai V6.

## 1. A estrutura da consulta

Simplificando, o SQL gerado tem esta forma:

```sql
SELECT /* sentinela.leitura */ t.*, a.*, v.*
FROM (                                                -- (A) TRX
  SELECT /*+ INDEX(x trx_cli_ts_ix) */
         COUNT(CASE WHEN x.ts >= :lo24 THEN 1 END)                     AS n24,
         ...
         COUNT(CASE WHEN x.ap = 1 AND x.ts >= :lo24 THEN 1 END)        AS f1_24_cnt,
         COUNT(CASE WHEN x.ap = 1 AND x.ts >= :lo24
                     AND x.monto IS NULL THEN 1 END)                   AS f1_24_nnull,
         SUM  (CASE WHEN x.ap = 1 AND x.ts >= :lo24 THEN x.monto END)  AS f1_24_sum,
         MAX  (CASE WHEN x.ap = 1 AND x.ts >= :lo24 THEN x.monto END)  AS f1_24_max,
         ...
  FROM trx x
  WHERE x.cliente = :cli AND x.ts >= :lo92 AND x.ts <= :hi AND x.base = 1
) t
CROSS JOIN (                                          -- (B) ABR
  SELECT /*+ INDEX(a abr_cli_ts_ix) */
         COUNT(DISTINCT CASE WHEN a.ts >= :lo1h THEN a.ts END) AS abr_1h,
         COUNT(DISTINCT a.ts)                                  AS abr_92
  FROM abr a
  WHERE a.cliente = :cli AND a.ts >= :lo92 AND a.ts <= :hi AND a.valido = 1
) a
CROSS JOIN (                                          -- (C) VU
  SELECT /*+ INDEX(v vu_cli_ts_ix) */
         COUNT(*) AS vu_92, COUNT(CASE WHEN v.cpass = 1 THEN 1 END) AS cpass_92
  FROM vu v
  WHERE v.cliente = :cli AND v.ts >= :lo92 AND v.ts <= :t0
) v
```

Os binds de instante são calculados na aplicação a partir de T0, em segundos, como no Excel:

| Bind | Valor | Uso |
|---|---|---|
| `:t0` | T0 | limite superior do VU, que inclui o segundo do T0 (`0 ≤ delta`) |
| `:hi` | T0 − 1 s | limite superior de TRX e ABR (`1 ≤ delta`) |
| `:lo5`, `:lo1h`, `:lo3h`, `:lo24`, `:lo92` | T0 − 300 s, − 3.600 s, − 10.800 s, − 86.400 s, − 7.948.800 s | limites inferiores das janelas (`delta ≤ N`) |
| `:mcc`, `:com` | MCC e comercio de T0 | filtros "mesmo MCC" e "mesmo comercio" |

## 2. Por que cada subquery devolve exatamente uma linha

**Um SELECT com função de agregação (`COUNT`, `SUM`, `MAX`) e sem `GROUP BY` devolve sempre exatamente uma
linha.** Isso vale para 0, 3 ou 10 mil linhas de entrada. Sem nenhuma linha (um cliente novo), a subquery
devolve `COUNT = 0` e `SUM`/`MAX = NULL`, e o CROSS JOIN nunca perde a linha do resultado.

Cada janela ou filtro é um par `CASE` + agregado sobre o **mesmo** conjunto de linhas. Por isso uma única
passada pelos dados do cliente calcula todas as janelas ao mesmo tempo (agregação condicional). O `CASE` devolve
`NULL` para a linha fora da janela ou do filtro, e as funções de agregação ignoram `NULL`.

Os três `CROSS JOIN` juntam três resumos de uma linha cada (1 × 1 × 1 = 1 linha). Eles seriam perigosos se
alguma subquery tivesse `GROUP BY`; aqui, a própria forma da consulta garante uma linha em cada lado.

## 3. Por que a aplicação recebe agregados brutos, e não as variáveis

As regras de NULL do Excel dependem de mais do que o filtro da variável. Por exemplo, V1 é NULL se a janela
inteira (todas as linhas base de 24 h) menos as linhas do filtro com monto nulo der zero. Para cada filtro a
consulta devolve então quatro números: contagem, quantos têm monto nulo, soma e máximo. Para cada janela,
devolve também a contagem de linhas base. Com isso `variables.compute` aplica as regras exatamente como as
fórmulas, sem uma segunda ida ao banco.

## 4. Comparações com nulo

O Excel compara o MCC como texto, com a chave `"NULL"` para vazio: MCC nulo na T0 casa com MCC nulo na
transação. O mesmo vale para o comercio. Em SQL, `x.mcc = :mcc` seria falso para nulo = nulo; a consulta usa
`DECODE(x.mcc, :mcc, 1, 0) = 1`, porque o `DECODE` trata dois nulos como iguais.

## 5. Classificação na ingestão

As condições de cada TRX que não dependem de T0 (base, aprovada global, rechazada global, aprovada com tarjeta
ausente, rechazada nacional presente diurna, aprovada de madrugada) são calculadas uma vez, na ingestão
(`domain/events.py`, `TrxFlags`), e gravadas como colunas `NUMBER(1)`. A leitura só compara `x.ap = 1`, sem
reavaliar listas de modos de entrada, nomes com `WEBPAY` ou faixas horárias a cada consulta. O que depende de
T0 (janelas, mesmo MCC, mesmo comercio) fica na consulta.

As faixas horárias usam a hora local de `FS_ZONE`, que é a hora gravada em `ts`.

## 6. Partições, índices e o plano

As três tabelas têm **partição por intervalo diário em `ts`** e **índices globais (não particionados) cobertos**:

| Índice | Colunas |
|---|---|
| `TRX_CLI_TS_IX` | `cliente, ts, base, ap, re, ap_aus, re_npd, ap_mad, mcc, comercio, monto` |
| `ABR_CLI_TS_IX` | `cliente, ts, valido` |
| `VU_CLI_TS_IX` | `cliente, ts, cpass` |

**A leitura não depende do número de partições da tabela.** Ela não toca a tabela: cada subquery faz um
`INDEX RANGE SCAN` num índice global, que é uma única árvore B. A descida vai direto à primeira entrada
`(cliente, lo92)` e segue as folhas até `(cliente, hi)`. Todas as transações de 92 dias de um cliente ficam
contíguas no índice, numa única varredura, sem `TABLE ACCESS`.

As partições diárias servem só à retenção. O `job-diario` faz `DROP PARTITION ... UPDATE GLOBAL INDEXES`, e
no Oracle 12c+ a limpeza das entradas órfãs do índice global é adiada e assíncrona, então o drop é barato.

Alternativas consideradas:

| Alternativa | Efeito na leitura | Efeito na retenção |
|---|---|---|
| Diária + índice **local** | Uma sondagem do índice por partição: ~93 descidas por leitura | Drop de partição sem tocar índice |
| Mensal + subpartição hash por cliente + índice local | Poda para uma subpartição por mês: ~4 descidas por leitura | Retenção em degraus de um mês (guarda até ~4 meses para cobrir 92 dias) |
| **Diária + índice global coberto** (atual) | **1 descida** por leitura e por fonte | Drop diário com `UPDATE GLOBAL INDEXES` |

A subpartição por cliente é a forma de chegar perto da leitura do índice global usando índices locais. Faz
sentido se o drop de partição com índice global virar problema em volume real, ou se a ingestão concentrar
contenção nos blocos do índice. Com o índice começando por `cliente`, as inserções já se espalham pelo índice
inteiro, e não só no bloco mais à direita.

O plano esperado é um `INDEX RANGE SCAN` em cada um dos três índices e **nenhum `TABLE ACCESS`**. O `backfill`
confere esse plano (`db/plan.py`) sobre o volume carregado e invalida a rodada se ele for outro. Os testes de
integração conferem o mesmo.

Cada subquery leva a hint `INDEX` do seu alias (constante `HINTS` em `read_sql.py`), no bloco onde o alias é
definido; no `SELECT` externo, o Oracle a ignoraria sem aviso. Motivo: num teste no ATP com o modelo anterior,
o cursor da leitura foi otimizado com `TABLE ACCESS STORAGE FULL` nas partições recentes, e cada leitura passou
a custar ~80 ms de CPU, contra menos de 3 ms com o índice. Com clientes assimétricos (`skewed`) e histograma,
qualquer hard parse pode repetir a escolha; a hint deixa o plano estável.

## 7. GET por ID_TRX

`GET /v1/features/{ID_TRX}` primeiro lê a T0 pela PK de `TRX`
(`SELECT cliente, ts, monto, mcc, comercio, id_trx FROM trx WHERE id_trx = :id`) e depois roda a mesma
consulta acima. A própria TRX está em `delta = 0` e fica fora das janelas de TRX (`1 ≤ delta`).

## 8. Resumo

| Etapa | Linhas |
|---|---|
| (A) TRX do cliente em 92 dias | N, reduzidas a **1** pelos agregados |
| (B) ABR válidas do cliente em 92 dias | M, reduzidas a **1** |
| (C) VU do cliente em 92 dias | K, reduzidas a **1** |
| `t × a × v` | 1 × 1 × 1 = **1 linha final**, com os 50 agregados |
