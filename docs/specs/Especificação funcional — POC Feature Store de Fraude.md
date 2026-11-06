# Especificação funcional — POC Feature Store de Fraude

v2 · Nov 6, 2026 · @Fernando

> A v1 (Java, modos `oracle` e `oracle+valkey`) está na tag `java-v1`. Esta versão só usa Oracle, é escrita em Python e expõe a ingestão e a leitura por HTTP.

## 1. Objetivo e escopo

A POC prova que uma feature store de contadores em janelas de tempo, para detecção de fraude num banco, pode ser servida pelo **Oracle 26ai** com latência adequada a 600 leituras por segundo, sob 600 ingestões por segundo concorrentes, atrás de uma **ingest API** e de uma **query API** HTTP.

O entregável é um benchmark reproduzível. Ele roda primeiro num desktop com podman e depois na OCI (VM + Autonomous Database), com o mesmo código, o mesmo dataset e a mesma carga.

São três componentes:

| Componente | Papel |
| --- | --- |
| Oracle 26ai | Fonte da verdade e store de serving: `TX` e `DAILY_AGG`. Local: container Enterprise. OCI: ATP |
| Container da API | FastAPI: ingest API e query API. Também roda as tarefas de banco (schema, backfill, job diário, equivalência) |
| Container do simulador | Gera o dataset, chama as APIs à taxa alvo, registra a latência e produz o relatório |

Fora de escopo: Kafka real (substituído pelo simulador, que chama a ingest API), modelo de machine learning (o leitor para no vetor de features), autenticação, alta disponibilidade, multi-região, cache na frente do banco e chaves de agregação além de conta (merchant, dispositivo).

Princípio: fazer o essencial. Duas APIs, um banco, logs em arquivo e um gerador de relatório. Sem orquestrador: compose num host só.

## 2. Glossário

| Termo | Significado na POC |
| --- | --- |
| Conta (`account_id`) | Chave de agregação. Inteiro de 1 a N, onde N é parâmetro do gerador. |
| Transação | Evento com `tx_id` (único), `account_id`, `ts` (instante do evento, enviado pelo cliente) e `amount` (valor monetário). |
| Janela | Intervalo de tempo que termina no instante da leitura (`now`), inclusive: 19 janelas fixas (seção 3). |
| Fatia (bucket) | Agregado (`cnt`, `sum`, `min`, `max`) das transações de uma conta em um dia civil fechado. Só as janelas de dias usam fatias; as demais são calculadas sobre as transações. |
| Métrica | Agregação sobre as transações de uma janela: `cnt`, `sum`, `min`, `max`, `avg`. |
| Vetor de features | Saída da leitura: 19 janelas × 5 métricas = 95 valores, em ordem fixa. |
| Dia fechado | Dia civil já encerrado (D-1 para trás), no fuso `FS_ZONE`. |
| Dia corrente | O dia civil de `now`, de 00:00 até `now`. Janelas de dias somam dias fechados e o dia corrente. |

## 3. Regras das features

Cada leitura devolve, para uma conta, 95 valores: 5 métricas para cada uma das 19 janelas abaixo, calculadas no instante da leitura (`now`).

| Grupo | Janelas | Transações incluídas |
| --- | --- | --- |
| Minutos | 1, 5, 10, 15, 30 | Deslizante: `now − N < ts ≤ now` |
| Horas | 1, 2, 3, 5, 6, 9, 12, 24, 48, 72 | Deslizante: `now − N < ts ≤ now` |
| Dias | 7, 31, 62, 92 | Os N−1 dias fechados anteriores a hoje (D−(N−1) a D−1) mais o dia corrente, de 00:00 até `now` |

As janelas deslizantes (minutos e horas) não se alinham a minuto nem a hora: a janela de N cobre exatamente N, seja qual for o instante da consulta. O limite inferior é exclusivo e o superior, inclusivo. A precisão é a do `ts` da transação, o microssegundo, o que atende ao requisito do cliente de precisão ao segundo. A janela de 24 horas cobre as últimas 24 horas, e não o dia corrente.

As janelas de dias seguem o dia civil de `FS_ZONE`. A janela de N dias soma N−1 dias fechados e o que já aconteceu hoje. Por isso cobre entre N−1 e N dias de calendário, conforme a hora da consulta. Essa é a definição do cliente.

Exemplo, para uma consulta em 29/10 às 17:23:40 (hora local):

| Janela | Transações incluídas |
| --- | --- |
| 5m | de 17:18:40 (exclusive) a 17:23:40 (inclusive) |
| 1h | de 16:23:40 a 17:23:40 |
| 2h | de 15:23:40 a 17:23:40 |
| 24h | de 28/10 17:23:40 a 29/10 17:23:40 |
| 72h | de 26/10 17:23:40 a 29/10 17:23:40 |
| 7d | dias fechados de 23/10 a 28/10, mais 29/10 de 00:00 a 17:23:40 |
| 31d | dias fechados de 29/09 a 28/10, mais hoje até 17:23:40 |
| 62d | dias fechados de 29/08 a 28/10, mais hoje até 17:23:40 |
| 92d | dias fechados de 30/07 a 28/10, mais hoje até 17:23:40 |

O SQL do Oracle e um modelo de referência independente, que calcula as janelas direto sobre a lista de transações, implementam a mesma definição. O cenário C (seção 10) exige vetores idênticos. A regra do cliente ("sem transação na última hora, contadores até 1h zerados") é consequência: sem transações em `(now − 1h, now]`, as janelas até 1h ficam vazias.

Métricas por janela:

| Métrica | Definição | Sem transações na janela |
| --- | --- | --- |
| `cnt` | Quantidade de transações | 0 |
| `sum` | Soma de `amount` | 0 |
| `min` | Menor `amount` | nulo |
| `max` | Maior `amount` | nulo |
| `avg` | `sum / cnt`, calculada na leitura, nunca armazenada; 4 casas, arredondamento bancário | nulo |

`cnt`, `sum`, `min` e `max` são decomponíveis: o valor de um intervalo é a combinação dos valores das suas partes (soma de somas, mínimo dos mínimos). Por isso a janela de dias combina as fatias dos dias fechados com o agregado do dia corrente, e cada fatia armazena só esses quatro números. As janelas deslizantes são calculadas sobre as transações, porque o limite inferior pode cair em qualquer instante. Nada é materializado por janela.

Ordem do vetor: janelas na ordem da tabela acima (minutos, horas, dias); dentro de cada janela, `cnt`, `sum`, `min`, `max`, `avg`. Nome de cada posição: `<metrica>_<N><unidade>`, por exemplo `cnt_5m`, `sum_24h`, `max_31d`.

## 4. Arquitetura

```
simulador ──HTTP──► API (FastAPI) ──SQL──► Oracle 26ai
  ingestor   POST /v1/transacoes             TX (particionada por dia)
  leitor     GET  /v1/contas/{id}/features   DAILY_AGG (IOT)
```

O simulador só conhece HTTP. A API é a única que fala com o banco. As tarefas de banco (schema, backfill, job diário e equivalência) são comandos de linha que rodam no container da API, porque é ele que tem as credenciais.

### Ingest API

`POST /v1/transacoes` com corpo `{"tx_id": 1, "account_id": 4711, "ts": "2026-10-29T20:23:40.123456Z", "amount": 150.25}`.

| Resposta | Quando |
| --- | --- |
| `201 {"tx_id": 1, "duplicada": false}` | Transação gravada |
| `200 {"tx_id": 1, "duplicada": true}` | O `tx_id` já existia: idempotência, nada muda |
| `422` | Corpo inválido: `account_id` ≤ 0, `amount` ≤ 0 ou com mais de 2 casas, `ts` sem fuso |
| `503` | Banco indisponível |

### Query API

`GET /v1/contas/{account_id}/features[?now=<ISO-8601 com fuso>]` devolve `{"account_id", "now", "features": {"cnt_1m": …, …, "avg_92d": …}}`, com as 95 chaves na ordem da seção 3. Sem `now`, vale o relógio do servidor. O parâmetro serve à verificação (cenário C).

Toda resposta que acessa o banco traz o header `Server-Timing: db;dur=<ms>`, com o tempo da chamada ao Oracle medido na API. Assim o relatório separa o custo do banco do custo do HTTP.

## 5. Componentes

| Programa | Onde roda | O que faz |
| --- | --- | --- |
| `api` | API | Sobe a ingest API e a query API (uvicorn, N processos) |
| `schema` | API | Cria `TX` e `DAILY_AGG`. Idempotente |
| `backfill` | API | Destrutivo: popula o histórico (transações desde 00:00 de D-3 em `TX` e 92 dias de `DAILY_AGG`) e confere o plano de execução real da leitura |
| `job-diario` | API | Fecha o dia D-1 em `DAILY_AGG` e remove partições de `TX` com mais de 93 dias. Idempotente |
| `equivalencia` | API | Cenário C: aplica uma sequência de transações pela ingest API, lê pela query API e compara com o modelo de referência |
| `gerador` | Simulador | Produz o dataset NDJSON (`tx_id`, `account_id`, `amount`), determinístico por `seed` |
| `ingestor` | Simulador | Chama a ingest API à taxa alvo, com `ts = agora` em cada transação. Substitui o consumer Kafka |
| `leitor` | Simulador | Sorteia contas e chama a query API à taxa alvo. Substitui o serviço de scoring |
| `carga` | Simulador | Ingestor e leitor ao mesmo tempo, em processos separados, com o mesmo `--run` |
| `relatorio` | Simulador | Lê os logs e produz TPS, latências e validade da rodada (seção 8) |

O ingestor e o leitor rodam ao mesmo tempo: a leitura é medida sob ingestão concorrente, que é a condição real.

A carga é em laço aberto: a requisição k é agendada para `início + k/TPS`, quer as anteriores tenham terminado ou não. O atraso entre o instante agendado e o envio é medido à parte. É ele que mostra quando o cliente ou a API não dão conta.

Idempotência: `tx_id` é chave primária em `TX`, então reaplicar o mesmo dataset não duplica, e a API responde `200 duplicada`.

## 6. Modelo de dados (Oracle 26ai)

| Tabela | Colunas | Chave e índice | Particionamento |
| --- | --- | --- | --- |
| `TX` | `tx_id`, `account_id`, `ts` (`TIMESTAMP(6)`, UTC), `amount` (`NUMBER(14,2)`) | PK `tx_id`; índice local coberto `(account_id, ts, amount)` | Interval por dia sobre `ts` |
| `DAILY_AGG` | `account_id`, `dia` (`DATE`), `cnt`, `sum_amount`, `min_amount`, `max_amount` | PK `(account_id, dia)`, organizada por índice (IOT) | Nenhum |

A leitura é um único `SELECT`. Ele varre `TX` da conta nas últimas 72 horas (poda para até 4 partições, só no índice coberto) e calcula, com agregados condicionais, as 15 janelas deslizantes e o dia corrente. Em `CROSS JOIN`, agrega `DAILY_AGG` para os dias fechados das 4 janelas de dias. Cada janela de dias combina as duas partes. Detalhes em [leitura-oracle.md](../leitura-oracle.md).

Retenção: `TX` mantém 93 dias; partições mais antigas saem com `DROP PARTITION` no job diário.

## 7. Parâmetros

| Argumento | Programas | Default | Significado |
| --- | --- | --- | --- |
| `--accounts` | gerador, leitor, carga, backfill, equivalencia | 100000 (1000 na equivalência) | Universo de contas |
| `--tps` | ingestor, leitor | 600 | Taxa alvo |
| `--tps-ingest`, `--tps-read` | carga | 600, 600 | Taxas alvo de cada fluxo |
| `--duration` | ingestor, leitor, carga | 300 s | Tempo total |
| `--warmup` | ingestor, leitor, carga | 30 s | Trecho inicial descartado pelo relatório |
| `--concurrency` | ingestor, leitor, carga | 64 | Requisições HTTP em voo por fluxo |
| `--seed` | gerador, leitor, carga, backfill, equivalencia | 42 | Reprodutibilidade dos sorteios |
| `--distribution` | gerador, leitor, carga, backfill | `skewed` | `uniform` ou `skewed` (≈50% das transações em 1% das contas) |
| `--dataset` | gerador, ingestor, carga | `./data/tx.ndjson` | Arquivo de transações |
| `--days` | backfill | 92 | Dias de histórico |
| `--day` | job-diario | D-1 | Dia a fechar |
| `--run` | ingestor, leitor, carga | data/hora UTC | Nome da execução, que agrupa os logs |

| Variável de ambiente | Significado |
| --- | --- |
| `ORACLE_DSN`, `ORACLE_USER`, `ORACLE_PASSWORD` | Conexão com o Oracle (só no container da API) |
| `FS_ZONE` | Fuso do dia civil. Default `America/Sao_Paulo` |
| `API_URL` | Onde o simulador encontra a API |
| `API_WORKERS`, `DB_POOL_SIZE` | Processos do uvicorn e conexões por processo |

## 8. Logs e relatório

O ingestor e o leitor escrevem uma linha por requisição num CSV, mais um `.meta.json` com os parâmetros e a medição de fila. O relatório transforma os arquivos em TPS e percentis. O log bruto é a evidência.

| Coluna | Exemplo | Significado |
| --- | --- | --- |
| `ts_epoch_ms` | `1793296800123` | Início da requisição |
| `program` | `leitor` | `ingestor` ou `leitor` |
| `operation` | `read` | `ingest` ou `read` |
| `account_id` | `4711` | Conta |
| `latency_us` | `1842` | Duração da requisição HTTP vista pelo simulador |
| `db_us` | `612` | Tempo do banco informado pela API (`Server-Timing`) |
| `status` | `ok` | `ok` ou `error` |

O relatório produz, por execução, programa e operação, sem o warmup: operações, erros, duração, TPS alcançado, latência HTTP e de banco (avg, p95, p99, max, percentis nearest-rank sem interpolação) e três verificações de validade:

| Verificação | Critério |
| --- | --- |
| TPS ok | Desvio do TPS alcançado em relação ao alvo menor que 5% |
| Erro ok | Taxa de erro menor que 0,1% |
| Fila ok | No máximo 1% das requisições após o warmup saiu mais de 100 ms depois do instante agendado |

Saídas: `report.md` (uma tabela por execução), `report.csv` e `tps-series.csv` (operações por segundo, para achar quedas).

## 9. Ambientes

| | Desktop | OCI |
| --- | --- | --- |
| Banco | Container Oracle 26ai Enterprise (profile `local-db` do compose) | Autonomous Database (ATP) 26ai, private endpoint |
| API e simulador | Containers da mesma imagem, no compose | Os mesmos, numa VM Linux com podman |
| Subir | `scripts/ambiente.sh up` | `scripts/ambiente.sh up --oci` |

Os scripts são bash, porque a OCI não roda Windows. Os números locais servem para validar a corretude e o comportamento. Os de referência saem da rodada na OCI. O passo a passo da OCI está em [oci.md](../oci.md).

Escala de referência: 100 mil contas, 92 dias de histórico, ingestor e leitor a 600 TPS cada por 5 minutos.

## 10. Cenários e critérios de aceitação

| Cenário | Carga | O que prova |
| --- | --- | --- |
| A. Oracle atrás da API | Ingestor 600 TPS + Leitor 600 TPS, 5 min | Latência de leitura e de ingestão, separando HTTP e banco |
| C. Equivalência | Sem carga: 1.000 contas, 93 dias de transações, leituras no mesmo instante lógico | API = modelo de referência nas 95 posições |

Critérios funcionais:

- [ ] A query API devolve 95 valores na ordem da seção 3.
- [ ] Conta sem transações na última hora devolve `cnt = 0` em todas as janelas de minutos e na de 1h.
- [ ] As janelas de dias incluem as transações do dia corrente até `now`.
- [ ] As janelas deslizantes respeitam os limites da seção 3 ao microssegundo: uma transação em `now − N` fica fora e outra 1 µs depois entra; uma transação depois de `now` fica fora.
- [ ] O cenário C passa sem divergência para pelo menos 1.000 contas.
- [ ] Reaplicar a sequência pela ingest API devolve `200 duplicada` e não altera os contadores.
- [ ] O job diário gera `DAILY_AGG` igual ao recálculo direto sobre `TX`.

Critérios de carga: TPS ok, Erro ok e Fila ok em todas as linhas do relatório.

Não há meta de latência absoluta: o cliente não forneceu SLA.

## 11. Premissas e questões abertas

| Premissa | Valor |
| --- | --- |
| Linguagem | Python 3.13 |
| APIs | FastAPI + uvicorn |
| Banco | Oracle 26ai: Enterprise em container no desktop, ATP na OCI |
| Acesso ao Oracle | python-oracledb em modo thin (sem Instant Client), um `SELECT` por leitura |
| Deploy na OCI | VM com compose; Container Instances e OKE ficam para depois |
| Kafka | Abstraído pelo simulador, que chama a ingest API |
| Modelo de ML | Fora; o leitor para no vetor |

| Questão em aberto | Default na POC | Impacto se mudar |
| --- | --- | --- |
| TPS de ingestão (só o de leitura foi informado) | 600, igual ao de leitura | Só `--tps-ingest` |
| SLA de latência p99 do scoring | Nenhum | Vira critério de aceitação |
| Eventos atrasados ou fora de ordem | Aceitos com o `ts` enviado; o job diário fecha D-1 | Reprocessar dias fechados |
| Mesmas janelas para merchant ou dispositivo? | Não | Novos índices e tabelas de agregado |

Questões decididas com o cliente em 29/10/2026, já refletidas na seção 3:

| Questão | Decisão |
| --- | --- |
| Precisão das janelas de minutos e horas | Ao segundo, para todas, de 1m a 72h: janelas deslizantes, sem alinhamento a fatias. A POC usa o microssegundo do `ts` |
| A janela de 24 horas é o dia corrente ou as últimas 24 horas? | As últimas 24 horas deslizantes |
| Janelas de dias incluem o dia corrente? | Sim: N−1 dias fechados mais o dia corrente até `now` (a de 7 dias são 6 dias fechados mais hoje) |
