# Rodando a POC na OCI com containers (VM + Autonomous Database)

Na OCI, a POC roda numa VM Linux com os mesmos containers do desktop: a API e o simulador. O banco é um Autonomous Database (ATP) 26ai, em vez do Oracle local. O `compose.yaml` e os scripts são os mesmos; mudam só o arquivo de ambiente (o `ORACLE_DSN` aponta para o ATP) e a flag `--oci` do `ambiente.sh`.

```
┌──────────── VCN / subnet privada ─────────────┐
│  VM (Oracle Linux 9, podman)                  │
│   ├─ sentinela-api  (FastAPI, :8000) ────────────► ATP 26ai (private endpoint, TLS)
│   └─ sentinela-sim  (simulador) ──► api:8000  │       TRX, ABR, VU
└───────────────────────────────────────────────┘
```

Para rodar sem containers, direto no Python da VM, veja [oci-vm.md](oci-vm.md). Os programas e os resultados são os mesmos.

## Por que VM, e não Container Instances

- **O mesmo `compose.yaml`** do desktop. No Container Instances, cada container é um recurso OCI à parte, sem compose.
- **Logs e relatório ficam em disco** (`./logs`). No Container Instances o armazenamento é efêmero, e seria preciso enviar os arquivos ao Object Storage.
- **Iteração e diagnóstico:** `podman exec`, `podman stats`, `top`, rebuild em segundos e ajuste de `API_WORKERS` sem recriar nada.
- **Isolamento de CPU:** `API_CPUS` e `SIM_CPUS` limitam cada container. Se o simulador ainda interferir na API, ele pode ir para uma segunda VM: basta apontar `API_URL` para o IP privado da primeira e liberar a porta 8000 na security list.

## 1. Autonomous Database

1. Crie um **Autonomous Transaction Processing** na versão 26ai, com **private endpoint** na mesma VCN da VM.
2. **ECPUs:** comece com **8**. A leitura das 10 variáveis gasta cerca de 5 ms de CPU do banco; a 600 leituras por segundo, são ~3 CPUs ocupadas só com ela. Com 3 ECPUs a rodada cheia não passou; com 8, passou com folga (seção 6).
3. **mTLS desligado:** em *Network → Mutual TLS (mTLS) authentication → Edit*, desmarque *Require mutual TLS authentication*. Assim o driver em modo thin conecta por TLS, **sem wallet**.

   > Com **endpoint público**, o ATP só aceita TLS sem wallet se houver uma **access control list** com o IP de origem. Sem ela, a conexão é recusada com `DPY-6000: Listener refused connection (Similar to ORA-12506)`.

4. Em *Database connection*, selecione **TLS** e copie a connect string do serviço **`_tp`**. Ela tem a forma `(description=...(address=(protocol=tcps)(port=1521)(host=<host>))(connect_data=(service_name=<serviço>))...)`. Anote o **host**, a **porta** e o **service_name**.
5. Crie o usuário da POC como `ADMIN` (Database Actions → SQL) com o script [`db/00_usuario_atp.sql`](../db/00_usuario_atp.sql), trocando a senha. Os grants em `V$SQL` e `V$SQL_PLAN*` são obrigatórios: o backfill confere o plano real da leitura e aborta com código 4 sem eles.

O schema da POC cria só `TRX`, `ABR` e `VU` (`CREATE ... IF NOT EXISTS`). Se o usuário já tiver tabelas de outras rodadas (por exemplo `TX` e `DAILY_AGG`, do modelo anterior), elas não são tocadas: nem o `schema`, nem o `backfill`, nem a `equivalencia` mexem nelas.

## 2. VM

1. Crie uma VM `VM.Standard.E5.Flex` (sugestão: 8 OCPUs, 32 GB) com Oracle Linux 9 na subnet privada. O acesso é pelo Bastion ou por uma VPN.
2. Instale as ferramentas:

   ```bash
   sudo dnf install -y podman git python3-pip
   pip3 install --user podman-compose
   ```

3. Clone o repositório e mude para a branch das 10 variáveis:

   ```bash
   git clone https://github.com/fmelodb/sentinela.git && cd sentinela && git switch xgboost-10-variaveis
   ```

Não é preciso login no registro da Oracle: a imagem do Oracle só é usada com o banco local.

## 3. Configuração

```bash
cp .env.example .env.local
vi .env.local
```

```bash
ORACLE_DSN=tcps://<host>:1521/<serviço>_tp.adb.oraclecloud.com
ORACLE_USER=SENTINELA
ORACLE_PASSWORD=SuaSenha123
FS_ZONE=America/Santiago
API_WORKERS=4
DB_POOL_SIZE=32
API_CPUS=6
SIM_CPUS=2
API_URL=http://api:8000
```

| Variável | Valor na OCI |
| --- | --- |
| `ORACLE_DSN` | `tcps://<host>:<porta>/<service_name>`, com os valores do passo 1.4. Use esse formato curto, e não a connect string com parênteses: o `lib.sh` faz `source` do arquivo, e parênteses sem aspas quebram o shell |
| `ORACLE_USER` / `ORACLE_PASSWORD` | `SENTINELA` e a senha do passo 1.5 |
| `ORACLE_PWD` | vazio (não há banco local) |
| `FS_ZONE` | Fuso da hora local das fontes. `America/Santiago` para os dados da CMR |
| `API_WORKERS` / `DB_POOL_SIZE` | Processos do uvicorn e conexões por processo. Cada worker abre `DB_POOL_SIZE` sessões no ATP. 4 × 32 = 128 sessões aguentaram a rodada cheia com ~6 ms de rede; numa VM na mesma região, 4 × 16 deve bastar |
| `API_CPUS` / `SIM_CPUS` | Por exemplo, 6 e 2 numa VM de 8 OCPUs |
| `API_URL` | `http://api:8000`: onde o simulador encontra a API, dentro do compose |

Para manter mais de um ambiente (por exemplo, um arquivo para o Oracle local e outro para o ATP), crie arquivos separados e escolha com `ENV_FILE`: `ENV_FILE=.env.atps scripts/ambiente.sh up --oci`. Os `.env.*` com senha não devem ir para o git.

## 4. Subir

```bash
scripts/ambiente.sh up --oci
```

O script constrói a imagem, sobe `sentinela-api` e `sentinela-sim`, espera o `/health` e cria `TRX`, `ABR` e `VU` no ATP. Para conferir:

```bash
curl -s localhost:8000/health        # {"status":"ok","versao":"excel-2026-11-13"}
```

Depois de mudar o código ou o arquivo de ambiente: `scripts/ambiente.sh build` (recria a API e o simulador).

## 5. Conferir a corretude: equivalência

Antes do benchmark, rode a equivalência contra o ATP. Ela esvazia `TRX`, `ABR` e `VU` (só elas), ingere pela API os 6 casos do Excel e os cenários extras da spec, e compara cada leitura com a referência transcrita do Excel:

```bash
podman exec sentinela-api sentinela equivalencia --reset --api-url http://localhost:8000
```

Esperado: só linhas `PASS` (63) e código de saída 0. O `--api-url` é necessário porque o programa roda dentro do container da API, onde `http://api:8000` não existe.

## 6. Rodar o benchmark

```bash
scripts/rodada.sh --run oci-cheia
cat logs/report/report.md
```

A rodada gera os datasets, faz o backfill de 92 dias (100 mil clientes, ~16,5 milhões de linhas, destrutivo para `TRX`, `ABR` e `VU`), confere o plano, roda a carga cheia por 300 s e gera o relatório:

| Fluxo | TPS (default) |
| --- | ---: |
| Ingestão de TRX / ABR / VU | 600 / 200 / 100 |
| Leitura (metade POST com uma compra sintética, metade GET de uma TRX recém-ingerida) | 600 |

Os parâmetros (`--tps-trx`, `--tps-abr`, `--tps-vu`, `--tps-read`, `--fracao-get`, `--clientes`, `--duration`, `--warmup`, `--sem-backfill`…) estão no [README, seção 4.1](../README.md#41-rodada-automatizada).

**Rodadas seguintes sem refazer o backfill:** a carga grava as TRX do dataset (`ID_TRX` `G1`, `G2`…) e os ABR/VU com `ID_EVENTO` `GA…`/`GV…`. Numa segunda carga sobre a mesma base, essas gravações voltariam como repetidas e não mediriam inserção. Apague-as antes (em SQL, como `SENTINELA`):

```sql
DELETE FROM trx WHERE id_trx LIKE 'G%';
DELETE FROM abr WHERE id_evento LIKE 'G%';
DELETE FROM vu  WHERE id_evento LIKE 'G%';
COMMIT;
```

e rode `scripts/rodada.sh --run oci-cheia-2 --sem-backfill`.

### Resultado de referência

Rodada cheia com os defaults, simulador e API num **desktop** em São Paulo (~6 ms de rede até o ATP), ATP com **8 ECPUs**, `API_WORKERS=4`, `DB_POOL_SIZE=32`. Todas as linhas com TPS, Erro e Fila `sim`:

| Fluxo | DB avg | DB p99 | Tempo no banco (`V$SQL`) |
| --- | ---: | ---: | ---: |
| Ingestão TRX / ABR / VU | 5,8 ms | 11 ms | ~1,1 ms por insert com commit |
| Leitura POST | 11,4 ms | 20 ms | ~6 ms (5,3 ms de CPU) |
| Leitura GET | 16,6 ms | 28 ms | ~6 ms + 0,03 ms da busca por ID |

A coluna DB é medida na API e inclui a rede até o ATP (uma ida e volta por chamada; o GET faz duas). Numa VM na mesma região, espera-se algo perto do tempo no banco mais a rede local. Com 3 ECPUs, a mesma rodada reprovou na fila: a CPU da leitura é o recurso que limita.

Para ver o tempo de cada comando do lado do banco (sem rede), como `ADMIN`:

```sql
SELECT SUBSTR(sql_text, 1, 40) sql_txt, executions,
       ROUND(elapsed_time / executions / 1000, 2) ms_ela, ROUND(cpu_time / executions / 1000, 2) ms_cpu,
       ROUND(buffer_gets / executions) gets
FROM v$sql
WHERE parsing_schema_name = 'SENTINELA' AND executions > 10000
ORDER BY executions DESC;
```

## 7. Carregar dados reais

Os endpoints recebem os nomes de coluna das fontes, então os dados reais podem chegar direto pela API (`POST /v1/trx`, `/v1/trx/lote` etc.). Para a história inicial, a partir de arquivos:

```bash
# pela API, de dentro do simulador (os arquivos em ./data aparecem em /data)
podman exec sentinela-sim sentinela importar --fonte trx --arquivo /data/trx.csv --sep ';' --modo api
# direto no ATP, em massa (copie o arquivo para o container da API antes)
podman cp abr.ndjson sentinela-api:/tmp/abr.ndjson
podman exec sentinela-api sentinela importar --fonte abr --arquivo /tmp/abr.ndjson --modo direto
```

Os dois modos validam com os mesmos modelos da API. Detalhes no [README, seção 4.4](../README.md#44-dados-reais-importar).

## 8. Testes de integração contra o ATP

Os testes de integração se recusam a rodar contra um banco que não seja local, porque esvaziam `TRX`, `ABR` e `VU`. Para rodá-los no ATP, explicitamente: `FS_IT_DESTRUCTIVE=1 scripts/test.sh`. Outras tabelas do usuário não são tocadas.

## 9. O que muda em relação ao desktop

- **Commit:** no desktop (podman no WSL), o redo em disco virtual limitou a ingestão a algo entre 600 e 900 commits por segundo. No ATP, cada insert com commit leva ~1 ms no banco e os 900 por segundo passam sem picos.
- **Sem controle da manutenção:** no ATP não dá para mudar o redo nem desligar as tarefas automáticas. Registre o horário da rodada para cruzar com eventos do banco.
- **Escala de ECPUs durante uma rodada invalida a rodada:** a primeira carga feita durante a mudança de 3 para 8 ECPUs teve atrasos de até 24 s. Escale, espere e rode de novo.
- **Plano de execução:** o backfill confere o plano real da leitura (três `INDEX RANGE SCAN`, nenhum `TABLE ACCESS`); precisa de `V$SQL` e `DBMS_XPLAN.DISPLAY_CURSOR`, daí os grants do script do usuário.
- **Rede:** a latência HTTP do relatório inclui a rede simulador → API; a coluna *DB* inclui a rede API → ATP. O tempo só do banco está em `V$SQL` (seção 6).
