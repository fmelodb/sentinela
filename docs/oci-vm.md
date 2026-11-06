# Rodando a POC na OCI sem containers (VM + Autonomous Database)

Neste modo, a API e o simulador rodam **direto na VM Linux**, num virtualenv Python, sem podman. O banco é um Autonomous Database (ATP) 26ai. Os programas são os mesmos do modo com containers ([oci.md](oci.md)). Muda só a forma de chamá-los: `sentinela <programa>` no shell, em vez de `podman exec ... sentinela <programa>`.

```
┌──────────── VCN / subnet privada ─────────────┐
│  VM (Oracle Linux 9, Python 3.12, venv)       │
│   ├─ sentinela api    (uvicorn, :8000) ──────────► ATP 26ai (private endpoint, TLS :1521)
│   └─ sentinela carga  ──► localhost:8000      │
└───────────────────────────────────────────────┘
```

Os scripts `scripts/ambiente.sh` e `scripts/rodada.sh` **não servem neste modo**, porque usam `podman exec`. Use os comandos da seção 5, que fazem os mesmos passos.

## 1. Autonomous Database

1. Crie um **Autonomous Transaction Processing** na versão 26ai, com **private endpoint** na mesma VCN da VM.
2. **Rede:** na security list ou NSG do private endpoint, libere a entrada TCP **1521** vinda da subnet da VM.
3. **mTLS:** desmarque *Require mutual TLS authentication* (em *Network*, *Mutual TLS (mTLS) authentication*, *Edit*). Assim o driver em modo thin conecta por TLS, **sem wallet**.

   > Com **endpoint público**, o ATP só aceita TLS sem wallet se houver uma **access control list** com o IP de origem. Sem ela, a conexão é recusada com `DPY-6000: Listener refused connection (Similar to ORA-12506)`. Esse erro também aparece se você usar um alias de `tnsnames.ora` que aponte para a porta **1522** (mTLS): o sqlcl conecta porque usa a wallet do `sqlnet.ora`, e o `python-oracledb` thin não usa.

4. Em *Database connection*, selecione **TLS** em *TLS authentication* e copie a connect string do serviço `_tp`. Ela usa a **porta 1521** e tem a forma `(description=(retry_count=20)(retry_delay=3)(address=(protocol=tcps)(port=1521)(host=...))(connect_data=(service_name=..._tp.adb.oraclecloud.com))(security=(ssl_server_dn_match=yes)))`.
5. Crie o usuário da POC como `ADMIN` (Database Actions → SQL) com o script [`db/00_usuario_atp.sql`](../db/00_usuario_atp.sql), trocando `<senha>`. Os grants em `V$SQL` e `V$SQL_PLAN*` são obrigatórios: o backfill confere o plano real da leitura e aborta com código 4 sem eles.

## 2. VM

1. Crie uma VM `VM.Standard.E5.Flex` (sugestão: 8 OCPUs = 16 vCPUs, 32 GB) com Oracle Linux 9 na subnet privada. O acesso é pelo Bastion ou por uma VPN.
2. Instale o Python e o tmux. O projeto exige Python 3.12 ou mais novo:

   ```bash
   sudo dnf install -y python3.12 python3.12-pip tmux git
   ```

3. Copie o projeto para a VM, com `git clone` ou copiando o diretório:

   ```bash
   git clone https://github.com/fmelodb/sentinela.git && cd sentinela && git switch python
   ```

   Se copiar o diretório do Windows, **não leve o `.venv`** (apague-o na VM).

4. Crie o virtualenv e instale o projeto em modo **editável**:

   ```bash
   python3.12 -m venv .venv
   . .venv/bin/activate
   pip install -e .
   ```

   Use o modo editável (`-e`). O programa `schema` procura `db/01_schema.sql` relativo ao código-fonte, e com uma instalação comum ele procuraria dentro do `site-packages`. A alternativa é exportar `SENTINELA_SCHEMA=$PWD/db/01_schema.sql`.

## 3. Configuração

Crie `.env.local` na raiz do projeto:

```bash
ORACLE_DSN='(description=(retry_count=20)(retry_delay=3)(address=(protocol=tcps)(port=1521)(host=...))(connect_data=(service_name=..._tp.adb.oraclecloud.com))(security=(ssl_server_dn_match=yes)))'
ORACLE_USER=SENTINELA
ORACLE_PASSWORD=SuaSenha123
FS_ZONE=America/Sao_Paulo
API_WORKERS=4
DB_POOL_SIZE=8
API_URL=http://localhost:8000
TZ=UTC
```

| Variável | Valor |
| --- | --- |
| `ORACLE_DSN` | A connect string TLS do passo 1.4, numa linha só, **entre aspas simples** (ela tem parênteses e `=`) |
| `ORACLE_USER` / `ORACLE_PASSWORD` | `SENTINELA` e a senha do passo 1.5 |
| `FS_ZONE` | Fuso do dia civil. Default `America/Sao_Paulo` |
| `API_WORKERS` / `DB_POOL_SIZE` | Processos do uvicorn e conexões por processo. Comece com 4 × 8. Cada worker abre `DB_POOL_SIZE` sessões no ATP |
| `API_URL` | `http://localhost:8000`: a API está na mesma VM (no compose era `http://api:8000`) |
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
sentinela schema                 # cria TX e DAILY_AGG no ATP (idempotente)
taskset -c 0-11 sentinela api    # $API_WORKERS processos, porta 8000
```

**Janela 2, a conferência** (com o ambiente carregado):

```bash
curl -s localhost:8000/health    # {"status":"ok"}
```

**Isolamento de CPU:** sem containers, o `taskset` substitui `API_CPUS` e `SIM_CPUS`. Ele fixa a API e o simulador em núcleos diferentes, para o simulador não roubar CPU da API e distorcer o HTTP p99. O exemplo acima é para 16 vCPUs, com a API em 0–11 e o simulador em 12–15. Para ver quantas vCPUs a VM tem, use `nproc`.

## 5. Rodar o benchmark

Rode na janela 2, **na raiz do projeto**, porque `./data` e `./logs` são relativos ao diretório atual. Os passos abaixo são os mesmos do `scripts/rodada.sh`:

```bash
# 1. dataset: tps × duration × 1,1 = 600 × 300 × 1,1
sentinela gerador --accounts 100000 --count 198000 --seed 42

# 2. backfill: DESTRUTIVO, cerca de 10 minutos com 100 mil contas; confere o plano da leitura
sentinela backfill --accounts 100000 --seed 42

# 3. carga: ingestor e leitor em paralelo, 600 + 600 TPS por 300 s
taskset -c 12-15 sentinela carga --run oci-600 --tps-ingest 600 --tps-read 600 \
    --duration 300 --warmup 30 --accounts 100000 --seed 42 --concurrency 64

# 4. relatório
sentinela relatorio ./logs
cat logs/report/report.md
```

O `--run` é **só o nome da rodada**. Ele não define os 600 TPS, que vêm do `--tps-ingest` e do `--tps-read`. O nome aparece nos logs (`logs/ingestor-oci-600.csv`, `logs/leitor-oci-600.csv`) e no título "Execução oci-600" do relatório. Sem ele, o nome é um timestamp UTC. O relatório agrega todos os logs de `./logs`, então nomes distintos separam as rodadas.

Para as rodadas seguintes:

- **o mesmo dataset serve**, a menos que `tps × duration × 1,1` passe do `--count` gerado. Nesse caso, gere de novo com um `--count` maior;
- **o backfill pode ser pulado** se a base não foi esvaziada. Para comparar rodadas em condições iguais, refaça o backfill antes de cada uma.

### 5.1 Equivalência (opcional)

Confere a corretude contra o ATP. É destrutivo, porque esvazia as tabelas, então refaça o backfill depois:

```bash
sentinela equivalencia --reset    # esperado: sete linhas PASS e código de saída 0
```

### 5.2 Job diário

```bash
sentinela job-diario                 # D-1
sentinela job-diario --day 2026-11-05
```

## 6. Escalando a carga

O simulador roda em laço aberto. Ele dispara exatamente o `--tps` pedido e não acelera se a API tiver folga. Para achar a capacidade, suba o TPS em degraus (600, 1000, 1500, 2000…) até alguma coluna de verificação do relatório falhar:

```bash
taskset -c 12-15 sentinela carga --run oci-1500 --tps-ingest 1500 --tps-read 1500 \
    --duration 300 --warmup 30 --accounts 100000 --seed 42 --concurrency 160
```

- **`--concurrency`:** o máximo do simulador é cerca de `concurrency ÷ latência HTTP média`. Suba junto com o TPS, para não ser o simulador que satura.
- **`API_WORKERS`:** altere no `.env.local`, recarregue o ambiente e reinicie a API (`Ctrl-C` na janela 1 e `sentinela api` de novo). O total de sessões no ATP é `API_WORKERS × DB_POOL_SIZE`. Por exemplo, 20 × 8 = 160: confira se o ATP aguenta.
- **Dataset:** gere de novo com `--count` ≥ `tps × duration × 1,1`. Para 1500 TPS por 300 s, `--count 495000`.
- **Diagnóstico:** se o HTTP p99 sobe e o DB p99 fica estável, o gargalo é a API (mais workers ou CPU). Se o DB sobe junto, o gargalo é o ATP ou o pool.

## 7. O que muda em relação ao modo com containers

- **Sem limite de CPU por processo:** use o `taskset` (seção 4). Sem ele, a API e o simulador disputam os mesmos núcleos.
- **A configuração vem do shell:** é preciso carregar o `.env.local` em cada terminal (seção 3). Uma API iniciada sem o ambiente falha com `variáveis de ambiente ausentes`.
- **Processos em primeiro plano:** a API não reinicia sozinha. Use o tmux e confira o `/health` antes de cada rodada.
- **Plano de execução e janela de manutenção:** valem as mesmas observações do [oci.md, seção 5](oci.md#5-o-que-muda-em-relação-ao-desktop).
