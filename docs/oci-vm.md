# Rodando a POC na OCI sem containers (VM + Autonomous Database)

Neste modo, a API e o simulador rodam **direto na VM Linux**, num virtualenv Python, sem podman. O banco é um Autonomous Database (ATP) 26ai. Os programas são os mesmos do modo com containers ([oci.md](oci.md)); muda só a forma de chamá-los: `sentinela <programa>` no shell, em vez de `podman exec ... sentinela <programa>`.

```
┌──────────── VCN / subnet privada ─────────────┐
│  VM (Oracle Linux 9, Python 3.12, venv)       │
│   ├─ sentinela api    (uvicorn, :8000) ──────────► ATP 26ai (private endpoint, TLS)
│   └─ sentinela carga  ──► localhost:8000      │       TRX, ABR, VU
└───────────────────────────────────────────────┘
```

Os scripts `scripts/ambiente.sh` e `scripts/rodada.sh` **não servem neste modo**, porque usam `podman exec`. A seção 6 traz os mesmos passos, um comando por vez.

## 1. Autonomous Database

Igual ao modo com containers: siga a [seção 1 do oci.md](oci.md#1-autonomous-database). Em resumo:

1. ATP 26ai com private endpoint na VCN da VM e **8 ECPUs** (com 3, a rodada cheia não passou: a leitura gasta ~5 ms de CPU do banco).
2. Na security list ou NSG do private endpoint, libere a entrada TCP da porta da connect string (1521) vinda da subnet da VM.
3. mTLS desligado (TLS sem wallet). Com endpoint público, é preciso uma access control list com o IP de origem.
4. Anote **host**, **porta** e **service_name** da connect string TLS do serviço `_tp`.
5. Crie o usuário `SENTINELA` com [`db/00_usuario_atp.sql`](../db/00_usuario_atp.sql), como `ADMIN`.

Tabelas que o usuário já tenha (por exemplo `TX` e `DAILY_AGG`, do modelo anterior) não são tocadas: a POC só cria, esvazia e carrega `TRX`, `ABR` e `VU`.

## 2. VM

1. Crie uma VM `VM.Standard.E5.Flex` (sugestão: 8 OCPUs = 16 vCPUs, 32 GB) com Oracle Linux 9 na subnet privada. O acesso é pelo Bastion ou por uma VPN.
2. Instale o Python e o tmux. O projeto exige Python 3.12 ou mais novo:

   ```bash
   sudo dnf install -y python3.12 python3.12-pip tmux git
   ```

3. Copie o projeto para a VM e mude para a branch das 10 variáveis:

   ```bash
   git clone https://github.com/fmelodb/sentinela.git && cd sentinela && git switch xgboost-10-variaveis
   ```

   Se copiar o diretório do Windows, **não leve o `.venv`** (apague-o na VM).

4. Crie o virtualenv e instale o projeto em modo **editável**:

   ```bash
   python3.12 -m venv .venv
   . .venv/bin/activate
   pip install -e .
   ```

   Use o modo editável (`-e`): o programa `schema` procura `db/01_schema.sql` relativo ao código-fonte, e com uma instalação comum procuraria dentro do `site-packages`. A alternativa é exportar `SENTINELA_SCHEMA=$PWD/db/01_schema.sql`.

## 3. Configuração

Crie `.env.local` na raiz do projeto:

```bash
ORACLE_DSN=tcps://<host>:1521/<serviço>_tp.adb.oraclecloud.com
ORACLE_USER=SENTINELA
ORACLE_PASSWORD=SuaSenha123
FS_ZONE=America/Santiago
API_WORKERS=4
DB_POOL_SIZE=16
API_URL=http://localhost:8000
TZ=UTC
```

| Variável | Valor |
| --- | --- |
| `ORACLE_DSN` | `tcps://<host>:<porta>/<service_name>`, com os valores da connect string TLS. O formato curto evita aspas: a connect string com parênteses quebraria o `source` do arquivo |
| `ORACLE_USER` / `ORACLE_PASSWORD` | `SENTINELA` e a senha do usuário |
| `FS_ZONE` | Fuso da hora local das fontes (janelas, faixas horárias, partições). `America/Santiago` para os dados da CMR |
| `API_WORKERS` / `DB_POOL_SIZE` | Processos do uvicorn e conexões por processo. O total de sessões no ATP é `API_WORKERS × DB_POOL_SIZE`. Na mesma região, 4 × 16 deve bastar para a rodada cheia; com rede mais lenta, suba o pool (4 × 32 aguentou ~6 ms de rede) |
| `API_URL` | `http://localhost:8000`: a API está na mesma VM (no compose é `http://api:8000`) |
| `TZ` | `UTC`, como nos containers |

`ORACLE_PWD`, `API_CPUS` e `SIM_CPUS` não se aplicam: não há banco local nem limite de CPU por container (ver seção 4).

Se o arquivo veio do Windows, tire os CRLF: `sed -i 's/\r$//' .env.local`.

Os programas leem a configuração **do ambiente**, não do arquivo. Em todo terminal novo, carregue o ambiente:

```bash
cd ~/sentinela && . .venv/bin/activate && set -a && . ./.env.local && set +a
```

## 4. Subir a API

Rode tudo dentro do **tmux**, para que a API e a carga não morram se o SSH cair: `tmux new -s sentinela`. Use `Ctrl-b c` para abrir uma janela nova e `Ctrl-b n` para alternar entre elas. Depois de sair, `tmux attach -t sentinela` volta para a sessão.

**Janela 1, a API** (com o ambiente carregado):

```bash
sentinela schema                 # cria TRX, ABR e VU no ATP (idempotente; não toca outras tabelas)
taskset -c 0-11 sentinela api    # $API_WORKERS processos, porta 8000
```

**Janela 2, a conferência** (com o ambiente carregado):

```bash
curl -s localhost:8000/health    # {"status":"ok","versao":"excel-2026-11-13"}
```

**Isolamento de CPU:** sem containers, o `taskset` substitui `API_CPUS` e `SIM_CPUS`. Ele fixa a API e o simulador em núcleos diferentes, para o simulador não roubar CPU da API e distorcer o HTTP p99. O exemplo é para 16 vCPUs, com a API em 0–11 e o simulador em 12–15. Para ver quantas vCPUs a VM tem, use `nproc`.

## 5. Conferir a corretude: equivalência

Na janela 2. Esvazia `TRX`, `ABR` e `VU`, ingere pela API os 6 casos do Excel e os cenários extras da spec e compara cada leitura com a referência transcrita do Excel:

```bash
sentinela equivalencia --reset    # esperado: só linhas PASS (63) e código de saída 0
```

## 6. Rodar o benchmark

Rode na janela 2, **na raiz do projeto**, porque `./data` e `./logs` são relativos ao diretório atual. Os passos são os mesmos do `scripts/rodada.sh`, com os defaults dele:

```bash
# 1. datasets: tps × duration × 1,1 eventos por fonte (600, 200 e 100 TPS por 300 s)
sentinela gerador --dir ./data --clientes 100000 --seed 42 --trx 198000 --abr 66000 --vu 33000

# 2. backfill: DESTRUTIVO para TRX, ABR e VU. 92 dias, 100 mil clientes (~16,5 milhões de linhas);
#    no fim coleta estatísticas e confere o plano da leitura (código 4 se o plano for outro)
sentinela backfill --clientes 100000 --seed 42 --trx-dia 1 --abr-dia 0.5 --vu-dia 0.3

# 3. carga: três ingestores e o leitor em paralelo, por 300 s
taskset -c 12-15 sentinela carga --run oci-cheia --dir ./data \
    --tps-trx 600 --tps-abr 200 --tps-vu 100 --tps-read 600 --fracao-get 0.5 \
    --duration 300 --warmup 30 --clientes 100000 --seed 42 --concurrency 64

# 4. relatório
sentinela relatorio ./logs
cat logs/report/report.md
```

O que cada fluxo da carga faz:

| Processo | Chamada | Log |
| --- | --- | --- |
| `ingestor_trx` / `ingestor_abr` / `ingestor_vu` | `POST /v1/<fonte>` com um evento do dataset, com o instante trocado por agora | `logs/ingestor_<fonte>-<run>.csv` |
| `leitor` | `POST /v1/features` com uma compra sintética no instante atual; na fração `--fracao-get`, `GET /v1/features/{ID_TRX}` de uma TRX postada entre 5 e 65 s antes | `logs/leitor-<run>.csv` |

O `--run` é **só o nome da rodada**: aparece nos nomes dos logs e no título "Execução oci-cheia" do relatório. Sem ele, o nome é um timestamp UTC. O relatório agrega todos os logs de `./logs`, então nomes distintos separam as rodadas.

Rodada curta, para testar o ambiente antes da cheia (backfill de alguns minutos):

```bash
sentinela gerador --dir ./data --clientes 10000 --trx 6600 --abr 2000 --vu 1400
sentinela backfill --clientes 10000
taskset -c 12-15 sentinela carga --run oci-curta --dir ./data --tps-trx 100 --tps-abr 30 --tps-vu 20 \
    --tps-read 100 --duration 60 --warmup 10 --clientes 10000
```

### 6.1 Rodadas seguintes

- **O mesmo dataset serve**, a menos que `tps × duration × 1,1` passe do que foi gerado. Nesse caso, gere de novo com contagens maiores.
- **O backfill pode ser pulado**, mas antes apague o que a carga anterior gravou. Senão os mesmos `ID_TRX` (`G…`) e `ID_EVENTO` (`GA…`, `GV…`) voltam como repetidos e a rodada não mede inserção. Em SQL, como `SENTINELA`:

  ```sql
  DELETE FROM trx WHERE id_trx LIKE 'G%';
  DELETE FROM abr WHERE id_evento LIKE 'G%';
  DELETE FROM vu  WHERE id_evento LIKE 'G%';
  COMMIT;
  ```

- A equivalência esvazia as tabelas: depois dela, refaça o backfill.

### 6.2 Carregar dados reais

```bash
sentinela importar --fonte trx --arquivo trx.csv --sep ';' --modo api       # pelos endpoints de lote
sentinela importar --fonte abr --arquivo abr.ndjson --modo direto           # em massa, direto no ATP
```

Os dois modos validam com os mesmos modelos da API; linhas inválidas aparecem com o número da linha e o campo. Detalhes no [README, seção 4.4](../README.md#44-dados-reais-importar).

### 6.3 Job diário

```bash
sentinela job-diario                 # remove partições com mais de 93 dias, até D-1
sentinela job-diario --day 2026-11-05
```

## 7. Escalando a carga

O simulador roda em laço aberto: dispara exatamente o TPS pedido e não acelera se a API tiver folga. Para achar a capacidade, suba o TPS em degraus até alguma coluna de verificação do relatório falhar:

```bash
taskset -c 12-15 sentinela carga --run oci-1200 --dir ./data \
    --tps-trx 1200 --tps-abr 400 --tps-vu 200 --tps-read 1200 \
    --duration 300 --warmup 30 --clientes 100000 --concurrency 160
```

- **Dataset:** gere de novo com contagens ≥ `tps × duration × 1,1` por fonte. Para o exemplo, `--trx 396000 --abr 132000 --vu 66000`.
- **`--concurrency`:** o máximo de cada processo do simulador é cerca de `concurrency ÷ latência HTTP média`. Suba junto com o TPS, para não ser o simulador que satura.
- **`API_WORKERS` e `DB_POOL_SIZE`:** altere no `.env.local`, recarregue o ambiente e reinicie a API (`Ctrl-C` na janela 1 e `sentinela api` de novo). Confira se o ATP aguenta o total de sessões.
- **ECPUs:** a leitura gasta ~5 ms de CPU do banco. Cada 600 leituras por segundo ocupam ~3 CPUs do ATP; é o recurso que limita primeiro. Não escale durante uma rodada.
- **Diagnóstico:** se o HTTP p99 sobe e o DB p99 fica estável, o gargalo é a API (mais workers ou CPU). Se o DB sobe junto, é o ATP ou o pool. O tempo de cada comando do lado do banco, sem rede, está em `V$SQL` (consulta no [oci.md, seção 6](oci.md#6-rodar-o-benchmark)).

## 8. O que muda em relação ao modo com containers

- **Sem limite de CPU por processo:** use o `taskset` (seção 4). Sem ele, a API e o simulador disputam os mesmos núcleos.
- **A configuração vem do shell:** é preciso carregar o `.env.local` em cada terminal (seção 3). Uma API iniciada sem o ambiente falha com `variáveis de ambiente ausentes`.
- **Processos em primeiro plano:** a API não reinicia sozinha. Use o tmux e confira o `/health` antes de cada rodada.
- **Rede, commit, ECPUs e plano de execução:** valem as mesmas observações do [oci.md, seção 9](oci.md#9-o-que-muda-em-relação-ao-desktop).
