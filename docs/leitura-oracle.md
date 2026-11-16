# Como os dados são gravados e lidos no Oracle

Este documento acompanha um evento do momento em que chega na API até virar linha no Oracle (escrita), e uma
compra avaliada do pedido até as 10 variáveis (leitura). Um exemplo pequeno, com números, atravessa as duas
partes. A definição exata de cada variável está em [variaveis-xgboost.md](specs/variaveis-xgboost.md).

```
            ESCRITA                                         LEITURA
POST /v1/trx  (JSON da fonte)                 POST /v1/features (compra T0)  ou  GET /v1/features/{ID_TRX}
     │                                                   │
     ▼  api/models.py: valida, hora local                ▼  T0 = (cliente, instante, monto, MCC, comercio)
     ▼  domain/events.py: classifica a TRX               ▼  db/read_sql.py: 1 SELECT, 3 subconsultas
     ▼  db/rows.py: INSERT + COMMIT                      ▼  Oracle: 3 INDEX RANGE SCAN → 1 linha, 50 números
     ▼  Oracle: tabela + índices, redo                   ▼  domain/variables.py: razões e regras de NULL
  201 (nova) / 200 (repetida)                         10 variáveis em JSON
```

---

## Parte 1 — Escrita

### 1.1 O que chega

A API recebe os eventos com os **nomes de coluna das fontes**, para os dados originais entrarem sem tradução.
Uma transação (TRX):

```json
{"ID_TRX": "a", "N_ID_CUSTOMER": "C1", "D_TRANSMISSION_DATE_AND_TIME": "2026-09-30T11:58:00",
 "F_AMOUNT_TRANSACTION": 20000, "S_RESPONSE_CODE": "00", "N_PCODE": 3000, "N_ENTRY_MODE": 9,
 "N_ACQUIRING_INSTITUTION_COUNTRY_CODE": 152, "S_CARD_ACCEPTOR_NAME": "COMERCIO", "N_MERCHANT_TYPE": 5411,
 "S_CARD_ACCEPT_EXT_ID_CODE": "COM_A", "ORIGEN": "atmpos_trxs", "N_MTI": 200}
```

ABR (notificação de dispositivo) e VU (evento de segurança) chegam do mesmo jeito, em `/v1/abr` e `/v1/vu`. Cada
endpoint tem uma versão `/lote`, que recebe uma lista de até 10 mil eventos.

### 1.2 Validação e hora local

Os modelos de `api/models.py` (pydantic) fazem três coisas antes de qualquer acesso ao banco:

1. **Validam tipos e tamanhos.** Monto com no máximo 2 casas, códigos com tamanho máximo, ID obrigatório.
   Um corpo inválido volta com 422, sem tocar o Oracle. Colunas que a POC não usa (`CASO`, `COMENTARIO_CASO`…)
   são ignoradas; texto vazio vira nulo.
2. **Levam o instante para a hora local de `FS_ZONE`.** O Excel mede as janelas em hora local, sem fuso.
   `"2026-09-30T14:58:00Z"` vira `2026-09-30 11:58:00` (Santiago, UTC−3); um instante sem fuso já é tomado como
   local.
3. **Arredondam ao segundo**, como o `ROUND(... * 86400)` do Excel. As janelas são medidas em segundos inteiros.

### 1.3 Classificação: o que não depende da compra avaliada

Várias condições das variáveis dependem só da própria TRX: se ela entra no cálculo (base), se é aprovada, se a
tarjeta estava presente, se é nacional, a faixa horária. Elas são calculadas **uma vez, na gravação**
(`domain/events.py`, `TrxFlags`), e guardadas como colunas 0/1. A leitura não precisa reavaliar listas de
modos de entrada nem procurar `WEBPAY` no nome a cada consulta.

| Coluna | Significa | Regra (Excel) |
|---|---|---|
| `base` | entra em alguma janela | `ORIGEN = 'atmpos_trxs'` e `MTI = 200` |
| `ap` | aprovada global | base, `PCODE` em {3000, 2000, 6000} e resposta `'00'` |
| `re` | rechazada global | base, PCODE global e resposta diferente de `'00'` (nula conta como rechazada) |
| `ap_aus` | aprovada com tarjeta ausente (V7) | `ap` e modo de entrada fora da lista de presentes |
| `re_npd` | rechazada nacional, presente, diurna (V8) | `re`, (país 152 ou nome com `WEBPAY`), presente, hora 06–14 |
| `ap_mad` | aprovada de madrugada (V9) | `ap` e hora 00–05 |

O que **depende** da compra avaliada (as janelas de tempo, "mesmo MCC", "mesmo comercio") não pode ser
pré-calculado: fica para a leitura.

### 1.4 O INSERT

`db/rows.py` monta um `INSERT` por fonte, com binds posicionais e os tipos declarados antes
(`setinputsizes`), e o store da API executa com `autocommit`:

```sql
INSERT INTO trx (id_trx, cliente, ts, fecha_informacion, monto, response_code, pcode, entry_mode, pais,
                 nombre_comercio, mcc, comercio, origen, mti, base, ap, re, ap_aus, re_npd, ap_mad)
VALUES (:1, :2, ..., :20)
```

No Oracle, uma gravação de TRX faz:

1. **Escolhe a partição pelo dia de `ts`.** As tabelas são particionadas por intervalo diário
   (`INTERVAL (NUMTODSINTERVAL(1, 'DAY'))`); a partição de um dia novo é criada sozinha na primeira gravação.
2. **Grava a linha** no bloco da partição.
3. **Atualiza os índices:** `TRX_PK` (`id_trx`) e o índice de leitura `TRX_CLI_TS_IX`
   (`cliente, ts, base, ap, re, ap_aus, re_npd, ap_mad, mcc, comercio, monto`). Os dois são **globais** (uma árvore
   B só, para a tabela inteira; seção 2.7 explica por quê).
4. **Commit:** o redo da transação (~1,4 KB) precisa estar em disco antes da resposta. É a espera
   `log file sync`, e é o que limita a ingestão quando o disco é lento (seção 3).

ABR e VU são iguais, com o índice de leitura `(cliente, ts, valido)` / `(cliente, ts, cpass)` e um índice único
em `id_evento`.

### 1.5 Idempotência: o mesmo evento duas vezes

- **TRX:** a chave é `ID_TRX`. Uma segunda gravação do mesmo ID viola a PK (`ORA-00001`); a API captura o erro e
  responde **200** com `"duplicada": true`, em vez de 201. Reenviar um lote ou reimportar um arquivo não duplica
  nada.
- **ABR e VU:** a fonte não tem chave natural, e o Excel **conta duas vezes** um evento repetido idêntico. Por
  isso o conteúdo não é deduplicado. Se o cliente mandar um `ID_EVENTO`, o índice único
  `ABR_EVENTO_UX`/`VU_EVENTO_UX` torna a gravação idempotente por ele. Sem `ID_EVENTO`, a coluna fica nula, e o
  Oracle não indexa linhas com a chave toda nula: o índice não custa nada para quem não o usa.

### 1.6 Lotes

`POST /v1/<fonte>/lote` grava a lista num único `executemany(..., batcherrors=True)` e um commit. Com
`batcherrors`, uma linha com erro não derruba as outras: o Oracle devolve a lista de posições que falharam. A API
separa as duplicadas (`ORA-00001`) dos erros de verdade e responde:

```json
{"recebidas": 1000, "novas": 998, "duplicadas": 2, "erros": []}
```

É o caminho do `importar --modo api` e da carga de histórico. O `backfill` e o `importar --modo direto` usam a
mesma gravação em lotes de 5 mil, direto no banco, sem HTTP.

### 1.7 Retenção

A variável mais longa olha 92 dias para trás. O `job-diario` remove as partições inteiramente mais antigas que
93 dias com `ALTER TABLE ... DROP PARTITION ... UPDATE GLOBAL INDEXES`. Apagar um dia é descartar um segmento,
sem `DELETE` linha a linha. As entradas dos índices globais que apontavam para a partição são limpas depois, em
segundo plano (manutenção assíncrona de índice global, desde o Oracle 12c).

---

## Parte 2 — Leitura

### 2.1 O exemplo

O cliente `C1` faz uma compra de CLP 50.000 em `2026-09-30 12:00:00`, MCC 5411, comercio `COM_A`. Essa é a
**T0**. A história dele:

| TRX | Instante | Antes de T0 | Resposta | Modo | MCC | Comercio | Monto | Classificação |
|---|---|---:|---|---|---|---|---:|---|
| a | 30/09 11:58:00 | 2 min | 00 | 9 (presente) | 5411 | COM_A | 20.000 | `ap` |
| b | 30/09 10:30:00 | 1 h 30 | 00 | 81 (ausente) | 5812 | COM_B | 80.000 | `ap`, `ap_aus` |
| c | 30/09 03:15:00 | 8 h 45 | 00 | 9 | 5411 | COM_A | 15.000 | `ap`, `ap_mad` |
| d | 30/09 08:00:00 | 4 h | 05 | 9 | 5411 | COM_A | 7.000 | `re`, `re_npd` |
| e | 29/09 09:00:00 | 27 h | 51 | 9 | 5411 | COM_A | 12.000 | `re`, `re_npd` |
| f | 15/08 14:00:00 | 46 dias | 00 | 9 | 5411 | COM_A | 100.000 | `ap` |
| g | 30/09 11:00:00 | 1 h | 00 | 9 | 5411 | COM_A | 999.999 | nenhuma: `ORIGEN = 'otro'`, fora da base |

Todas são nacionais (país 152). Além delas: duas notificações ABR válidas no mesmo segundo (30/09 11:30:00,
dispositivos `DEV_1` e `DEV_2`) e uma em 10/09; três eventos VU: CPASS (999901) em 20/09, outro tipo (999910) em
25/09 e CPASS (999903) exatamente às 12:00:00 de 30/09.

### 2.2 Os limites das janelas

A aplicação converte a T0 em limites de instante, em segundos, e passa como binds:

| Bind | Valor no exemplo | Uso |
|---|---|---|
| `:cli` | `C1` | cliente |
| `:t0` | 30/09 12:00:00 | fim da janela do VU, que **inclui** o segundo da T0 (`0 ≤ delta`) |
| `:hi` | 30/09 11:59:59 | fim das janelas de TRX e ABR, que **excluem** o segundo da T0 (`1 ≤ delta`) |
| `:lo5` | 30/09 11:55:00 | início da janela de 5 min |
| `:lo1h` | 30/09 11:00:00 | início da janela de 1 h |
| `:lo3h` | 30/09 09:00:00 | início da janela de 3 h |
| `:lo24` | 29/09 12:00:00 | início da janela de 24 h |
| `:lo92` | 30/06 12:00:00 | início da janela de 92 dias (7.948.800 s, rolante, não calendário) |
| `:mcc`, `:com` | 5411, `COM_A` | filtros "mesmo MCC" e "mesmo comercio" |

A própria compra avaliada, se estiver gravada, cai em `delta = 0` e fica fora das janelas de TRX.

### 2.3 A consulta

Um único `SELECT`, gerado por `db/read_sql.py`, com uma subconsulta por fonte:

```sql
SELECT /* sentinela.leitura */ t.*, a.*, v.*
FROM (                                                       -- (A) TRX
  SELECT /*+ INDEX(x trx_cli_ts_ix) */
         COUNT(CASE WHEN x.ts >= :lo24 THEN 1 END)                     AS n24,      -- janela inteira
         ...
         COUNT(CASE WHEN x.ap = 1 AND x.ts >= :lo24 THEN 1 END)        AS f1_24_cnt,
         COUNT(CASE WHEN x.ap = 1 AND x.ts >= :lo24
                     AND x.monto IS NULL THEN 1 END)                   AS f1_24_nnull,
         SUM  (CASE WHEN x.ap = 1 AND x.ts >= :lo24 THEN x.monto END)  AS f1_24_sum,
         MAX  (CASE WHEN x.ap = 1 AND x.ts >= :lo24 THEN x.monto END)  AS f1_24_max,
         ...                                                         -- 10 filtros × 4 números
  FROM trx x
  WHERE x.cliente = :cli AND x.ts >= :lo92 AND x.ts <= :hi AND x.base = 1
) t
CROSS JOIN (                                                 -- (B) ABR
  SELECT /*+ INDEX(a abr_cli_ts_ix) */
         COUNT(DISTINCT CASE WHEN a.ts >= :lo1h THEN a.ts END) AS abr_1h,
         COUNT(DISTINCT a.ts)                                  AS abr_92
  FROM abr a
  WHERE a.cliente = :cli AND a.ts >= :lo92 AND a.ts <= :hi AND a.valido = 1
) a
CROSS JOIN (                                                 -- (C) VU
  SELECT /*+ INDEX(v vu_cli_ts_ix) */
         COUNT(*) AS vu_92, COUNT(CASE WHEN v.cpass = 1 THEN 1 END) AS cpass_92
  FROM vu v
  WHERE v.cliente = :cli AND v.ts >= :lo92 AND v.ts <= :t0
) v
```

**O `WHERE` faz a poda; os `CASE` fazem as janelas.** O `WHERE` de (A) pega só as linhas base do cliente nos
92 dias: no exemplo, a até f (g fica de fora por `base = 0`). Sobre esse conjunto, cada `CASE WHEN ... THEN 1 END`
marca as linhas de uma janela ou filtro; para as outras, o `CASE` devolve `NULL`, e `COUNT`, `SUM` e `MAX`
ignoram `NULL`. Uma única passada pelas linhas calcula todas as janelas ao mesmo tempo (agregação condicional).

### 2.4 Por que o resultado é sempre uma linha

**Um `SELECT` com funções de agregação e sem `GROUP BY` devolve sempre exatamente uma linha**, com 0, 6 ou 10 mil
linhas de entrada. Um cliente sem nenhuma TRX recebe `COUNT = 0` e `SUM`/`MAX = NULL`, e a linha continua lá. Os
dois `CROSS JOIN` juntam três resumos de uma linha cada: 1 × 1 × 1 = 1 linha, com 50 colunas. O `CROSS JOIN` só
é seguro por isso; se alguma subconsulta tivesse `GROUP BY`, ele multiplicaria linhas.

No exemplo, a linha que volta tem, entre outros:

| Agregado | Linhas que contam | Valor |
|---|---|---|
| `n24` (base em 24 h) | a, b, c, d | 4 |
| `n92` (base em 92 d) | a, b, c, d, e, f | 6 |
| `n3h` | a, b | 2 |
| `n24_mcc` / `n92_mcc` (MCC 5411) | a, c, d / a, c, d, e, f | 3 / 5 |
| `n5_com` (5 min, COM_A) | a | 1 |
| `f1_24` (aprovadas, 24 h): cnt, nnull, sum | a, b, c | 3, 0, 115.000 |
| `f1_92` (aprovadas, 92 d): sum | a, b, c, f | 215.000 |
| `f2_24` / `f2_92` (aprovadas, MCC 5411): sum | a, c / a, c, f | 35.000 / 135.000 |
| `f3` (rechazadas, 24 h): cnt, sum | d | 1, 7.000 |
| `f4` (aprovadas, 5 min, COM_A): cnt | a | 1 |
| `f7` (aprovadas ausentes, 3 h): max | b | 80.000 |
| `f8` (rechazadas nac. pres. diurnas, 92 d): cnt, sum | d, e | 2, 19.000 |
| `f9_24` / `f9_92` (aprovadas de madrugada): max | c / c | 15.000 / 15.000 |
| `abr_1h` / `abr_92` (segundos distintos válidos) | 11:30:00 / 11:30:00 e 10/09 | 1 / 2 |
| `vu_92` / `cpass_92` | os três / 20/09 e 12:00:00 | 3 / 2 |

As duas notificações ABR das 11:30:00 contam **uma vez**: `COUNT(DISTINCT a.ts)` conta segundos, não linhas. O VU
das 12:00:00 conta porque a janela do VU inclui o segundo da T0.

### 2.5 Dos agregados às variáveis

A consulta devolve números brutos, e `domain/variables.py` faz as contas. Por que não calcular as razões no SQL?
Porque as regras de NULL do Excel dependem de mais do que o filtro da variável. Para cada filtro, a consulta
devolve **quatro** números (contagem, quantos têm monto nulo, soma e máximo), e para cada janela, a **contagem
de linhas base**. Com eles, a regra de cada fórmula é aplicada exatamente, sem uma segunda ida ao banco.

A regra mais comum, chamada aqui de **soma protegida**: a soma de um filtro é `NULL` se a janela inteira, tirando
as linhas do filtro com monto nulo, ficar vazia; senão, é a soma (0 se nenhuma linha passar no filtro). Ela
distingue "não houve transação" (NULL) de "houve, mas nenhuma aprovada" (0).

No exemplo:

| Variável | Conta | Resultado |
|---|---|---:|
| V1 aprovadas 24 h ÷ 92 d | 115.000 ÷ 215.000 | 0,5349 |
| V2 idem, mesmo MCC | 35.000 ÷ 135.000 | 0,2593 |
| V3 média das rechazadas em 24 h | 7.000 ÷ 1 | 7.000 |
| V4 aprovadas em 5 min no mesmo comercio | contagem | 1 |
| V5 ABR 1 h ÷ 92 d | 1 ÷ 2 | 0,5 |
| V6 CPASS ÷ VU em 92 d | 2 ÷ 3 | 0,6667 |
| V7 maior aprovada ausente em 3 h | `MAX(0, 80.000)` | 80.000 |
| V8 média das rechazadas nac. pres. diurnas em 92 d | 19.000 ÷ 2 | 9.500 |
| V9 maior aprovada de madrugada 24 h ÷ 92 d | 15.000 ÷ 15.000 | 1,0 |
| V10 monto da T0 ÷ denominador de V1 | 50.000 ÷ 215.000 | 0,2326 |

A TRX g, de CLP 999.999, não aparece em nenhuma conta: está fora da base. Esses valores foram conferidos contra
a referência transcrita das fórmulas do Excel (`verification/reference.py`).

### 2.6 Nulo igual a nulo

O Excel compara o MCC como texto, com a chave `"NULL"` para vazio: MCC nulo na T0 casa com MCC nulo na transação.
O mesmo vale para o comercio. Em SQL, `x.mcc = :mcc` é falso (na verdade, desconhecido) para nulo contra nulo.
A consulta usa `DECODE(x.mcc, :mcc, 1, 0) = 1`, porque o `DECODE` trata dois nulos como iguais.

### 2.7 Índices e plano: por que a leitura é uma descida só

Cada subconsulta lê **só o índice**, nunca a tabela:

| Índice | Colunas | Serve a |
|---|---|---|
| `TRX_CLI_TS_IX` | `cliente, ts, base, ap, re, ap_aus, re_npd, ap_mad, mcc, comercio, monto` | (A) |
| `ABR_CLI_TS_IX` | `cliente, ts, valido` | (B) |
| `VU_CLI_TS_IX` | `cliente, ts, cpass` | (C) |

O índice é **coberto**: tem todas as colunas que a subconsulta usa, então o Oracle não precisa ir à tabela
(nenhum `TABLE ACCESS` no plano). E é **global**: uma única árvore B para a tabela inteira, ordenada por
`(cliente, ts)`. Todas as entradas de 92 dias de um cliente ficam **contíguas**. O Oracle desce a árvore uma vez,
até `(C1, 30/06 12:00:00)`, e segue as folhas em ordem até `(C1, 30/09 11:59:59)`. É um `INDEX RANGE SCAN`, e o
número de partições da tabela não muda nada nele.

As partições diárias servem **só à retenção** (seção 1.7). Alternativas consideradas:

| Desenho | Descidas de índice por leitura | Retenção |
|---|---|---|
| Partição diária + índice **local** (um índice por partição) | ~93, uma por partição | drop sem tocar índice global |
| Partição mensal + subpartição hash por cliente + índice local | ~4, uma por mês | em degraus de um mês: guarda até ~4 meses para cobrir 92 dias |
| **Partição diária + índice global coberto** (atual) | **1** | drop diário com `UPDATE GLOBAL INDEXES` |

Um índice global também espalha as gravações pelo índice inteiro (cada cliente cai num lugar). Testamos se isso
pesava na ingestão: com o mesmo número de commits por segundo do modelo anterior (que usava índice local), a
latência de gravação foi a mesma.

O `backfill` confere o plano real da leitura (`db/plan.py`): três `INDEX RANGE SCAN` nos três índices e nenhum
`TABLE ACCESS`. Plano diferente invalida a rodada. Cada subconsulta leva a hint `INDEX` do seu alias, no bloco
onde o alias é definido (no `SELECT` externo o Oracle a ignoraria sem aviso). Motivo: num teste no ATP com o
modelo anterior, um hard parse escolheu `TABLE ACCESS STORAGE FULL` nas partições recentes, e cada leitura passou
a custar ~80 ms de CPU, contra menos de 3 ms com o índice. Com clientes assimétricos e histograma, qualquer hard
parse pode repetir a escolha; a hint deixa o plano estável.

### 2.8 GET por ID_TRX

`GET /v1/features/{ID_TRX}` faz duas chamadas: primeiro busca a T0 pela PK de `TRX`

```sql
SELECT cliente, ts, monto, mcc, comercio, id_trx FROM trx WHERE id_trx = :id
```

(~0,03 ms, 4 blocos) e depois roda a mesma consulta da seção 2.3. Se o ID não existe, responde 404. Com rede
entre a API e o banco, o GET paga a ida e volta duas vezes.

---

## Parte 3 — Quanto custa cada coisa

Medido nas rodadas cheias (100 mil clientes, 92 dias, 600 TRX + 200 ABR + 100 VU gravações por segundo e 600
leituras por segundo), com `V$SQL` (tempo dentro do banco, sem rede):

| Operação | Oracle local (podman no WSL) | ATP, 8 ECPUs |
|---|---:|---:|
| INSERT com commit (TRX) | ~9,6 ms, quase tudo espera de commit | ~1,1 ms (0,9 ms de CPU) |
| Leitura das 10 variáveis | ~3 ms (1,8 ms de CPU) | ~6 ms (5,3 ms de CPU) |
| Busca da T0 por ID | 0,04 ms | 0,03 ms |
| Blocos lidos por leitura | ~170 | ~72 |

- **Na escrita, o que pesa é o commit.** O redo por gravação é pequeno (~1,4 KB), mas cada commit espera o
  disco. No desktop, o disco virtual do WSL teve picos de 0,5 a 8 s na gravação do redo, e a ingestão só passou
  com até ~600 commits por segundo. No ATP, os 900 por segundo passaram sem picos.
- **Na leitura, o que pesa é a CPU.** São 50 agregados condicionais sobre as linhas do cliente; um cliente
  quente (com `--distribution skewed`, 1% dos clientes concentra metade dos eventos) tem milhares de TRX em 92
  dias. A 600 leituras por segundo e ~5 ms de CPU cada, são ~3 CPUs do banco ocupadas só com a leitura: é o
  recurso que limita primeiro no ATP. Com 3 ECPUs a rodada cheia não passou; com 8, passou com folga.
- **A rede soma por chamada.** Com a API num desktop em São Paulo e o ATP na região de São Paulo (~6 ms de ida e
  volta), a coluna DB do relatório mostrou ~5,8 ms por gravação e ~11 ms por leitura POST (~17 ms no GET, que faz
  duas chamadas). Com a API na mesma região do banco, sobra perto do tempo de banco da tabela.
