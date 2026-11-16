# Definição das 10 variáveis do modelo XGBoost de fraude (versão `excel-2026-11-13`)

Fonte: fórmulas do Excel `docs/artifacts/Validacion_variables_POC_Fraude_ML_casos_sinteticos.xlsx`, conforme decidido para a POC. Contexto e discrepâncias: `docs/artifacts/Especificacion_Fraude_XGBoost_y_contexto.md`.

> **Definição provisória.** O Excel não é o SQL de treino. As discrepâncias com o Word (seção 6) continuam abertas e só serão resolvidas com o código ou SQL que gerou as variáveis do treino.

A implementação de referência é `src/sentinela/verification/reference.py`, uma transcrição das fórmulas. `tests/unit/test_reference_excel.py` confere os 60 valores (6 casos × 10 variáveis) contra os valores calculados pelo Excel, extraídos para `tests/fixtures/excel_casos.json` por `scripts/extrair_excel.py`.

## 1. Entrada

Para cada compra avaliada (T0): cliente `N_ID_CUSTOMER`, instante `D_TRANSMISSION_DATE_AND_TIME`, monto `F_AMOUNT_TRANSACTION`, MCC `N_MERCHANT_TYPE` e comercio `S_CARD_ACCEPT_EXT_ID_CODE`. A história é a das três fontes do mesmo cliente:

| Fonte | Colunas usadas |
| --- | --- |
| TRX (`TRANSACCION_FINANCIERA`) | `N_ID_CUSTOMER`, `ID_TRX`, `D_TRANSMISSION_DATE_AND_TIME`, `F_AMOUNT_TRANSACTION`, `S_RESPONSE_CODE`, `N_PCODE`, `N_ENTRY_MODE`, `N_ACQUIRING_INSTITUTION_COUNTRY_CODE`, `S_CARD_ACCEPTOR_NAME`, `N_MERCHANT_TYPE`, `S_CARD_ACCEPT_EXT_ID_CODE`, `ORIGEN`, `N_MTI` |
| ABR (`NOTIFICACION_ABR`) | `N_ID_CUSTOMER`, `D_TRANSMISSION_DATE_AND_TIME` e os atributos E:N (`S_LENGUAGE` … `S_RANDOM_SEED`), só para validade |
| VU (`NOTIFICACION_VU`) | `N_ID_CUSTOMER`, `D_TRANSMISSION_DATE_AND_TIME`, `N_TRANSACTION_TYPE` |

`N_ID_CUSTOMER` e `ID_TRX` são texto nos casos sintéticos (`SYN_CLI_01`, `'900000001'`): o schema os guarda como `VARCHAR2`.

## 2. Instantes e janelas

- Segundos: `ROUND((fecha − 1970-01-01) × 86400)`, sobre a hora **local** sem fuso. A API converte instantes com fuso para `FS_ZONE` (default `America/Santiago`) e interpreta instantes sem fuso como já locais.
- `delta = T0 − instante`, em segundos inteiros.

| Janela | Condição |
| --- | --- |
| TRX 5 min / 3 h / 24 h / 92 d | `1 ≤ delta ≤ 300 / 10800 / 86400 / 7948800` |
| ABR 1 h / 92 d | `1 ≤ delta ≤ 3600 / 7948800` |
| VU 92 d | `0 ≤ delta ≤ 7948800` (inclui o segundo do T0) |

Consequências: a própria T0 (delta 0) e qualquer TRX no mesmo segundo ficam fora; eventos posteriores a T0 (delta < 0) nunca entram, o que permite vários T0 por cliente e ingestão fora de ordem.

## 3. Classificação de cada TRX

| Atributo | Regra do Excel |
| --- | --- |
| base | `ORIGEN = 'atmpos_trxs'` e `N_MTI = 200`. Toda janela TRX exige base |
| global_tx | `N_PCODE ∈ {3000, 2000, 6000}` |
| aprovada_gl | base e global_tx e `S_RESPONSE_CODE = '00'` |
| rechazada_gl | base e global_tx e `S_RESPONSE_CODE ≠ '00'`, **incluindo nulo** |
| presente | `N_ENTRY_MODE ∈ {9,20,21,22,23,50,51,52,53,70,71,72,73,800,801,802,803,900,901,902,903,910,911,912,913}`; nulo ou fora da lista = ausente |
| nacional | país 152 **ou** `S_CARD_ACCEPTOR_NAME` contém `WEBPAY` (sensível a maiúsculas, como `FIND`) |
| franja | hora local 00–05 madrugada, 06–14 diurno, 15–23 tarde-noche |
| mismo_mcc | chave do MCC igual à do T0; MCC nulo vira `'NULL'`, então nulo = nulo |
| mismo_comercio | comercio igual ao do T0; nulo = nulo |

## 4. As variáveis

Notação, sempre sobre TRX base do cliente:
- `N(j)`: linhas na janela j (qualquer pcode e resposta).
- `Nnull(f)`: linhas que satisfazem o filtro f com monto nulo.
- `S(f)`, `C(f)`, `MAX0(f)`: soma, contagem e `MAX(0, máximo)` dos montos não nulos com filtro f (`MAX0` de nada = 0).
- `gate(N, f) = NULL se N − Nnull(f) = 0, senão S(f)` (a soma vale 0 quando há linhas na janela mas nenhuma passa no filtro).
- `ratio(a, b) = NULL se a ou b é NULL ou b = 0, senão a / b`.

| # | Nome | Filtro f | Valor |
| --- | --- | --- | --- |
| V1 | `CLI_GL_LAST_24_HOUR_E_92_DAY_AMOUNT_TRX_APPROVED` | aprovada_gl | `ratio(gate(N24, 24h∧f), gate(N92, 92d∧f))` |
| V2 | `CLI_MCC_GL_LAST_24_HOUR_E_92_DAY_AMOUNT_TRX_APPROVED` | aprovada_gl ∧ mismo_mcc | como V1, mas `N24`/`N92` contam só linhas do mesmo MCC |
| V3 | `CLI_GL_LAST_24_HOUR_PROM_AMOUNT_TRX_NOT_APPROVED` | 24h ∧ rechazada_gl | `ratio(gate(N24, f), NULL se N24 = 0 senão C(f))`; `C` conta também montos nulos |
| V4 | `CLI_COM_GL_LAST_5_MIN_N_TRX_APPROVED` | 5min ∧ aprovada_gl ∧ mismo_comercio | `0 se não há linhas 5 min do mesmo comercio, senão C(f)` |
| V5 | `CLI_ABR_LAST_1_HOUR_E_92_DAY_DISTINCT_CLIENT` | ABR válido (algum atributo E:N preenchido), distinto por segundo | `NULL se D92 = 0, senão D1h / D92` |
| V6 | `CLI_VU_LAST_92_DAY_R_N_CPASS` | VU 92 d; CPASS = `N_TRANSACTION_TYPE ∈ {999901, 999903}` | `NULL se CPASS = 0 ou total = 0, senão CPASS / total` |
| V7 | `CLI_A_LAST_3_HOUR_AMOUNT_TRX_APPROVED_MAX` | 3h ∧ aprovada_gl ∧ ausente | `0 se N3h − Nnull(f) = 0, senão MAX0(f)` |
| V8 | `CLI_NP_LAST_92_DAY_PROM_AMOUNT_TRX_D_NOT_APPROVED` | 92d ∧ rechazada_gl ∧ nacional ∧ presente ∧ diurno | `ratio(gate(N92, f), NULL se N92 = 0 senão C(f))` |
| V9 | `CLI_GL_LAST_24_HOUR_E_92_DAY_AMOUNT_TRX_M_APPROVED_MAX` | aprovada_gl ∧ madrugada | `ratio(MAX0 de 24h com gate N24, MAX0 de 92d com gate N92)` |
| V10 | `R_AMOUNT_TRX_CLI_GL_92_DAY_AMOUNT_TRX_APPROVED` | o denominador de V1 | `NULL se monto do T0 nulo, senão ratio(monto T0, denominador V1)` |

V5 conta segundos distintos das notificações do **próprio cliente**; não agrupa por dispositivo (ver discrepância 1). V6 devolve NULL, não 0, quando há VU sem CPASS. V4 e V7 nunca são NULL.

## 5. Saída

`null` no JSON corresponde ao `"NULL"` do Excel. Os nomes e a ordem são os da aba `Output` (V1 … V10). Contagens (V4) saem como inteiro; o resto como número. O contrato com o modelo (ordem serializada, tipos, representação de ausentes) ainda precisa ser conferido com o artefato treinado.

## 6. Discrepâncias abertas (Word × Excel)

Implementado: a coluna Excel.

| # | Tema | Word | Excel (implementado) |
| --- | --- | --- | --- |
| 1 | V5 | clientes distintos por identificador ABR (dispositivo, senão semente) | notificações distintas do cliente, por segundo |
| 2 | Início dos 92 d | 00:00 do dia de 92 dias atrás | T0 − 92 × 86400 s |
| 3 | Fim da janela VU | até T0 − 1 s | inclui o segundo do T0 |
| 4 | Madrugada (V9) | 22:00–05:59 | 00:00–05:59 |
| 5 | V6 com VU sem CPASS | 0 | NULL |
| 6 | Nacionalidade em V1 | exige país 152 | sem filtro de país |
| 7 | Nacionalidade em V8 | país 152 | país 152 ou nome com WEBPAY |

Outros pontos em aberto: comparação de texto (o Excel ignora maiúsculas em `=`; a implementação compara exatamente; os casos sintéticos não exercitam a diferença), filtro de terminais citado no Word, duplicatas de `ID_TRX`, montos negativos (o `MAX(0, …)` de V7/V9 os anula, a soma de V1/V3/V8 não) e eventos recebidos com atraso.

## 7. Implementação

| Parte | Onde |
|---|---|
| Classificação de cada TRX, na ingestão | `src/sentinela/domain/events.py` (`TrxFlags`), gravada em colunas de `TRX` |
| Agregados brutos em um statement | `src/sentinela/db/read_sql.py`; explicação em [docs/leitura-oracle.md](../leitura-oracle.md) |
| Razões e regras de NULL | `src/sentinela/domain/variables.py` |
| Mesmo cálculo em memória (testes) | `src/sentinela/domain/memory.py` |
| Referência independente, transcrição literal das fórmulas | `src/sentinela/verification/reference.py` |
| Entradas e saídas dos 6 casos do Excel | `src/sentinela/verification/excel_casos.json`, gerado por `scripts/extrair_excel.py` |
| Cenários extras e conferência pela API | `src/sentinela/verification/scenarios.py` e `equivalence.py` (`sentinela equivalencia --reset`) |

O SQL é conferido contra as duas referências (memória e transcrição do Excel) nos 6 casos, nos cenários extras
e num conjunto aleatório com eventos nas bordas das janelas. Uma mudança de definição (por exemplo, quando o
SQL de treino do modelo for conhecido) entra em `events.py`/`read_sql.py`/`variables.py` e, de forma
independente, em `reference.py`; os testes mostram se as duas versões concordam.
