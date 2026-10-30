# sentinela — POC Feature Store de Fraude

Benchmark reproduzível de uma feature store de contadores em janelas de tempo para detecção de fraude. Ele mede se o **Oracle 26ai** sozinho atende 600 leituras por segundo com latência adequada, ou se o **Valkey 7.2** (OCI Cache) precisa entrar como camada de leitura.

Os dois modos de serving usam o mesmo código, o mesmo dataset e a mesma carga:

| Modo | Ingestão | Leitura |
| --- | --- | --- |
| `oracle` | Oracle | Oracle |
| `oracle+valkey` | Oracle e Valkey | Valkey |

Cada leitura devolve, para uma conta, um vetor de 95 features: 19 janelas (de 1 minuto a 92 dias) × 5 métricas (`cnt`, `sum`, `min`, `max`, `avg`).

Documentação de referência:

- [Especificação funcional](docs/specs/Especificação%20funcional%20—%20POC%20Feature%20Store%20de%20Fraude.md): o quê e por quê.
- [Especificação técnica](docs/specs/Especificação%20técnica%20—%20POC%20Feature%20Store%20de%20Fraude.md): como, incluindo SQL, scripts Lua e decisões.
- [Plano de implementação](docs/superpowers/plans/2026-10-29-poc-feature-store.md).

## 1. Requisitos

### Máquina e ferramentas

| Item | Versão | Observação |
| --- | --- | --- |
| Podman | 5.x | No Windows, com `podman machine` (WSL). A VM precisa de **pelo menos 8 CPUs e 16 GB de RAM**: 6 GB para o Oracle, mais o Valkey e os dois programas Java com 1 GB de heap cada. |
| PowerShell | 7.x (`pwsh`) | Os scripts em `scripts/` são PowerShell 7; também rodam em Linux. |
| Git | qualquer | — |
| Disco | ~25 GB livres | Imagem do Oracle Enterprise (~8 GB), datafiles e imagens de build. |
| Conta Oracle | — | Necessária para baixar a imagem do Oracle Database Enterprise (seção 2.1). |

**Não é preciso instalar Java nem Maven no host.** O build e os testes rodam no container `maven:3.9-eclipse-temurin-25` pelo script `scripts/mvn.ps1`. Para compilar fora do container, use JDK 25 e Maven 3.9.

### Imagens de container

| Imagem | Papel |
| --- | --- |
| `container-registry.oracle.com/database/enterprise:latest` | Oracle AI Database 26ai Enterprise Edition (label `release` 23.26.x). O script de ambiente recusa outra linha de versão. |
| `docker.io/valkey/valkey:7.2` | Online store do modo `oracle+valkey`, sem persistência. |
| `docker.io/library/maven:3.9-eclipse-temurin-25` | Build e testes. |
| `docker.io/library/eclipse-temurin:25-jre` | Base da imagem da POC (`localhost/sentinela-poc`). |

### Dependências Java (fixadas no `pom.xml`)

| Dependência | Versão | Uso |
| --- | --- | --- |
| `com.oracle.database.jdbc:ojdbc17` | 23.26.0.0.0 | Driver JDBC do Oracle |
| `com.oracle.database.jdbc:ucp17` | 23.26.0.0.0 | Pool de conexões (UCP) |
| `io.lettuce:lettuce-core` | 6.6.0.RELEASE | Cliente do Valkey |
| `com.fasterxml.jackson.core:jackson-core` | 2.19.2 | Leitura do dataset NDJSON (só o streaming) |
| `org.junit.jupiter:junit-jupiter` | 5.13.4 | Testes |

Java 25, sem framework de aplicação, sem injeção de dependência, sem framework de log.

## 2. Deploy no podman

Tudo roda num único pod, `sentinela`, que publica as portas 1521 (Oracle) e 6379 (Valkey) no host:

| Container | Duração | Função |
| --- | --- | --- |
| `sentinela-oracle` | permanente | Banco; os dados ficam no volume `sentinela-oradata` |
| `sentinela-valkey` | permanente | Cache, sem persistência |
| containers da imagem `localhost/sentinela-poc` | efêmeros | Um por execução de programa |

### 2.1 Acesso à imagem do Oracle (uma vez)

1. Entre em <https://container-registry.oracle.com> com sua conta Oracle.
2. Abra **Database → enterprise** e aceite os termos de licença.
3. Faça login no registro pelo podman, com o usuário e a senha (ou o *auth token*) da conta:

```powershell
podman login container-registry.oracle.com
```

Se a imagem já estiver na máquina (`podman image exists container-registry.oracle.com/database/enterprise:latest`), este passo não é necessário.

### 2.2 Configuração (uma vez)

Copie o modelo e preencha as duas senhas:

```powershell
Copy-Item .env.example .env.local
notepad .env.local
```

| Variável | Significado |
| --- | --- |
| `ORACLE_PWD` | Senha de SYS, SYSTEM e PDBADMIN, definida na criação do banco |
| `ORACLE_URL` | `jdbc:oracle:thin:@//localhost:1521/SENTINELA` (dentro do pod, tudo é `localhost`) |
| `ORACLE_USER` / `ORACLE_PASSWORD` | Usuário da POC (`SENTINELA`) e a senha dele |
| `VALKEY_URL` | `redis://localhost:6379` |
| `FS_ZONE` | Fuso do dia civil, que define o corte de D-1 e das janelas de dias. Default `America/Sao_Paulo` |

Use senhas com letras e números, sem aspas, `$` ou espaços, com pelo menos 8 caracteres. O `.env.local` fica fora do git.

### 2.3 Subir o ambiente

```powershell
pwsh scripts/ambiente.ps1 -Acao up
```

O script é idempotente e pode ser rodado de novo sem problema. Ele:

1. Confere a imagem do Oracle e cria (ou reinicia) o pod, o volume e os containers `sentinela-oracle` e `sentinela-valkey`.
2. Espera o banco ficar pronto. **Na primeira vez, a criação do banco leva de 10 a 20 minutos.** Nas seguintes, os datafiles já estão no volume.
3. Cria o usuário `SENTINELA` na PDB `SENTINELA` (`db/00_usuario.sql`).
4. Constrói a imagem `localhost/sentinela-poc` a partir do `Containerfile`.
5. Cria as tabelas `TX` e `DAILY_AGG` (programa `schema`, que executa `db/01_schema.sql`).

Termina com a mensagem `ambiente pronto`. Para conferir:

```powershell
podman ps --pod
podman exec sentinela-valkey valkey-cli ping   # PONG
```

### 2.4 Depois de mudar o código

A imagem da POC contém o jar. Depois de alterar `src/`, reconstrua:

```powershell
podman build -t sentinela-poc .
```

### 2.5 Parar e remover

| Comando | Efeito |
| --- | --- |
| `pwsh scripts/ambiente.ps1 -Acao down` | Remove o pod e os containers; **o volume do Oracle fica**, e o próximo `up` não recria o banco |
| `pwsh scripts/ambiente.ps1 -Acao destroy` | Remove o pod **e** o volume do Oracle; o próximo `up` cria o banco do zero |

## 3. Testes automatizados

### Unitários

Não dependem de nenhum store.

```powershell
pwsh scripts/mvn.ps1 test
```

### Integração

Rodam contra o Oracle e o Valkey do pod, que precisa estar no ar.

```powershell
pwsh scripts/mvn.ps1 verify -Pintegration
```

> **Atenção:** os testes de integração são **destrutivos**: esvaziam `TX`, `DAILY_AGG` e o Valkey. Rode-os antes de uma rodada de benchmark, nunca durante. Como proteção, os testes destrutivos só executam se `ORACLE_URL` e `VALKEY_URL` apontarem para `localhost`; contra outro ambiente, só com `FS_IT_DESTRUCTIVE=1` definido explicitamente.

Para rodar um teste específico, coloque entre aspas os argumentos que contêm ponto, porque o PowerShell quebra `-Dit.test=...` no ponto:

```powershell
pwsh scripts/mvn.ps1 test -Dtest=WindowCatalogTest
pwsh scripts/mvn.ps1 verify -Pintegration '-Dit.test=OracleFeatureStoreIT'
```

A suíte completa tem 135 testes e leva alguns minutos; o `EquivalenceIT` sozinho aplica cerca de 90 mil transações.

## 4. Execução manual

Todo programa roda num container efêmero dentro do pod, pelo `scripts/poc.ps1`:

```powershell
pwsh scripts/poc.ps1 <programa> [--argumento valor ...]
```

As pastas `./data` e `./logs` do repositório são montadas como `/data` e `/logs` no container. Os caminhos padrão `./data/tx.ndjson` e `./logs/...` já apontam para elas.

### 4.1 Uma rodada passo a passo

O exemplo usa o modo `oracle+valkey`; troque por `oracle` para o outro modo.

**1. Gerar o dataset.** Isso só é preciso uma vez, e o mesmo arquivo serve aos dois modos:

```powershell
pwsh scripts/poc.ps1 gerador --accounts 100000 --count 200000
```

**2. Popular o histórico.** O backfill é destrutivo: esvazia os stores antes de popular. Com 100 mil contas leva cerca de 10 minutos. No fim, ele confere o plano de execução real da leitura e sai com código 4 se o índice coberto ou a poda de partições não forem usados:

```powershell
pwsh scripts/poc.ps1 backfill --mode oracle+valkey --accounts 100000
```

**3. Rodar ingestor e leitor ao mesmo tempo, em dois terminais, com o mesmo `--run`.** A leitura é medida sob ingestão concorrente:

```powershell
# terminal 1
pwsh scripts/poc.ps1 ingestor --mode oracle+valkey --tps 600 --duration 300 --warmup 30 --run minha-rodada
# terminal 2
pwsh scripts/poc.ps1 leitor --mode oracle+valkey --tps 600 --duration 300 --warmup 30 --accounts 100000 --run minha-rodada
```

**4. Gerar o relatório**, que processa todos os logs da pasta:

```powershell
pwsh scripts/poc.ps1 relatorio /logs
Get-Content logs/report/report.md
```

### 4.2 Rodada automatizada

`scripts/rodada.ps1` faz os passos 1 a 4 de uma vez:
1. gera o dataset se `data/tx.ndjson` não existir;
2. roda o backfill;
3. sobe ingestor e leitor em paralelo;
4. espera os dois e gera o relatório.

```powershell
pwsh scripts/rodada.ps1 -Mode oracle -Run A-oracle
pwsh scripts/rodada.ps1 -Mode oracle+valkey -Run B-oracle-valkey
```

Os parâmetros são `-Mode` (obrigatório), `-Run`, `-Tps` (600), `-Duration` (300), `-Warmup` (30), `-Accounts` (100000), `-Threads` (8) e `-Seed` (42). Depois das duas rodadas, o `report.md` traz a tabela lado a lado dos modos.

Para um teste rápido do ambiente, rode uma rodada curta:

```powershell
pwsh scripts/rodada.ps1 -Mode oracle+valkey -Run curta -Tps 100 -Duration 60 -Warmup 10 -Accounts 10000
```

O dataset é reaproveitado entre rodadas. Ao mudar `-Accounts` ou `-Seed`, apague `data/tx.ndjson` antes.

### 4.3 Verificação de equivalência (cenário C)

Aplica a mesma sequência de transações (93 dias, 1.000 contas) nos dois adapters e compara os vetores. Compara também com um modelo de referência calculado em memória, e confere os critérios funcionais: janelas vazias, dia corrente nas janelas de dias, limites ao microssegundo, idempotência e Job diário.

```powershell
pwsh scripts/poc.ps1 equivalencia --reset
```

Sem `--reset`, o programa se recusa a rodar se houver dados nos stores. O `--reset` apaga tudo. O resultado esperado são sete linhas `PASS` e exit code 0.

### 4.4 Job diário

Fecha um dia em `DAILY_AGG` e, no modo `oracle+valkey`, grava as fatias de dia no Valkey. Também remove partições de `TX` com mais de 93 dias. É idempotente.

```powershell
pwsh scripts/poc.ps1 job-diario --mode oracle+valkey               # D-1
pwsh scripts/poc.ps1 job-diario --mode oracle+valkey --day 2026-10-28
```

### 4.5 Referência dos programas

| Programa | Argumentos (default) |
| --- | --- |
| `gerador` | `--accounts` (100000), `--count` (200000), `--seed` (42), `--distribution` (`skewed` ou `uniform`), `--dataset` (`./data/tx.ndjson`) |
| `backfill` | `--mode` (`oracle`), `--accounts` (100000), `--days` (92, de 3 a 92), `--seed` (42), `--distribution` (`skewed`), `--tx-per-day` (5) |
| `ingestor` | `--mode` (`oracle`), `--tps` (600), `--duration` (300), `--warmup` (30), `--threads` (8), `--dataset`, `--run` (data/hora UTC), `--log` |
| `leitor` | `--mode` (`oracle`), `--tps` (600), `--duration` (300), `--warmup` (30), `--threads` (8), `--accounts` (100000), `--seed` (42), `--distribution` (`skewed`), `--run`, `--log` |
| `relatorio` | diretório de logs (argumento único) |
| `equivalencia` | `--accounts` (1000), `--seed` (42), `--now` (ISO-8601; default agora), `--reset` |
| `job-diario` | `--mode` (`oracle`), `--day` (`YYYY-MM-DD`; default D-1) |
| `schema` | — |

Códigos de saída:

| Código | Significado |
| --- | --- |
| 0 | Sucesso |
| 1 | Verificação falhou |
| 2 | Argumento ou variável de ambiente inválida |
| 3 | Store indisponível |
| 4 | Rodada inválida (dataset esgotado ou plano de execução errado) |
| 5 | Erro inesperado |

## 5. Lendo o relatório

O relatório tem uma tabela por execução e uma tabela comparando os dois modos:

| Arquivo | Conteúdo |
| --- | --- |
| `logs/report/report.md` | As tabelas |
| `logs/report/report.csv` | As mesmas linhas, para planilha |
| `logs/report/tps-series.csv` | Operações por segundo, para encontrar quedas |

Uma rodada só vale se todas as linhas tiverem **as três** colunas de verificação em `sim`:

| Coluna | Critério |
| --- | --- |
| **TPS ok** | Desvio do TPS alcançado em relação ao alvo menor que 5% |
| **Erro ok** | Taxa de erro menor que 0,1% |
| **Fila ok** | No máximo 1% das operações após o warmup começou mais de 100 ms depois do instante agendado. A coluna *Atraso máx* mostra o pior caso. |

A latência registrada cobre só a chamada ao store. Quando o cliente não dá conta da taxa, as operações esperam numa fila, e essa espera não entra nos percentis. A coluna **Fila ok** existe para detectar isso. O TPS sozinho não detecta: depois de um travamento, a fila é esvaziada dentro da janela medida e o TPS volta ao alvo.

Resultado da rodada local de 29/10/2026 (100 mil contas, 600 + 600 TPS, 5 min por modo):

- **Leitura válida nos dois modos:** fila zero. No modo `oracle`, avg 0,5 ms e p99 1,1 ms. No modo `oracle+valkey`, avg 0,2 ms e p99 0,5 ms.
- **Ingestão não válida:** com 8 threads o ingestor satura a 600 TPS (`Fila ok = NÃO`, atraso de até 16 s). Para medir a ingestão, é preciso refazer a rodada com mais threads no ingestor.

Os números locais servem para comparar os modos. Não representam o ATP-S na OCI.

## 6. Estrutura do repositório

```
db/                 SQL: usuário (00) e schema (01)
scripts/            ambiente.ps1, rodada.ps1, poc.ps1, mvn.ps1
src/main/java/sentinela/
  domain/           janelas, métricas, vetor de features e o port FeatureStorePort
  adapter/oracle/   adapter Oracle e manutenção (job, backfill, retenção)
  adapter/valkey/   adapter Valkey, scripts Lua e manutenção
  workload/         gerador de carga, amostradores e dataset
  report/           relatório
  verification/     modelo de referência e cenário C
  infra/            argumentos, ambiente, log CSV
  app/              um ponto de entrada por programa
src/main/resources/lua/   ingest.lua, put_day.lua
Containerfile       imagem da POC
```

## 7. Problemas comuns

| Sintoma | Causa e solução |
| --- | --- |
| `Error: Could not find or load main class .timezone=UTC` | O PowerShell quebrou um argumento `-D...` no ponto. Coloque-o entre aspas: `'-Duser.timezone=UTC'`. |
| `podman build` falha com `archive/tar: write too long` | Algum arquivo do contexto de build cresceu durante o build (um log, por exemplo). Pare o processo que escreve nele ou inclua o caminho no `.containerignore`. |
| `ambiente.ps1` para em "aguardando o Oracle" na primeira vez | É normal: a criação do banco leva de 10 a 20 minutos. Acompanhe com `podman logs -f sentinela-oracle`. |
| Programa sai com código 3 | O Oracle ou o Valkey não está acessível. Confira `podman ps --pod` e as URLs no `.env.local`. |
| Testes de integração aparecem como *skipped* | O pod não existe, ou o `.env.local` não foi lido, ou os stores não estão em `localhost` (proteção dos testes destrutivos). |
| Avisos `Set-PSReadLineOption` na saída | Vêm do perfil do PowerShell em execuções sem terminal e não afetam nada. Use `pwsh -NoProfile` para evitá-los. |
