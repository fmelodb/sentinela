# Rodando a POC na OCI sem containers (VM + Autonomous Database)

Neste modo, a API e o simulador rodam **direto na VM Linux**,  num virtualenv Python, sem podman. O banco é um Autonomous Database (ATP) 26ai. Os programas são os mesmos do modo com containers ([oci.md](oci.md)); muda só a forma de chamá-los: `sentinela <programa>` no shell, em vez de `podman exec ... sentinela <programa>`.

```
┌──────────── VCN / subnet privada ─────────────┐
│  VM (Oracle Linux 9, Python 3.12, venv)       │
│   ├─ sentinela api    (uvicorn, :8000) ──────────► ATP 26ai (private endpoint, TLS)
│   └─ sentinela carga  ──► localhost:8000      │       TRX, ABR, VU
└───────────────────────────────────────────────┘
```

Os scripts `scripts/ambiente.sh` e `scripts/rodada.sh` **não servem neste modo**, porque usam `podman exec`. A seção 6 traz os mesmos passos, um comando por vez.

Este guia cobre só o **benchmark sintético**: dados gerados pelo próprio projeto, para medir latência e vazão. A carga de dados reais e a operação de produção (importação, job diário) ficam fora dele.

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

## 5. Conferência de corretude (opcional)

A equivalência **não faz parte do benchmark**: não mede desempenho. Ela confere se a API, rodando contra este ATP, calcula as 10 variáveis exatamente como o Excel. Para isso, ingere pela API os 6 casos do Excel e os cenários de borda da spec (janelas a ±1 s, faixas horárias, VU vazio, monto nulo × zero etc.) e compara cada leitura com a referência transcrita das fórmulas.

Não é obrigatória, porque os mesmos cenários já rodam nos testes do projeto. Vale rodá-la **uma vez, na primeira instalação**, para confirmar que o ambiente da OCI (fuso, versão do banco, configuração) dá os mesmos números do desktop. Na janela 2:

```bash
sentinela equivalencia --reset    # esperado: só linhas PASS (63) e código de saída 0
```

O `--reset` **esvazia `TRX`, `ABR` e `VU`**. Por isso, rode a equivalência **antes** do backfill, nunca entre o backfill e a carga.

## 6. Rodar o benchmark sintético

Tudo nesta seção usa **dados sintéticos**: a história é gerada pelo backfill, e os eventos da carga vêm dos arquivos gerados pelo `gerador`. Nenhum dado real é necessário.

Rode na janela 2, **na raiz do projeto**, porque `./data` e `./logs` são relativos ao diretório atual. Os passos são os mesmos do `scripts/rodada.sh` ([README, seção 4.1](../README.md#41-rodada-automatizada)), com os defaults dele.

### 6.1 Os quatro passos

```bash
# 1. datasets da carga: tps × duration × 1,1 eventos por fonte (600, 200 e 100 TPS por 300 s)
sentinela gerador --dir ./data --clientes 100000 --seed 42 --trx 198000 --abr 66000 --vu 33000

# 2. backfill: DESTRUTIVO para TRX, ABR e VU. 92 dias, 100 mil clientes (~16,5 milhões de linhas);
#    no fim coleta estatísticas e confere o plano da leitura (código 4 se o plano for outro)
sentinela backfill --clientes 100000 --seed 42 --trx-dia 1 --abr-dia 0.5 --vu-dia 0.3

# 3. carga: três ingestores e o leitor em paralelo, por 300 s; grava os logs em ./logs
taskset -c 12-15 sentinela carga --run oci-cheia --dir ./data \
    --tps-trx 600 --tps-abr 200 --tps-vu 100 --tps-read 600 --fracao-get 0.5 \
    --duration 300 --warmup 30 --clientes 100000 --seed 42 --concurrency 64

# 4. relatório: lê todos os logs de ./logs e grava em ./logs/report/
sentinela relatorio ./logs
cat logs/report/report.md
```

| Passo | O que faz | Onde grava |
| --- | --- | --- |
| 1. `gerador` | Gera os eventos que a carga vai postar. Os IDs começam com `G` (TRX), `GA` (ABR) e `GV` (VU) | `data/trx.ndjson`, `data/abr.ndjson`, `data/vu.ndjson` |
| 2. `backfill` | Esvazia as três tabelas e grava, direto no banco, 92 dias de história até agora. É a base que as leituras consultam. `--trx-dia`, `--abr-dia` e `--vu-dia` são a média de eventos **por cliente por dia** de cada fonte: com 100 mil clientes, 1 / 0,5 / 0,3 dão 100 mil TRX, 50 mil ABR e 30 mil VU por dia (~16,5 milhões de linhas em 92 dias). Com a distribuição `skewed` (default), metade dos eventos cai em 1% dos clientes | `TRX`, `ABR` e `VU` no ATP |
| 3. `carga` | Posta os eventos do passo 1 pela API, com o instante trocado por agora, e lê as variáveis ao mesmo tempo | ATP (pela API) e logs em `./logs` |
| 4. `relatorio` | Agrega os logs da carga em tabelas de latência e vazão | `logs/report/` |

### 6.2 Logs da carga

Cada processo da carga grava um CSV, com uma linha por requisição, e um `.meta.json` ao lado, com o TPS alvo, o warmup e a medição da fila:

| Processo | Chamada | Log |
| --- | --- | --- |
| `ingestor_trx` / `ingestor_abr` / `ingestor_vu` | `POST /v1/<fonte>` com um evento do dataset, com o instante trocado por agora | `logs/ingestor_<fonte>-<run>.csv` |
| `leitor` | `POST /v1/features` com uma compra sintética no instante atual; na fração `--fracao-get`, `GET /v1/features/{ID_TRX}` de uma TRX postada entre 5 e 65 s antes | `logs/leitor-<run>.csv` |

As colunas do CSV são `ts_epoch_ms, program, operation, cliente, latency_us, db_us, status`. `latency_us` é o tempo HTTP visto pelo simulador; `db_us` é o tempo da chamada ao Oracle medido na API.

O `--run` é **só o nome da rodada**: aparece nos nomes dos logs e no título "Execução oci-cheia" do relatório. Sem ele, o nome é um timestamp UTC. Use um nome diferente em cada rodada; com o mesmo nome, a nova sobrescreve os logs da anterior.

### 6.3 Relatório

O `sentinela relatorio ./logs` grava três arquivos em `logs/report/`:

| Arquivo | Conteúdo |
| --- | --- |
| `report.md` | Uma tabela por rodada (`## Execução <run>`), com uma linha por programa e operação |
| `report.csv` | Os mesmos números, para planilha |
| `tps-series.csv` | Operações por segundo de cada programa, para ver se o TPS ficou estável ao longo da rodada |

Cada linha do `report.md` traz:
- ops, erros, taxa de erro, duração e TPS alvo e alcançado, sem o warmup;
- **HTTP** avg, p95, p99 e max: tempo visto pelo simulador (rede, FastAPI e banco);
- **DB** avg, p95, p99 e max: tempo da chamada ao Oracle medido na API (header `Server-Timing`).

As duas leituras aparecem separadas, como `features_post` e `features_get`. As colunas de verificação dizem se a rodada vale:

| Coluna | Passa quando |
| --- | --- |
| TPS ok | o TPS alcançado fica a menos de 5% do alvo |
| Erro ok | a taxa de erro fica abaixo de 0,1% |
| Fila ok | no máximo 1% das requisições sai com mais de 100 ms de atraso sobre o horário previsto. Se não passar, foi o simulador que não deu conta, e o TPS medido não é confiável |

O relatório agrega **todos** os logs de `./logs`. Para um relatório só da rodada atual, mova os logs antigos antes: `mkdir -p logs/antigos && mv logs/*.csv logs/*.json logs/antigos/`.

### 6.4 Rodada curta

Para testar o ambiente antes da rodada cheia (o backfill leva poucos minutos):

```bash
sentinela gerador --dir ./data --clientes 10000 --trx 6600 --abr 2000 --vu 1400
sentinela backfill --clientes 10000
taskset -c 12-15 sentinela carga --run oci-curta --dir ./data --tps-trx 100 --tps-abr 30 --tps-vu 20 \
    --tps-read 100 --duration 60 --warmup 10 --clientes 10000
sentinela relatorio ./logs
```

Depois dela, refaça os passos 1 e 2 da seção 6.1 antes da rodada cheia. Os datasets da curta são pequenos demais (a carga pararia com código 4, dataset esgotado), e o backfill dela tem só 10 mil clientes.

### 6.5 Rodadas seguintes

O dataset é fixo: toda rodada posta **os mesmos eventos, com os mesmos IDs** (`G…`, `GA…`, `GV…`). Se esses IDs ainda estiverem no banco por causa da rodada anterior, a API não duplica nada: responde 200 ("repetido") em vez de 201 e não grava a linha. Mas então a rodada mede a deduplicação, não a inserção, e o resultado não é comparável. Por isso, **antes de cada rodada os eventos da carga anterior precisam sair do banco**. Há duas formas:

| | Refazer o backfill (recomendado) | Apagar só os eventos da carga |
| --- | --- | --- |
| Comando | `sentinela backfill ...` (passo 2 da seção 6.1) | os `DELETE` abaixo |
| O que apaga | tudo: esvazia as três tabelas e recria os 92 dias | só as linhas da carga (`G…`); a história do backfill fica |
| Tempo | minutos (16,5 milhões de linhas) | segundos |
| Estatísticas e plano | recoleta e confere o plano | mantém os do último backfill |
| Fim da história | agora | a hora do último backfill |

Nenhuma das duas gera duplicidade, e as duas deixam o banco no mesmo ponto de partida. O `DELETE` **não equivale** ao backfill: ele remove só os poucos milhares de linhas da carga e preserva a história, enquanto o backfill apaga tudo e grava a história de novo.

A diferença que importa é o fim da história. O backfill grava 92 dias **até o instante em que roda**, e a carga posta eventos no instante atual. Se a próxima rodada for horas ou dias depois do backfill, fica um intervalo sem história entre os dois, e as janelas mais curtas das variáveis ficam mais vazias que na primeira rodada. Use o `DELETE` só para repetir uma rodada **logo em seguida**. Em qualquer outro caso, ou na dúvida, refaça o backfill.

O `DELETE`, como `SENTINELA`. Ele não toca a história, porque o backfill usa IDs `B…` em TRX e grava ABR e VU sem `ID_EVENTO`:

```sql
DELETE FROM trx WHERE id_trx LIKE 'G%';
DELETE FROM abr WHERE id_evento LIKE 'G%';
DELETE FROM vu  WHERE id_evento LIKE 'G%';
COMMIT;
```

O mesmo dataset serve para as rodadas seguintes, a menos que `tps × duration × 1,1` passe do que foi gerado. Nesse caso, gere de novo com contagens maiores (seção 7).

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
