# sentinela — POC Feature Store de Fraude

Benchmark reproduzível de uma feature store para detecção de fraude. Ele mede se o **Oracle 26ai**, atrás de uma **ingest API** e de uma **query API** em FastAPI, serve as variáveis de entrada de um modelo com latência adequada, sob ingestão concorrente.

Nesta branch (`xgboost-10-variaveis`), a leitura devolve as **10 variáveis do modelo XGBoost de fraude** (compras CMR, Banco Falabella) para uma compra avaliada (T0). Elas são calculadas sobre três fontes: **TRX** (transações), **ABR** (notificações de dispositivo) e **VU** (eventos de segurança, CPASS). A definição segue o Excel de validação e está em [docs/specs/variaveis-xgboost.md](docs/specs/variaveis-xgboost.md). O modelo antigo, de 95 features genéricas, continua na branch `python`.

```
sentinela-sim ──HTTP──► sentinela-api (FastAPI) ──SQL──► Oracle 26ai
 gerador, ingestores,    POST /v1/{trx,abr,vu}[/lote]     TRX, ABR, VU
 leitor, relatorio,      POST /v1/features                (partição diária,
 importar --modo api     GET  /v1/features/{ID_TRX}        índices globais cobertos)
```

Os corpos usam os **nomes de coluna das fontes** (`N_ID_CUSTOMER`, `D_TRANSMISSION_DATE_AND_TIME`, `F_AMOUNT_TRANSACTION`…), para que os dados originais entrem sem tradução. Assim, o mesmo programa serve o benchmark sintético e, depois, os dados reais, que chegam pelos endpoints ou pelo `importar`.

> Esta é a v2, em Python e só com Oracle. A v1, em Java, com os modos `oracle` e `oracle+valkey` e scripts PowerShell, está na tag **`java-v1`** (`git switch --detach java-v1`).

Documentação:

- [As 10 variáveis do XGBoost](docs/specs/variaveis-xgboost.md): definição exata, regras de NULL e discrepâncias entre Word e Excel.
- [Especificação funcional](docs/specs/Especificação%20funcional%20—%20POC%20Feature%20Store%20de%20Fraude.md): o quê e por quê (modelo de 95 features; as variáveis atuais estão no documento acima).
- [Especificação técnica](docs/specs/Especificação%20técnica%20—%20POC%20Feature%20Store%20de%20Fraude.md): como (idem).
- [Como a leitura consulta o Oracle](docs/leitura-oracle.md).
- [Rodando na OCI](docs/oci.md): VM + Autonomous Database, com containers.
- [Rodando na OCI sem containers](docs/oci-vm.md): VM + Autonomous Database, direto no Python da VM.

## 1. Requisitos

| Item | Observação |
| --- | --- |
| Podman 5.x + `podman-compose` (ou Docker + `docker compose`) | No Windows, `podman machine` (WSL) com **pelo menos 8 CPUs e 16 GB**: 6 GB para o Oracle, mais a API e o simulador |
| bash | Linux, macOS ou o **Git Bash** no Windows. Os scripts não usam PowerShell |
| ~25 GB de disco | Imagem do Oracle Enterprise (~8 GB) e datafiles |
| Conta Oracle | Para baixar a imagem do Oracle Database Enterprise (seção 2.1) |

**Não é preciso Python no host:** a API, o simulador e os testes rodam em containers. Para desenvolver fora deles, use Python 3.13 e `pip install -e ".[test]"`.

| Imagem | Papel |
| --- | --- |
| `container-registry.oracle.com/database/enterprise:latest` | Oracle AI Database 26ai (release 23.26.x), só no desktop |
| `localhost/sentinela` (construída do `Containerfile`, base `python:3.13-slim`) | Containers `sentinela-api` e `sentinela-sim` |

## 2. Subir no desktop

### 2.1 Acesso à imagem do Oracle (uma vez)

Entre em <https://container-registry.oracle.com>, abra **Database → enterprise**, aceite a licença e faça login no registro:

```bash
podman login container-registry.oracle.com
```

### 2.2 Configuração (uma vez)

```bash
cp .env.example .env.local    # e preencha ORACLE_PWD e ORACLE_PASSWORD
```

| Variável | Significado |
| --- | --- |
| `ORACLE_PWD` | Senha de SYS, SYSTEM e PDBADMIN do Oracle local, definida na criação do banco |
| `ORACLE_DSN` | `oracle:1521/SENTINELA` (o serviço `oracle` do compose). Na OCI, a connect string do ATP |
| `ORACLE_USER` / `ORACLE_PASSWORD` | Usuário da POC (`SENTINELA`) e a senha dele |
| `FS_ZONE` | Fuso da hora local das fontes (janelas, faixas horárias, partições). Default `America/Santiago` |
| `API_WORKERS` / `DB_POOL_SIZE` | Processos do uvicorn e conexões por processo (4 × 8) |
| `API_CPUS` / `SIM_CPUS` | Limite de CPU dos containers da API e do simulador |
| `API_URL` | `http://api:8000`: onde o simulador encontra a API |

Use senhas só com letras e números, com pelo menos 8 caracteres. O `.env.local` fica fora do git.

Os scripts e o compose leem `.env.local`. Para usar outro arquivo, prefixe o comando com `ENV_FILE=`. Por exemplo, quando o `.env.local` aponta para o ATP, `ENV_FILE=.env.podman scripts/ambiente.sh build` usa o Oracle local.

### 2.3 Subir

```bash
scripts/ambiente.sh up
```

O script é idempotente. Ele:
1. sobe o Oracle (volume `sentinela-oradata`) e espera o banco ficar pronto. **Na primeira vez, a criação do banco leva de 10 a 20 minutos**;
2. cria o usuário `SENTINELA` (`db/00_usuario.sql`);
3. constrói a imagem e sobe `sentinela-api` e `sentinela-sim`;
4. cria as tabelas (`db/01_schema.sql`).

Termina com `ambiente pronto`. Para conferir:

```bash
curl -s localhost:8000/health
curl -s -X POST localhost:8000/v1/trx -H 'Content-Type: application/json' -d '{"ID_TRX": "1",
     "N_ID_CUSTOMER": "C42", "D_TRANSMISSION_DATE_AND_TIME": "2026-11-16T12:00:00", "F_AMOUNT_TRANSACTION": 25000,
     "S_RESPONSE_CODE": "00", "N_PCODE": 3000, "N_ENTRY_MODE": 9, "N_MERCHANT_TYPE": 5411,
     "ORIGEN": "atmpos_trxs", "N_MTI": 200}'
curl -si localhost:8000/v1/features/1 | head -20
```

A documentação interativa das APIs fica em <http://localhost:8000/docs>.

| Endpoint | Faz |
| --- | --- |
| `POST /v1/trx`, `/v1/abr`, `/v1/vu` | Grava um evento: 201 se novo, 200 se repetido. TRX é identificada por `ID_TRX`; ABR e VU, pelo `ID_EVENTO` opcional |
| `POST /v1/{trx,abr,vu}/lote` | Grava até 10 mil eventos por chamada. Devolve `recebidas`, `novas`, `duplicadas` e `erros` por posição |
| `POST /v1/features` | Devolve as 10 variáveis da compra enviada no corpo (`N_ID_CUSTOMER`, `D_TRANSMISSION_DATE_AND_TIME`, monto, MCC, comercio) |
| `GET /v1/features/{ID_TRX}` | Devolve as 10 variáveis de uma TRX já gravada; 404 se ela não existir |

Um instante com fuso é convertido para `FS_ZONE`; sem fuso, é tomado como hora local de `FS_ZONE`, como no Excel. ABR e VU sem `ID_EVENTO` não são deduplicados, porque o Excel conta duas vezes um evento repetido.

| Comando | Efeito |
| --- | --- |
| `scripts/ambiente.sh build` | Reconstrói a imagem e recria a API e o simulador, depois de mudar o código |
| `scripts/ambiente.sh down` | Remove os containers; **o volume do Oracle fica** |
| `scripts/ambiente.sh destroy` | Remove os containers **e** o volume do Oracle |

## 3. Testes

```bash
scripts/test.sh tests/unit     # unitários, não dependem do banco
scripts/test.sh                # todos, inclusive integração contra o Oracle local
```

Os unitários conferem a referência (transcrição das fórmulas do Excel) contra os 60 valores da aba Output, o domínio e a API com um store em memória, e todos os cenários de equivalência. Os de integração conferem o SQL contra duas referências independentes, o plano de execução, a equivalência completa pela API, o backfill, o `importar` e a retenção.

> **Atenção:** os testes de integração são **destrutivos**, porque esvaziam `TRX`, `ABR` e `VU`. Por proteção, só rodam se `ORACLE_DSN` apontar para o banco local; contra outro banco, só com `FS_IT_DESTRUCTIVE=1`.

## 4. Rodar o benchmark

### 4.1 Rodada automatizada

```bash
scripts/rodada.sh --run minha-rodada        # ou: ENV_FILE=.env.podman scripts/rodada.sh ...
cat logs/report/report.md
```

O script:
1. gera `data/{trx,abr,vu}.ndjson`, se não existirem ou forem pequenos demais;
2. roda o backfill de 92 dias das três fontes direto no banco (destrutivo) e confere o plano de execução real da leitura;
3. roda os ingestores de TRX, ABR e VU e o leitor em paralelo contra a API;
4. gera o relatório.

Parâmetros:

| Parâmetro | Default |
| --- | --- |
| `--run` | data e hora UTC |
| `--tps-trx` / `--tps-abr` / `--tps-vu` / `--tps-read` | 600 / 200 / 100 / 600 |
| `--fracao-get` | 0,5 |
| `--duration` / `--warmup` | 300 / 30 |
| `--clientes` | 100000 |
| `--trx-dia` / `--abr-dia` / `--vu-dia` | 1 / 0,5 / 0,3 |
| `--concurrency` / `--seed` | 64 / 42 |
| `--sem-backfill` | desligado |

Rodada curta, para testar o ambiente (o backfill leva cerca de 2 minutos):

```bash
scripts/rodada.sh --run curta --tps-trx 100 --tps-abr 30 --tps-vu 20 --tps-read 100 --duration 60 --warmup 10 --clientes 10000
```

O leitor mistura as duas formas de leitura:
- `POST /v1/features`, com uma compra sintética no instante atual;
- na fração `--fracao-get`, `GET /v1/features/{ID_TRX}` de uma TRX que o ingestor postou entre 5 e 65 segundos antes.

O relatório mostra as duas operações separadas, como `features_post` e `features_get`.

Os dados sintéticos têm um perfil por cliente: três MCCs e três comercios favoritos, e um ou dois dispositivos. A mistura de categorias exercita todas as regras das variáveis:
- cerca de 77% de compras aprovadas globais, e resposta nula;
- tarjeta presente e ausente;
- país 152, país 840 e comercios WEBPAY;
- ORIGEN e MTI fora da base, PCODE não global;
- monto nulo e monto zero;
- ABR sem atributos e ABR no mesmo segundo;
- VU com cerca de 10% de CPASS.

Com `--distribution skewed` (default), metade dos eventos e das leituras cai em 1% dos clientes.

### 4.2 Passo a passo

Os programas de banco rodam no container da API. Os de carga rodam no simulador, que monta `./data` e `./logs` como `/data` e `/logs`.

```bash
podman exec sentinela-sim sentinela gerador --clientes 100000 --trx 200000 --abr 66000 --vu 33000
podman exec sentinela-api sentinela backfill --clientes 100000
podman exec sentinela-sim sentinela carga --run r1 --tps-trx 600 --tps-abr 200 --tps-vu 100 --tps-read 600
podman exec sentinela-sim sentinela relatorio /logs
```

O `ingestor` (um por fonte, com `--fonte trx|abr|vu`) e o `leitor` também rodam separadamente, com o mesmo `--run`. Com `--replay`, o ingestor usa o instante do arquivo em vez do instante atual.

### 4.3 Equivalência

Ingere pela API os 6 casos do Excel e os cenários extras pedidos na spec:
- bordas de janela, a ±1 s;
- faixas horárias, incluindo 22h e 23h;
- VU vazio, sem CPASS e com CPASS no segundo do T0;
- ausência de linhas × monto zero × monto nulo;
- MCC e comercio nulos no T0;
- ABR repetido no mesmo segundo e ABR sem atributos;
- vários T0 do mesmo cliente;
- 25 clientes aleatórios com eventos nas bordas.

Cada T0 é lida por POST, e também por GET quando foi ingerida. O resultado é comparado com a referência transcrita das fórmulas do Excel e, nos 6 casos, também com os valores da aba Output. Depois a equivalência reenvia os lotes (tudo deve voltar como duplicado), ingere eventos posteriores a cada T0 e relê tudo: nenhum valor pode mudar.

```bash
podman exec sentinela-api sentinela equivalencia --reset --api-url http://localhost:8000
```

O `--reset` apaga as tabelas. O resultado esperado são só linhas `PASS` (63 hoje) e código de saída 0.

### 4.4 Dados reais: importar

O `importar` carrega a história de uma fonte a partir de um arquivo com os nomes de coluna originais. Aceita NDJSON, JSON (uma lista) ou CSV com cabeçalho; campo vazio no CSV vira nulo.

```bash
# pela API: endpoints de lote, com validação e gravação como em produção
podman exec sentinela-sim sentinela importar --fonte trx --arquivo /data/trx_real.csv --sep ';' --modo api
# direto no Oracle: mesma validação, carga em massa, para históricos grandes
podman exec sentinela-api sentinela importar --fonte abr --arquivo /tmp/abr_real.ndjson --modo direto
```

- Linhas inválidas são contadas, e as 10 primeiras aparecem com o número da linha e o campo. Se houver alguma, o código de saída é 1.
- Reimportar o mesmo arquivo de TRX não duplica nada, porque a chave é `ID_TRX`.
- ABR e VU só são idempotentes se trouxerem `ID_EVENTO`.

### 4.5 Job diário

Remove as partições diárias de `TRX`, `ABR` e `VU` com mais de 93 dias. É idempotente.

```bash
podman exec sentinela-api sentinela job-diario               # D-1
podman exec sentinela-api sentinela job-diario --day 2026-11-05
```

### 4.6 Referência dos programas

`sentinela <programa> --help` lista os argumentos.

| Programa | Container | Argumentos (default) |
| --- | --- | --- |
| `api` | api | `--workers` ($API_WORKERS), `--port` (8000) |
| `schema` | api | `--arquivo` |
| `backfill` | api | `--clientes` (100000), `--dias` (92), `--seed` (42), `--distribution` (`skewed`), `--trx-dia` (1), `--abr-dia` (0,5), `--vu-dia` (0,3) |
| `job-diario` | api | `--day` (D-1) |
| `equivalencia` | api | `--reset` (obrigatório), `--clientes` (25), `--seed` (42), `--api-url` |
| `importar` | api (`direto`) ou sim (`api`) | `--fonte`, `--arquivo`, `--modo` (`api`), `--sep` (`,`), `--lote` (1000), `--api-url` |
| `gerador` | sim | `--clientes` (100000), `--trx` (200000), `--abr` (60000), `--vu` (30000), `--dias` (1), `--seed`, `--distribution`, `--dir` (`./data`) |
| `ingestor` | sim | `--fonte` (`trx`), `--tps` (600), `--duration` (300), `--warmup` (30), `--concurrency` (64), `--dir`, `--dataset`, `--replay`, `--run`, `--log` |
| `leitor` | sim | `--tps`, `--duration`, `--warmup`, `--concurrency`, `--clientes`, `--seed`, `--distribution`, `--fracao-get` (0), `--tps-trx`, `--run`, `--log` |
| `carga` | sim | `--tps-trx` (600), `--tps-abr` (200), `--tps-vu` (100), `--tps-read` (600), `--fracao-get` (0,5), `--replay` e os demais do leitor |
| `relatorio` | sim | diretório de logs |

Códigos de saída:

| Código | Significado |
| --- | --- |
| 0 | sucesso |
| 1 | verificação falhou |
| 2 | argumento ou ambiente inválido |
| 3 | banco ou API indisponível |
| 4 | rodada inválida (dataset esgotado ou plano de execução errado) |
| 5 | erro inesperado |

## 5. Lendo o relatório

`logs/report/report.md` tem uma tabela por execução, com uma linha por programa e operação: `ingestor_trx`, `ingestor_abr`, `ingestor_vu`, e `leitor` com `features_post` e `features_get`. `report.csv` traz as mesmas linhas, para planilha, e `tps-series.csv`, as operações por segundo.

Cada linha mostra a latência de duas formas:

| Coluna | O que mede |
| --- | --- |
| **HTTP** avg/p95/p99/max | O tempo visto pelo simulador: rede, FastAPI e banco |
| **DB** avg/p95/p99/max | O tempo da chamada ao Oracle medido dentro da API (header `Server-Timing`), incluindo a espera por conexão do pool |

A diferença entre as duas é o custo da camada HTTP. Uma rodada só vale se todas as linhas tiverem **as três** colunas de verificação em `sim`:

| Coluna | Critério |
| --- | --- |
| **TPS ok** | Desvio do TPS alcançado em relação ao alvo menor que 5% |
| **Erro ok** | Taxa de erro menor que 0,1% |
| **Fila ok** | No máximo 1% das requisições após o warmup saiu mais de 100 ms depois do instante agendado |

Se a Fila ou o TPS falharem, o gargalo pode ser o simulador ou a API, e não o banco. Nesse caso, aumente `--concurrency`, `API_WORKERS` ou `API_CPUS` antes de concluir algo.

## 6. Estrutura do repositório

```
src/sentinela/
  domain/       eventos das três fontes, classificação das TRX, as 10 variáveis e a agregação em memória
  db/           SQL de leitura, inserts, store assíncrono da API, manutenção, pool, schema, plano
  api/          FastAPI (ingest e query API) e os modelos com os nomes de coluna das fontes
  sim/          amostradores, gerador sintético das três fontes, runner open-loop, log CSV, programas de carga
  report/       relatório
  verification/ referência transcrita do Excel, casos do Excel (excel_casos.json), cenários e equivalência
  backfill.py   histórico sintético de 92 dias
  importar.py   carga de arquivos das fontes (modo api ou direto)
  cli.py        ponto de entrada: sentinela <programa>
db/             SQL: usuário local (00), usuário no ATP (00_usuario_atp) e schema (01)
scripts/        ambiente.sh, rodada.sh, test.sh, lib.sh; extrair_excel.py regera os casos do Excel
tests/          unit/ e integration/
Containerfile   imagem única (alvos base e test)
compose.yaml    oracle (profile local-db), api e sim
```

## 7. Problemas comuns

| Sintoma | Causa e solução |
| --- | --- |
| `ambiente.sh` para em "aguardando o Oracle" na primeira vez | É normal: a criação do banco leva de 10 a 20 minutos. Acompanhe com `podman logs -f sentinela-oracle` |
| `API não respondeu /health` | O banco não está acessível pela API. Veja `podman logs sentinela-api` e o `ORACLE_DSN` |
| Programa sai com código 3 | O Oracle ou a API não estão acessíveis. Confira `podman ps` e o arquivo de ambiente (`ENV_FILE`) |
| No Windows, `bash scripts/...` no PowerShell falha com `pwsh`/`podman` não encontrado | O `bash` do PowerShell é o do WSL. Rode os scripts no **Git Bash** |
| Testes de integração aparecem como *skipped* | O `ORACLE_DSN` não é local (proteção dos testes destrutivos) ou o arquivo de ambiente não foi lido |
| `podman build` falha com `archive/tar: write too long` | Um arquivo do contexto cresceu durante o build (um log, por exemplo). Inclua o caminho no `.containerignore` |
| `features_get` com erros 404 no relatório | O leitor pediu um `ID_TRX` que o ingestor ainda não tinha postado (o ingestor de TRX parou ou partiu bem depois do leitor). Rode pela `carga`, com `--tps-trx` > 0 |
