# Especificação funcional — POC Feature Store de Fraude

Oct 29, 2026 · @Fernando

## 1. Objetivo e escopo

A POC prova que uma feature store de contadores em janelas de tempo, para detecção de fraude em um banco, pode ser servida com latência adequada a 600 leituras por segundo, e mede se o Oracle 26ai sozinho basta ou se o Valkey (OCI Cache) precisa entrar como camada de leitura.

O entregável é um benchmark reproduzível, rodando localmente em podman, que compara dois modos de serving com o mesmo código, o mesmo dataset e a mesma carga:

- Modo `oracle`: ingestão e leitura das features direto no Oracle.
- Modo `oracle+valkey`: ingestão no Oracle e no Valkey; leitura das features no Valkey.

A escolha do modo é um argumento de linha de comando dos programas de teste. O código de domínio não muda entre os modos (arquitetura hexagonal).

Fora de escopo da POC: Kafka real (substituído por um gerador de dados e um ingestor simulado), modelo de machine learning (o leitor para no vetor de features), autenticação, alta disponibilidade, multi-região, e outras chaves de agregação além de conta (merchant, dispositivo).

Princípio: fazer o essencial. Sem framework de aplicação, sem HTTP entre os componentes, sem orquestrador. Programas Java de linha de comando, um banco, um cache, logs em arquivo e um gerador de relatório.

## 2. Glossário

| Termo | Significado na POC |
| --- | --- |
| Conta (`account_id`) | Chave de agregação. Inteiro de 1 a N, onde N é parâmetro do gerador. |
| Transação | Evento com `tx_id` (único), `account_id`, `ts` (instante de ingestão) e `amount` (valor monetário). |
| Janela | Intervalo de tempo que termina no instante da leitura (`now`), inclusive: 19 janelas fixas (seção 3). |
| Fatia (bucket) | Agregado (`cnt`, `sum`, `min`, `max`) das transações de uma conta em um dia civil fechado. Só as janelas de dias usam fatias; as demais são calculadas sobre as transações. |
| Métrica | Agregação sobre as transações de uma janela: `cnt`, `sum`, `min`, `max`, `avg`. |
| Vetor de features | Saída da leitura: 19 janelas × 5 métricas = 95 valores, em ordem fixa. |
| Modo de serving | `oracle` ou `oracle+valkey`; define qual adapter responde a leitura. |
| Dia fechado | Dia civil já encerrado (D-1 para trás), no fuso `FS_ZONE`. |
| Dia corrente | O dia civil de `now`, de 00:00 até `now`. Janelas de dias somam dias fechados e o dia corrente. |

## 3. Regras das features

Cada leitura devolve, para uma conta, 95 valores: 5 métricas para cada uma das 19 janelas abaixo, calculadas no instante da leitura (`now`).

| Grupo | Janelas | Transações incluídas |
| --- | --- | --- |
| Minutos | 1, 5, 10, 15, 30 | Deslizante: `now − N < ts ≤ now` |
| Horas | 1, 2, 3, 5, 6, 9, 12, 24, 48, 72 | Deslizante: `now − N < ts ≤ now` |
| Dias | 7, 31, 62, 92 | Os N−1 dias fechados anteriores a hoje (D−(N−1) a D−1) mais o dia corrente, de 00:00 até `now` |

Janelas deslizantes (minutos e horas) não se alinham a minuto nem a hora: a janela de N cobre sempre exatamente N, seja qual for o instante da consulta. O limite inferior é exclusivo e o superior, inclusivo. A precisão é a do `ts` da transação, o microssegundo, o que atende ao requisito do cliente de precisão ao segundo. A janela de 24 horas cobre as últimas 24 horas, e não o dia corrente.

Janelas de dias seguem o dia civil de `FS_ZONE`. A janela de N dias soma N−1 dias fechados e o que já aconteceu hoje. Por isso cobre entre N−1 e N dias de calendário, conforme a hora da consulta; essa é a definição do cliente.

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

Essa definição é a mesma no Oracle e no Valkey, para que os dois adapters produzam vetores idênticos e o benchmark compare só latência. A regra do cliente ("sem transação na última hora, contadores até 1h zerados") é consequência: sem transações em `(now − 1h, now]`, as janelas até 1h ficam vazias.

Métricas por janela:

| Métrica | Definição | Sem transações na janela |
| --- | --- | --- |
| `cnt` | Quantidade de transações | 0 |
| `sum` | Soma de `amount` | 0 |
| `min` | Menor `amount` | nulo |
| `max` | Maior `amount` | nulo |
| `avg` | `sum / cnt`, calculada na leitura, nunca armazenada | nulo |

`cnt`, `sum`, `min` e `max` são decomponíveis: o valor de um intervalo é a combinação dos valores das suas partes (soma de somas, mínimo dos mínimos). Por isso a janela de dias é a combinação das fatias dos dias fechados com o agregado do dia corrente, e cada fatia armazena só esses quatro números. As janelas deslizantes são calculadas sobre as transações, porque o limite inferior pode cair em qualquer instante. Nada é materializado por janela.

Ordem do vetor: janelas na ordem da tabela acima (minutos, horas, dias); dentro de cada janela, `cnt`, `sum`, `min`, `max`, `avg`. Nome de cada posição: `<metrica>_<N><unidade>`, por exemplo `cnt_5m`, `sum_24h`, `max_31d`.

## 4. Arquitetura hexagonal

O domínio conhece um único contrato, `FeatureStorePort`, com duas operações: `ingest(transacao)` e `read(account_id, now) → vetor de 95 valores`. Dois adapters implementam o contrato; os programas de teste escolhem qual usar pelo argumento `--mode`.

&#91;embedded content: componentes da POC · 4 programas, 1 port, 2 adapters, 2 stores\]

Gerador, Ingestor, Leitor e Job diário são programas de linha de comando que usam a biblioteca em processo (sem HTTP). O `--mode` seleciona o adapter de leitura; a ingestão grava sempre no Oracle e, no modo `oracle+valkey`, também no Valkey. O Relatório (fora do desenho) só lê os arquivos de log que Ingestor e Leitor produzem.

| Modo | `ingest` | `read` |
| --- | --- | --- |
| `oracle` | Adapter Oracle | Adapter Oracle |
| `oracle+valkey` | Adapter Oracle e Adapter Valkey, em sequência no mesmo processo | Adapter Valkey |

O domínio contém a definição das 19 janelas e 5 métricas (seção 3) e a montagem do vetor em ordem fixa. Nenhum adapter redefine janelas: o Adapter Oracle traduz a definição em SQL e o Adapter Valkey em filtro sobre as transações e fatias que lê. Um teste de contrato roda a mesma sequência de transações contra os dois adapters e exige vetores idênticos.

Nota sobre o Kafka real: em produção os dois sinks seriam consumer groups independentes sobre o mesmo tópico, cada um idempotente. Na POC, com o Kafka abstraído, o Ingestor grava nos dois stores em sequência; isso é aceitável para medir latência, não é o desenho de produção.

## 5. Componentes

Seis programas Java de linha de comando, todos compartilhando a biblioteca da seção 4. Cada um faz uma coisa.

| Programa | O que faz | Entrada | Saída |
| --- | --- | --- | --- |
| Gerador | Produz o dataset de transações, determinístico por `seed`. Distribuição de contas configurável: uniforme ou concentrada (poucas contas com muitas transações, como em fraude real). | Parâmetros | Arquivo NDJSON, uma transação por linha: `tx_id`, `account_id`, `amount` |
| Ingestor | Lê o dataset e aplica as transações nos stores à taxa alvo (TPS), atribuindo `ts = agora` a cada uma. Substitui o consumer Kafka. Mede a latência de cada `ingest`. | Dataset, `--mode`, `--tps`, `--duration` | Log de ingestão |
| Leitor | Sorteia contas do mesmo universo e chama `read` à taxa alvo, montando o vetor de 95 valores. Substitui o serviço de scoring; não chama modelo. Mede a latência de cada `read`. | `--mode`, `--tps`, `--duration`, `--accounts` | Log de leitura |
| Backfill | Popula histórico antes do benchmark: transações com `ts` no passado (últimas 72h) direto no Oracle, e `DAILY_AGG` para os 92 dias fechados. No modo `oracle+valkey`, reconstrói o Valkey a partir do Oracle. | `--mode`, `--days`, `--seed` | Stores populados |
| Job diário | Agrega no Oracle as transações do dia D-1 em `DAILY_AGG`. No modo `oracle+valkey`, grava a fatia de dia no hash de cada conta que transacionou e remove fatias com mais de 91 dias. Executado sob demanda na POC. | `--mode`, `--day` | `DAILY_AGG` atualizada, Valkey atualizado |
| Relatório | Lê os logs de Ingestor e Leitor e produz TPS e latências (seção 8). | Diretório de logs | Arquivo Markdown e CSV |

Ingestor e Leitor rodam ao mesmo tempo durante o benchmark: a leitura é medida sob ingestão concorrente, que é a condição real. Rodar um sem o outro serve só para depuração.

O Ingestor usa `ts = agora` em vez do timestamp do dataset para que as janelas deslizantes sejam exercitadas em tempo real durante a execução. O Backfill é quem coloca `ts` no passado.

Idempotência: `tx_id` é chave primária no Oracle, então reaplicar o mesmo dataset não duplica. No Valkey, a transação só entra no sorted set da conta se o `tx_id` ainda não estiver lá, o que dá a mesma garantia dentro da retenção de 73 horas. Reentrega de transações mais antigas fica para a spec técnica de produção.

## 6. Modelo de dados

O Oracle é a fonte da verdade e guarda as transações; o Valkey é derivado e guarda, por conta, as transações recentes (72 horas e 1 de folga) e as fatias dos dias fechados. Nenhum dos dois armazena valores por janela.

### Oracle 26ai

| Tabela | Colunas | Chave e índice | Particionamento |
| --- | --- | --- | --- |
| `TX` | `tx_id`, `account_id`, `ts` (timestamp), `amount` (número) | PK `tx_id`; índice local em `(account_id, ts)` | Interval por dia sobre `ts`, sem subpartição |
| `DAILY_AGG` | `account_id`, `dia` (date), `cnt`, `sum`, `min`, `max` | PK `(account_id, dia)` | Nenhum |

Leitura no Adapter Oracle: um único `SELECT` que varre `TX` da conta nas últimas 72 horas (poda para 4 partições) e calcula, com `COUNT`, `SUM`, `MIN`, `MAX` condicionais, as 15 janelas deslizantes e o agregado do dia corrente, mais um `SELECT` em `DAILY_AGG` para os dias fechados das 4 janelas de dias. Cada janela de dias é a combinação das duas partes. Os limites seguem a seção 3.

Retenção na POC: `TX` mantém 92 dias ou mais (necessário para recalcular `DAILY_AGG` e para o Backfill); partições antigas saem com `DROP PARTITION`.

### Valkey 7.2

Duas chaves por conta, sem TTL: um sorted set com as transações das últimas 72 horas, que atende as janelas deslizantes e o dia corrente, e um hash com as fatias dos dias fechados.

| Elemento | Formato | Exemplo | Retenção |
| --- | --- | --- | --- |
| Transações recentes | sorted set `fs:{<account_id>}:tx` | `fs:{4711}:tx` | Últimas 73 horas: as 72 da maior janela e 1 de folga |
| Score de uma transação | `ts` em microssegundos desde o epoch, UTC | `1793305420123456` | Segue o membro |
| Membro de uma transação | `<tx_id>\|<amount>` | `98231\|450.00` | — |
| Dias fechados | hash `fs:{<account_id>}` | `fs:{4711}` | — |
| Fatia de dia | campo `d:<epoch_dia>` | `d:20754` | 91 campos: até 91 dias fechados |
| Valor de uma fatia | `cnt\|sum\|min\|max` | `3\|450.00\|20.00\|300.00` | Segue o campo |

Ingestão no Adapter Valkey: para a transação, incluir o membro no sorted set se o `tx_id` ainda não estiver lá e remover os membros com mais de 73 horas. Essa operação precisa ser atômica por conta; a spec técnica decide entre script Lua e Valkey Functions.

Leitura no Adapter Valkey: buscar no sorted set as transações de `(now − 72h, now]` e ler o hash dos dias fechados, e então combinar, para cada janela, as transações e fatias dentro do limite. A combinação pode rodar no cliente Java ou em Lua; a spec técnica decide com base na latência medida.

Tamanho estimado, com o histórico do Backfill (5 transações por conta por dia): cerca de 15 membros por conta no sorted set, e até cerca de 1.000 nas contas quentes do modo `skewed`, mais 91 campos de dia. Com 100 mil contas no ambiente local, menos de 1 GB.

## 7. Parâmetros dos programas

Todos os programas aceitam os argumentos abaixo pela linha de comando; conexões vêm de variáveis de ambiente para não ir parar em logs.

| Argumento | Programas | Valores | Default | Significado |
| --- | --- | --- | --- | --- |
| `--mode` | Ingestor, Leitor, Backfill, Job diário | `oracle`, `oracle+valkey` | `oracle` | Adapter de leitura e sinks de ingestão (seção 4) |
| `--accounts` | Gerador, Leitor, Backfill | inteiro | 100000 | Tamanho do universo de contas |
| `--tps` | Ingestor, Leitor | inteiro | 600 | Taxa alvo de operações por segundo |
| `--duration` | Ingestor, Leitor | segundos | 300 | Tempo total de execução |
| `--warmup` | Ingestor, Leitor | segundos | 30 | Trecho inicial descartado pelo Relatório |
| `--seed` | Gerador, Leitor, Backfill | inteiro | 42 | Reprodutibilidade dos sorteios |
| `--distribution` | Gerador, Leitor | `uniform`, `skewed` | `skewed` | Como as contas são sorteadas |
| `--dataset` | Gerador (saída), Ingestor (entrada) | caminho | `./data/tx.ndjson` | Arquivo de transações |
| `--days` | Backfill | inteiro | 92 | Dias de histórico a gerar |
| `--day` | Job diário | `YYYY-MM-DD` | D-1 | Dia a fechar |
| `--log` | Ingestor, Leitor | caminho | `./logs/<programa>-<mode>-<timestamp>.csv` | Arquivo de log de latência |
| `--threads` | Ingestor, Leitor | inteiro | 8 | Paralelismo do cliente para atingir o TPS alvo |

| Variável de ambiente | Programas | Significado |
| --- | --- | --- |
| `ORACLE_URL`, `ORACLE_USER`, `ORACLE_PASSWORD` | Todos exceto Gerador e Relatório | Conexão JDBC ao Oracle 26ai |
| `VALKEY_URL` | Todos exceto Gerador e Relatório | Conexão ao Valkey; obrigatória só no modo `oracle+valkey` |

O Relatório recebe o diretório de logs como único argumento e agrupa por programa e modo a partir do nome do arquivo e das colunas do log.

## 8. Logs e relatório de benchmark

Ingestor e Leitor escrevem uma linha por operação em um arquivo CSV; o Relatório transforma os arquivos em TPS e percentis. Nada de métricas em memória nem agregação durante a execução: o log bruto é a evidência.

Formato do log, uma linha por operação, sem cabeçalho de texto livre:

| Coluna | Tipo | Exemplo | Significado |
| --- | --- | --- | --- |
| `ts_epoch_ms` | inteiro | `1793296800123` | Instante de início da operação |
| `program` | texto | `reader` | `ingestor` ou `reader` |
| `mode` | texto | `oracle+valkey` | Modo em vigor |
| `operation` | texto | `read` | `ingest_oracle`, `ingest_valkey`, `read` |
| `account_id` | inteiro | `4711` | Conta da operação |
| `latency_us` | inteiro | `842` | Duração em microssegundos, medida em volta da chamada ao port |
| `status` | texto | `ok` | `ok` ou `error` |

No modo `oracle+valkey`, o Ingestor registra duas linhas por transação (`ingest_oracle` e `ingest_valkey`), para que o custo de cada sink apareça separado.

O Relatório produz, para cada combinação de `program`, `mode` e `operation`, descartando o trecho de `--warmup`:

| Medida | Definição |
| --- | --- |
| Operações | Linhas com `status = ok`; erros contados à parte |
| Duração | Do primeiro ao último `ts_epoch_ms` após o warmup |
| TPS alcançado | Operações / duração; comparado ao `--tps` alvo |
| Latência min, max, avg | Sobre `latency_us`, em milissegundos com uma casa decimal |
| Latência p95, p99 | Percentis sobre `latency_us`, sem interpolação (valor observado) |

Saídas: um Markdown com uma tabela por execução e uma tabela lado a lado dos modos quando os dois existirem no diretório, e um CSV com as mesmas linhas para planilha. O Relatório também emite TPS por segundo (série) para detectar quedas durante a execução, em CSV.

## 9. Ambiente local com podman

Tudo roda em uma máquina, em um único pod podman com rede compartilhada. Dois containers de dados ficam de pé durante todo o benchmark; os programas Java sobem como containers de curta duração a partir de uma única imagem.

| Container | Imagem | Papel | Persistência |
| --- | --- | --- | --- |
| `oracle` | Oracle Database 26ai Free, imagem oficial do Oracle Container Registry | Fonte da verdade: `TX`, `DAILY_AGG` | Volume para datafiles; sobrevive a reinícios |
| `valkey` | `valkey/valkey:7.2`, mesma linha de versão recomendada pelo OCI Cache | Online store do modo `oracle+valkey` | Sem persistência; reconstruído pelo Backfill |
| `poc` (uma imagem, vários comandos) | Imagem Java construída da POC | Gerador, Ingestor, Leitor, Backfill, Job diário, Relatório | Volumes montados: `./data` e `./logs` |

O Oracle 26ai Free tem limites de CPU e memória da edição gratuita; os números absolutos de latência locais não representam o ATP-S na OCI. O que a POC local valida é a diferença relativa entre os dois modos e a corretude. Os números de referência saem de uma segunda rodada na OCI, com os mesmos programas e `--mode`.

Escala local recomendada: 100 mil contas, 92 dias de histórico via Backfill, Ingestor e Leitor a 600 TPS cada por 5 minutos. O ambiente deve permitir subir para 300 mil contas sem mudar nada além de `--accounts`.

Ordem de execução de uma rodada:

1. Subir `oracle` e `valkey`; criar o schema (script SQL versionado no repositório).
2. Gerador: produzir o dataset.
3. Backfill no modo da rodada: histórico de 92 dias no Oracle e, se `oracle+valkey`, a reconstrução do Valkey.
4. Ingestor e Leitor em paralelo, mesmo `--mode`, `--tps` e `--duration`.
5. Relatório sobre `./logs`.
6. Repetir 3 a 5 com o outro `--mode`, mesmo dataset e `--seed`, para a comparação lado a lado.

O Job diário é exercitado à parte, fechando um dia do histórico do Backfill, para validar a agregação e a escrita das fatias de dia; não faz parte da janela de medição.

## 10. Cenários de benchmark e critérios de aceitação

A POC é aceita quando os três cenários abaixo rodam até o fim e o Relatório mostra os dois modos lado a lado, com o mesmo dataset e a mesma carga.

| Cenário | Modo | Carga | O que prova |
| --- | --- | --- | --- |
| A. Baseline Oracle | `oracle` | Ingestor 600 TPS + Leitor 600 TPS, 5 min | Latência de leitura e ingestão com um único store |
| B. Oracle + Valkey | `oracle+valkey` | Igual a A | Latência de leitura no Valkey e custo adicional da dupla ingestão |
| C. Equivalência | ambos | Sem carga: mesma sequência de transações aplicada nos dois adapters, leituras no mesmo instante lógico | Vetores idênticos nos dois adapters, para as 95 posições |

Critérios funcionais, todos obrigatórios:

- [ ] `read` devolve 95 valores na ordem da seção 3, em ambos os modos.
- [ ] Conta sem transações na última hora devolve `cnt = 0` em todas as janelas de minutos e na de 1h.
- [ ] Janelas de dias incluem as transações do dia corrente até `now`.
- [ ] Janelas deslizantes respeitam os limites da seção 3 ao microssegundo: uma transação em `now − N` fica fora e outra 1 µs depois entra; uma transação depois de `now` fica fora.
- [ ] Cenário C passa sem divergência para pelo menos 1.000 contas sorteadas.
- [ ] Reaplicar o mesmo dataset no Oracle não altera os contadores (idempotência por `tx_id`).
- [ ] Job diário gera `DAILY_AGG` igual ao recálculo direto sobre `TX` para o dia fechado.
- [ ] Relatório produz TPS alcançado e latência min, max, avg, p95, p99 por programa, modo e operação.

Critérios de carga, para a rodada local:

- [ ] Ingestor e Leitor sustentam o TPS alvo com desvio inferior a 5% após o warmup.
- [ ] Taxa de erro inferior a 0,1% em ambos os modos.

Não há meta de latência absoluta na POC: o cliente não forneceu SLA. O resultado esperado é a tabela comparativa dos modos, que alimenta a decisão de arquitetura (só ATP-S ou ATP-S + OCI Cache).

## 11. Premissas, fora de escopo e questões abertas

Premissas já fixadas, que a spec técnica herda sem rediscutir:

| Premissa | Valor |
| --- | --- |
| Linguagem dos programas | Java (versão LTS corrente definida na spec técnica) |
| Banco | Oracle Database 26ai Free local; ATP-S na OCI |
| Cache | Valkey 7.2, a versão recomendada pelo OCI Cache |
| Acesso ao Oracle | JDBC com UCP; um `SELECT` por leitura, sem PL/SQL obrigatório |
| Acesso ao Valkey | Lettuce |
| Estilo | Hexagonal: um port, dois adapters, seleção por `--mode` |
| Sem framework de aplicação | Programas de linha de comando; sem HTTP, sem DI framework, sem orquestrador |
| Kafka | Abstraído por Gerador + Ingestor |
| Modelo de ML | Fora; o Leitor para no vetor |

Fora de escopo, mesmo que apareça na conversa com o cliente: reentrega e exactly-once (não há Kafka), sharding do Valkey, réplicas e failover, chaves de agregação além de conta, métricas não decomponíveis (percentis, distintos), features point-in-time para treino (fica para o projeto, usando `TX`).

Questões abertas com o cliente e o default assumido na POC:

| Questão | Default na POC | Impacto se mudar |
| --- | --- | --- |
| TPS de ingestão (só o de leitura foi informado) | 600, igual ao de leitura | Só o parâmetro `--tps` do Ingestor |
| SLA de latência p99 do scoring | Nenhum; a POC só compara | Vira critério de aceitação |
| Métricas além de `cnt`: quais exatamente? | `cnt`, `sum`, `min`, `max`, `avg` | Métricas não decomponíveis mudam o modelo de dados |
| Eventos atrasados ou fora de ordem | Não tratados; `ts` é o instante de ingestão | Ingestor passaria a usar o timestamp do evento |
| Mesmas janelas para merchant ou dispositivo? | Não | Multiplica chaves no Valkey e índices no Oracle |

Questões decididas com o cliente em 29/10/2026, já refletidas na seção 3:

| Questão | Decisão |
| --- | --- |
| Precisão das janelas de minutos e horas | Ao segundo, para todas, de 1m a 72h: janelas deslizantes, sem alinhamento a fatias. A POC usa o microssegundo do `ts` |
| A janela de 24 horas é o dia corrente ou as últimas 24 horas? | As últimas 24 horas deslizantes |
| Janelas de dias incluem o dia corrente? | Sim: N−1 dias fechados mais o dia corrente até `now` (a de 7 dias são 6 dias fechados mais hoje) |
