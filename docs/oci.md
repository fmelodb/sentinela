# Rodando a POC na OCI (VM + Autonomous Database)

Na OCI, a POC roda numa VM Linux com os mesmos containers do desktop: a API e o simulador. O banco é um Autonomous Database (ATP) 26ai, em vez do Oracle local. O `compose.yaml` e os scripts são os mesmos. Muda só o `ORACLE_DSN` e a flag `--oci`.

```
┌──────────── VCN / subnet privada ────────────┐
│  VM (Oracle Linux 9, podman)                 │
│   ├─ sentinela-api  (FastAPI, :8000) ───────────► ATP 26ai (private endpoint, TLS :1521)
│   └─ sentinela-sim  (simulador) ──► api:8000 │
└──────────────────────────────────────────────┘
```

## Por que VM, e não Container Instances

- **O mesmo `compose.yaml`** do desktop. No Container Instances, cada container é um recurso OCI à parte, sem compose.
- **Logs e relatório ficam em disco** (`./logs`). No Container Instances o armazenamento é efêmero, e seria preciso enviar os arquivos ao Object Storage.
- **Iteração e diagnóstico:** `podman exec`, `podman stats`, `top`, rebuild em segundos e ajuste de `API_WORKERS` sem recriar nada.
- **Isolamento de CPU:** `API_CPUS` e `SIM_CPUS` limitam cada container. Se o simulador ainda interferir na API, ele pode ir para uma segunda VM. Basta apontar `API_URL` para o IP privado da primeira e liberar a porta 8000 na security list.

## 1. Autonomous Database

1. Crie um **Autonomous Transaction Processing** na versão 26ai, com **private endpoint** na mesma VCN da VM. Para a POC, o *mTLS* pode ficar desligado (*Require mutual TLS authentication = off*): o driver em modo thin conecta por TLS sem wallet.
2. Copie a connect string **TLS** do serviço `_tp` (*Database connection → Connection strings*). Ela tem a forma `(description=(retry_count=20)(retry_delay=3)(address=(protocol=tcps)(port=1521)(host=...))(connect_data=(service_name=..._tp.adb.oraclecloud.com))(security=(ssl_server_dn_match=yes)))`.
3. Crie o usuário da POC como `ADMIN` (Database Actions → SQL) com o script [`db/00_usuario_atp.sql`](../db/00_usuario_atp.sql), trocando `<senha>`.

## 2. VM

1. Crie uma VM `VM.Standard.E5.Flex` (sugestão: 8 OCPUs, 32 GB) com Oracle Linux 9 na subnet privada. O acesso é pelo Bastion ou por uma VPN.
2. Instale as ferramentas:

   ```bash
   sudo dnf install -y podman git python3-pip
   pip3 install --user podman-compose
   ```

3. Faça login no registro da Oracle **só se** for usar o banco local na VM (não é o caso com ATP).
4. Clone o repositório e mude para a branch `python`:

   ```bash
   git clone https://github.com/fmelodb/sentinela.git && cd sentinela && git switch python
   ```

## 3. Configuração

```bash
cp .env.example .env.local
vi .env.local
```

| Variável | Valor na OCI |
| --- | --- |
| `ORACLE_DSN` | A connect string TLS do passo 1.2, numa linha só e sem aspas |
| `ORACLE_USER` / `ORACLE_PASSWORD` | `SENTINELA` e a senha do passo 1.3 |
| `ORACLE_PWD` | vazio (não há banco local) |
| `API_WORKERS` / `DB_POOL_SIZE` | Comece com 4 × 8. Cada worker abre `DB_POOL_SIZE` sessões no ATP |
| `API_CPUS` / `SIM_CPUS` | Por exemplo, 6 e 2 numa VM de 8 OCPUs |

> A connect string tem parênteses e `=`. O `lib.sh` faz `source` do `.env.local`, então coloque o valor entre aspas simples: `ORACLE_DSN='(description=...)'`. O `env_file` do compose aceita aspas.

## 4. Subir e rodar

```bash
scripts/ambiente.sh up --oci     # build, sobe API e simulador, cria as tabelas no ATP
scripts/rodada.sh --run oci-600  # dataset, backfill, carga 600 + 600 TPS e relatório
cat logs/report/report.md
```

O teste de equivalência também roda contra o ATP. Ele é destrutivo, porque esvazia as tabelas:

```bash
podman exec sentinela-api sentinela equivalencia --reset
```

Os testes de integração se recusam a rodar contra um banco que não seja local. Para rodá-los no ATP, use `FS_IT_DESTRUCTIVE=1 scripts/test.sh`.

## 5. O que muda em relação ao desktop

- **Sem janela de manutenção sob controle:** no ATP não dá para desligar o autotask nem aumentar o redo. Registre o horário da rodada para cruzar com eventos do banco.
- **Plano de execução:** o backfill confere o plano real da leitura. A conferência precisa de `V$SQL` e `DBMS_XPLAN.DISPLAY_CURSOR`, daí os grants do script do usuário.
- **Rede:** a latência HTTP do relatório inclui a rede VM → ATP. Já a coluna *DB* (do `Server-Timing`) mede só a chamada ao banco vista pela API.
