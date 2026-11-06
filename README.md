# sentinela — POC Feature Store de Fraude

Benchmark reproduzível de uma feature store de contadores em janelas de tempo, para detecção de fraude. Ele mede se o **Oracle 26ai**, atrás de uma **ingest API** e de uma **query API** em FastAPI, serve 600 leituras por segundo com latência adequada, sob 600 ingestões por segundo concorrentes.

Cada leitura devolve, para uma conta, um vetor de 95 features: 19 janelas (de 1 minuto a 92 dias) × 5 métricas (`cnt`, `sum`, `min`, `max`, `avg`).

```
sentinela-sim ──HTTP──► sentinela-api (FastAPI) ──SQL──► Oracle 26ai
 gerador, ingestor,      POST /v1/transacoes              TX, DAILY_AGG
 leitor, relatorio       GET  /v1/contas/{id}/features
```

> Esta é a v2, em Python e só com Oracle. A v1, em Java, com os modos `oracle` e `oracle+valkey` e scripts PowerShell, está na tag **`java-v1`** (`git switch --detach java-v1`).

Documentação:

- [Especificação funcional](docs/specs/Especificação%20funcional%20—%20POC%20Feature%20Store%20de%20Fraude.md): o quê e por quê.
- [Especificação técnica](docs/specs/Especificação%20técnica%20—%20POC%20Feature%20Store%20de%20Fraude.md): como.
- [Como a leitura consulta o Oracle](docs/leitura-oracle.md).
- [Rodando na OCI](docs/oci.md): VM + Autonomous Database.

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
| `FS_ZONE` | Fuso do dia civil. Default `America/Sao_Paulo` |
| `API_WORKERS` / `DB_POOL_SIZE` | Processos do uvicorn e conexões por processo (4 × 8) |
| `API_CPUS` / `SIM_CPUS` | Limite de CPU dos containers da API e do simulador |
| `API_URL` | `http://api:8000`: onde o simulador encontra a API |

Use senhas só com letras e números, com pelo menos 8 caracteres. O `.env.local` fica fora do git.

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
curl -s -X POST localhost:8000/v1/transacoes -H 'Content-Type: application/json' \
     -d '{"tx_id": 1, "account_id": 42, "ts": "2026-11-06T12:00:00Z", "amount": 150.25}'
curl -si localhost:8000/v1/contas/42/features | head -20
```

A documentação interativa das APIs fica em <http://localhost:8000/docs>.

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

> **Atenção:** os testes de integração são **destrutivos**, porque esvaziam `TX` e `DAILY_AGG`. Por proteção, só rodam se `ORACLE_DSN` apontar para o banco local; contra outro banco, só com `FS_IT_DESTRUCTIVE=1`.

## 4. Rodar o benchmark

### 4.1 Rodada automatizada

```bash
scripts/rodada.sh --run minha-rodada
cat logs/report/report.md
```

O script:
1. gera `data/tx.ndjson`, se não existir ou se for pequeno demais;
2. roda o backfill (destrutivo, cerca de 10 minutos com 100 mil contas), que confere o plano de execução real da leitura;
3. roda ingestor e leitor em paralelo contra a API;
4. gera o relatório.

Parâmetros: `--run`, `--tps` (600, para cada fluxo), `--duration` (300), `--warmup` (30), `--accounts` (100000), `--concurrency` (64), `--seed` (42) e `--sem-backfill`. Rodada curta, para testar o ambiente:

```bash
scripts/rodada.sh --run curta --tps 100 --duration 60 --warmup 10 --accounts 10000
```

### 4.2 Passo a passo

Os programas de banco rodam no container da API. Os de carga rodam no simulador, que monta `./data` e `./logs` como `/data` e `/logs`.

```bash
podman exec sentinela-sim sentinela gerador --accounts 100000 --count 200000
podman exec sentinela-api sentinela backfill --accounts 100000
podman exec sentinela-sim sentinela carga --run r1 --tps-ingest 600 --tps-read 600 --duration 300
podman exec sentinela-sim sentinela relatorio /logs
```

`ingestor` e `leitor` também rodam separadamente, em dois terminais com o mesmo `--run`.

### 4.3 Equivalência (cenário C)

Aplica 93 dias de transações de 1.000 contas pela ingest API, lê os vetores pela query API e compara com um modelo de referência calculado em memória. Confere também janelas vazias, dia corrente, limites ao microssegundo, idempotência e o job diário.

```bash
podman exec sentinela-api sentinela equivalencia --reset
```

O `--reset` apaga as tabelas. O resultado esperado são sete linhas `PASS` e código de saída 0.

### 4.4 Job diário

Fecha um dia em `DAILY_AGG` e remove partições de `TX` com mais de 93 dias. É idempotente.

```bash
podman exec sentinela-api sentinela job-diario               # D-1
podman exec sentinela-api sentinela job-diario --day 2026-11-05
```

### 4.5 Referência dos programas

`sentinela <programa> --help` lista os argumentos.

| Programa | Container | Argumentos (default) |
| --- | --- | --- |
| `api` | api | `--workers` ($API_WORKERS), `--port` (8000) |
| `schema` | api | `--arquivo` |
| `backfill` | api | `--accounts` (100000), `--days` (92), `--seed` (42), `--distribution` (`skewed`), `--tx-per-day` (5) |
| `job-diario` | api | `--day` (D-1) |
| `equivalencia` | api | `--accounts` (1000), `--seed` (42), `--now` (agora), `--reset` |
| `gerador` | sim | `--accounts` (100000), `--count` (200000), `--seed`, `--distribution`, `--dataset` (`./data/tx.ndjson`) |
| `ingestor` | sim | `--tps` (600), `--duration` (300), `--warmup` (30), `--concurrency` (64), `--dataset`, `--run`, `--log` |
| `leitor` | sim | `--tps`, `--duration`, `--warmup`, `--concurrency`, `--accounts`, `--seed`, `--distribution`, `--run`, `--log` |
| `carga` | sim | `--tps-ingest` (600), `--tps-read` (600) e os demais de ingestor e leitor |
| `relatorio` | sim | diretório de logs |

Códigos de saída: 0 sucesso, 1 verificação falhou, 2 argumento ou ambiente inválido, 3 banco ou API indisponível, 4 rodada inválida (dataset esgotado ou plano de execução errado), 5 erro inesperado.

## 5. Lendo o relatório

`logs/report/report.md` tem uma tabela por execução. `report.csv` traz as mesmas linhas, para planilha, e `tps-series.csv`, as operações por segundo.

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
  domain/       janelas, limites, agregados e vetor de 95 features
  db/           SQL de leitura, store assíncrono da API, manutenção, pool, schema, plano
  api/          FastAPI (ingest e query API)
  sim/          amostradores, dataset, runner open-loop, log CSV, programas de carga
  report/       relatório
  verification/ modelo de referência e cenário C
  backfill.py   histórico do benchmark
  cli.py        ponto de entrada: sentinela <programa>
db/             SQL: usuário local (00), usuário no ATP (00_usuario_atp) e schema (01)
scripts/        ambiente.sh, rodada.sh, test.sh, lib.sh
tests/          unit/ e integration/
Containerfile   imagem única (alvos base e test)
compose.yaml    oracle (profile local-db), api e sim
```

## 7. Problemas comuns

| Sintoma | Causa e solução |
| --- | --- |
| `ambiente.sh` para em "aguardando o Oracle" na primeira vez | É normal: a criação do banco leva de 10 a 20 minutos. Acompanhe com `podman logs -f sentinela-oracle` |
| `API não respondeu /health` | O banco não está acessível pela API. Veja `podman logs sentinela-api` e o `ORACLE_DSN` |
| Programa sai com código 3 | O Oracle ou a API não estão acessíveis. Confira `podman ps` e o `.env.local` |
| No Windows, `bash scripts/...` no PowerShell falha com `pwsh`/`podman` não encontrado | O `bash` do PowerShell é o do WSL. Rode os scripts no **Git Bash** |
| Testes de integração aparecem como *skipped* | O `ORACLE_DSN` não é local (proteção dos testes destrutivos) ou o `.env.local` não foi lido |
| `podman build` falha com `archive/tar: write too long` | Um arquivo do contexto cresceu durante o build (um log, por exemplo). Inclua o caminho no `.containerignore` |
