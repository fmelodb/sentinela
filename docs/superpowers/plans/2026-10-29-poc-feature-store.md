# POC Feature Store de Fraude — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Construir os oito programas Java de linha de comando da POC (gerador, ingestor, leitor, backfill, job-diario, relatorio, equivalencia, schema) sobre um port hexagonal com adapters Oracle 26ai e Valkey 7.2, rodando em um pod podman.

**Architecture:** Um módulo Maven, um fat jar. `domain` contém janelas, métricas, vetor e o port `FeatureStorePort`, sem dependências. `adapter.oracle` e `adapter.valkey` implementam o port e têm cada um uma classe de manutenção (job, backfill, reset). `workload` contém amostradores e o gerador de carga open-loop. `infra` contém args, env, log CSV e sidecar. `report` contém o relatório. `verification` contém o modelo de referência e a checagem de equivalência. `app` tem um ponto de entrada por programa.

**Tech Stack:** Java 25, Maven 3.9 (via container), ojdbc17/ucp17 23.26.0.0.0, Lettuce 6.6, jackson-core 2.19, JUnit 5.13, podman 5, PowerShell 7.

**Spec:** `docs/specs/Especificação técnica — POC Feature Store de Fraude.md`, que se apoia em `docs/specs/Especificação funcional — POC Feature Store de Fraude.md`. Leia as duas antes de começar.

## Global Constraints

- Java 25 (`maven.compiler.release=25`). Sem framework de aplicação, sem DI, sem logging framework, sem picocli.
- Dinheiro sempre em centavos (`long`) no domínio e no Valkey; `NUMBER(14,2)` no Oracle. Nenhum `double` em valores monetários (exceção: o `AmountSampler` sorteia em `double` e arredonda para centavos na saída).
- Os adapters nunca leem o relógio (`Instant.now()`, `SYSTIMESTAMP`). O instante vem do chamador.
- Instantes têm precisão de microssegundo (`TIMESTAMP(6)`); `Transaction` rejeita nanos fora de múltiplo de 1.000.
- Minuto e hora são epoch UTC; dia civil em `FS_ZONE` (default `America/Sao_Paulo`). A JVM roda com `-Duser.timezone=UTC`.
- Log CSV: colunas `ts_epoch_ms,program,mode,operation,account_id,latency_us,status`, nessa ordem, com uma linha de cabeçalho. `program` ∈ {`ingestor`, `reader`}; `operation` ∈ {`ingest_oracle`, `ingest_valkey`, `read`}; `status` ∈ {`ok`, `error`}.
- Chave Valkey `fs:{<account_id>}`; campos `m:<epoch_minuto>`, `h:<epoch_hora>`, `d:<epoch_dia>`; valor `cnt|sum|min|max` em centavos.
- Códigos de saída: 0 sucesso, 1 verificação falhou, 2 argumento/env inválido, 3 store indisponível na inicialização, 4 rodada inválida, 5 erro inesperado.
- Mensagens para o usuário em português; identificadores de código em inglês, com os nomes da spec técnica.
- Pacote base `sentinela`. Pacote `report` é acrescentado ao layout da seção 3 da spec: depende só de `infra`.
- Testes de integração (`*IT.java`, `@Tag("integration")`) são **destrutivos**: esvaziam `TX`, `DAILY_AGG` e o Valkey. Rode-os antes de um Backfill, nunca no meio de uma rodada.

## Review Focus

- Instantes com nanossegundos (o `Instant.now()` do Linux tem precisão de nano/micro): o Oracle arredonda para 6 casas, o que pode mover uma transação de fatia. Esperado: `Transaction` recusa nanos não múltiplos de 1.000 e todo produtor trunca com `truncatedTo(MICROS)` (teste na Task 2; truncagem no Ingestor, Task 16, no Backfill, Task 17, e na equivalência, Tasks 19 e 20).
- Conta que nunca transacionou: esperado vetor vazio (`cnt = 0`, `sum = 0`, demais nulos) nos dois adapters, sem exceção (testes nas Tasks 12 e 14).
- Linha em branco ou malformada no dataset NDJSON: esperado ignorar linhas em branco e falhar com o número da linha nas malformadas (teste na Task 7).
- Diretório de logs com CSV sem `.meta.properties`, ou grupo vazio depois do warmup: esperado aviso em `stderr` e o resto do relatório sai normalmente (testes na Task 8).
- Valkey reiniciado ou `SCRIPT FLUSH` durante a execução: esperado que o `EVALSHA` receba `NOSCRIPT`, recarregue o script e repita uma vez, sem erro (teste na Task 14).

## Pré-requisitos do ambiente

O pod `sentinela` e o container `sentinela-oracle` (imagem `container-registry.oracle.com/database/enterprise:latest`, label `release` 23.26.0, banco 23.26.1, PDB `SENTINELA`) já existem, e `.env.local` já está preenchido. A Task 11 torna essa criação reproduzível por script. Maven não está instalado no host: todo build roda pelo `scripts/mvn.ps1` (Task 1), que executa `maven:3.9-eclipse-temurin-25` dentro do pod, com cache de dependências no volume `sentinela-m2`.

Todos os comandos abaixo são PowerShell 7, executados na raiz do repositório (`D:\Projects\Oracle\sentinela`).

## Mapa de arquivos

| Arquivo | Responsabilidade | Task |
| --- | --- | --- |
| `pom.xml`, `scripts/mvn.ps1` | Build e execução de testes | 1 |
| `src/main/java/sentinela/app/Main.java` | Despacho por subcomando e códigos de saída | 1 (cresce em 7, 9, 11, 15–19) |
| `src/main/java/sentinela/infra/{Args,UsageException,StoreException,StoreUnavailableException,ExitCodes}.java` | CLI e erros | 1 |
| `src/main/java/sentinela/domain/{Unit,Window,Bounds,WindowAgg,Slice,Transaction,AccountAgg,KeyedSlice,FeatureStorePort}.java` | Tipos do domínio | 2 |
| `src/main/java/sentinela/domain/WindowCatalog.java` | As 19 janelas e a regra de alinhamento | 3 |
| `src/main/java/sentinela/domain/{FeatureVector,SliceAggregator}.java` | Vetor de 95 valores e combinação de fatias | 4 |
| `src/main/java/sentinela/workload/{Seeds,AccountSampler,AmountSampler}.java` | Amostradores determinísticos | 5 |
| `src/main/java/sentinela/infra/{Env,CsvLog,RunMeta,ErrorReporter}.java`, `src/main/java/sentinela/workload/LoadRunner.java` | Log, sidecar, erros e gerador de carga | 6 |
| `src/main/java/sentinela/workload/Dataset.java`, `src/main/java/sentinela/app/GeneratorApp.java` | NDJSON e programa `gerador` | 7 |
| `src/main/java/sentinela/report/{LogRow,LogFile,LogLoader,Stats,GroupStats,ReportBuilder}.java` | Leitura dos logs e estatística | 8 |
| `src/main/java/sentinela/report/ReportWriter.java`, `src/main/java/sentinela/app/ReportApp.java` | Saídas do relatório e programa `relatorio` | 9 |
| `db/00_usuario.sql`, `db/01_schema.sql`, `src/main/java/sentinela/app/SchemaApp.java` | Usuário, DDL e programa `schema` | 10 |
| `Containerfile`, `.containerignore`, `scripts/ambiente.ps1`, `scripts/poc.ps1` | Ambiente podman e execução dos programas | 11 |
| `src/main/java/sentinela/adapter/oracle/{OracleDataSources,OracleTime,OracleReadSql,OracleFeatureStore}.java` | Adapter Oracle do port | 12 |
| `src/main/java/sentinela/adapter/oracle/OracleMaintenance.java` | Job, retenção, reset, bulk, reconstrução | 13 |
| `src/main/resources/lua/{ingest,put_day}.lua`, `src/main/java/sentinela/adapter/valkey/{SliceCodec,ValkeyConnections,LuaScripts,ValkeyFeatureStore}.java` | Adapter Valkey do port | 14 |
| `src/main/java/sentinela/adapter/valkey/{AsyncBatch,ValkeyMaintenance}.java` | Manutenção Valkey | 15 |
| `src/main/java/sentinela/app/{Mode,Modes,LoadPaths,IngestorApp,ReaderApp}.java` | Fábrica de modos, `ingestor`, `leitor` | 16 |
| `src/main/java/sentinela/workload/BackfillGenerator.java`, `src/main/java/sentinela/app/BackfillApp.java` | `backfill` | 17 |
| `src/main/java/sentinela/app/DailyJobApp.java` | `job-diario` | 18 |
| `src/main/java/sentinela/verification/{ReferenceModel,EquivalenceData,EquivalenceCheck}.java`, `src/main/java/sentinela/app/EquivalenceApp.java` | `equivalencia` | 19, 20 |
| `scripts/rodada.ps1` | Rodada de benchmark | 21 |

Testes espelham os pacotes em `src/test/java/sentinela/...`.

---

### Task 1: Scaffold do build, CLI e despacho

**Files:**
- Create: `pom.xml`
- Create: `scripts/mvn.ps1`
- Create: `src/main/java/sentinela/infra/UsageException.java`
- Create: `src/main/java/sentinela/infra/StoreException.java`
- Create: `src/main/java/sentinela/infra/StoreUnavailableException.java`
- Create: `src/main/java/sentinela/infra/ExitCodes.java`
- Create: `src/main/java/sentinela/infra/Args.java`
- Create: `src/main/java/sentinela/app/Main.java`
- Test: `src/test/java/sentinela/infra/ArgsTest.java`
- Test: `src/test/java/sentinela/app/MainTest.java`

**Interfaces:**
- Consumes: nada.
- Produces:
  - `Args.parse(String[] argv, Map<String,String> defaults, Set<String> flagNames) → Args`; `Args.defaults(String... keyValuePairs) → Map<String,String>` (aceita `null` como valor); getters `string(k)`, `optional(k) → Optional<String>`, `integer(k)`, `positiveInt(k)`, `longValue(k)`, `flag(k)`, `path(k) → Path`.
  - `UsageException(String)`, `StoreException(String, Throwable)`, `StoreUnavailableException(String, Throwable)`, todas `RuntimeException`.
  - `ExitCodes.OK=0, VERIFICATION_FAILED=1, USAGE=2, STORE_UNAVAILABLE=3, INVALID_RUN=4, UNEXPECTED=5` (o 5 é um acréscimo à seção 9.9 da spec técnica, para erros de I/O e afins).
  - `Main.run(String[] argv) → int` (o `main` só chama `System.exit(run(argv))`). As tasks seguintes acrescentam um `case` por programa no `switch`, sempre na forma `case "<programa>" -> <Classe>App.run(rest);`. Cada `XxxApp.run(String[] argv)` é `static int` e pode lançar `Exception`.

- [ ] **Step 1: Criar o `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>sentinela</groupId>
  <artifactId>sentinela</artifactId>
  <version>0.1.0</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.release>25</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <oracle.jdbc.version>23.26.0.0.0</oracle.jdbc.version>
    <lettuce.version>6.6.0.RELEASE</lettuce.version>
    <jackson.version>2.19.2</jackson.version>
    <junit.version>5.13.4</junit.version>
    <test.argLine>-Duser.timezone=UTC</test.argLine>
  </properties>

  <dependencies>
    <dependency>
      <groupId>com.oracle.database.jdbc</groupId>
      <artifactId>ojdbc17</artifactId>
      <version>${oracle.jdbc.version}</version>
    </dependency>
    <dependency>
      <groupId>com.oracle.database.jdbc</groupId>
      <artifactId>ucp17</artifactId>
      <version>${oracle.jdbc.version}</version>
    </dependency>
    <dependency>
      <groupId>io.lettuce</groupId>
      <artifactId>lettuce-core</artifactId>
      <version>${lettuce.version}</version>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-core</artifactId>
      <version>${jackson.version}</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <finalName>sentinela</finalName>
    <resources>
      <resource>
        <directory>src/main/resources</directory>
      </resource>
      <resource>
        <directory>db</directory>
        <targetPath>db</targetPath>
      </resource>
    </resources>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.14.0</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.5.3</version>
        <configuration>
          <argLine>${test.argLine}</argLine>
          <excludedGroups>integration</excludedGroups>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.6.0</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <createDependencyReducedPom>false</createDependencyReducedPom>
              <filters>
                <filter>
                  <artifact>*:*</artifact>
                  <excludes>
                    <exclude>META-INF/*.SF</exclude>
                    <exclude>META-INF/*.DSA</exclude>
                    <exclude>META-INF/*.RSA</exclude>
                  </excludes>
                </filter>
              </filters>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>sentinela.app.Main</mainClass>
                </transformer>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>

  <profiles>
    <profile>
      <id>integration</id>
      <build>
        <plugins>
          <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-failsafe-plugin</artifactId>
            <version>3.5.3</version>
            <configuration>
              <argLine>${test.argLine}</argLine>
              <classesDirectory>${project.build.outputDirectory}</classesDirectory>
              <groups>integration</groups>
            </configuration>
            <executions>
              <execution>
                <goals>
                  <goal>integration-test</goal>
                  <goal>verify</goal>
                </goals>
              </execution>
            </executions>
          </plugin>
        </plugins>
      </build>
    </profile>
  </profiles>
</project>
```

Se o Maven não resolver alguma versão (`Could not find artifact`), troque pela última da mesma linha listada em `https://central.sonatype.com` (ojdbc17/ucp17 23.26.x, lettuce-core 6.x, jackson-core 2.x, junit-jupiter 5.x) e registre a versão usada na mensagem de commit.

- [ ] **Step 2: Criar `scripts/mvn.ps1`**

```powershell
# Executa o Maven num container, dentro do pod sentinela quando ele existir,
# para que os testes de integração enxerguem Oracle e Valkey em localhost.
param([Parameter(ValueFromRemainingArguments = $true)][string[]] $MvnArgs)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

$podArgs = @()
podman pod exists sentinela
if ($LASTEXITCODE -eq 0) { $podArgs = @('--pod', 'sentinela') }

$envArgs = @()
$envFile = Join-Path $root '.env.local'
if (Test-Path $envFile) { $envArgs = @('--env-file', $envFile) }

podman run --rm @podArgs @envArgs `
  -v "${root}:/w" -v sentinela-m2:/root/.m2 -w /w `
  docker.io/library/maven:3.9-eclipse-temurin-25 mvn -B @MvnArgs
exit $LASTEXITCODE
```

- [ ] **Step 3: Criar as exceções e os códigos de saída**

`src/main/java/sentinela/infra/UsageException.java`:

```java
package sentinela.infra;

/** Argumento de linha de comando ou variável de ambiente inválida. Vira exit code 2. */
public final class UsageException extends RuntimeException {
    public UsageException(String message) {
        super(message);
    }
}
```

`src/main/java/sentinela/infra/StoreException.java`:

```java
package sentinela.infra;

/** Falha de uma operação contra um store durante a execução. Vira status "error" no log. */
public final class StoreException extends RuntimeException {
    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`src/main/java/sentinela/infra/StoreUnavailableException.java`:

```java
package sentinela.infra;

/** Store inacessível na inicialização de um programa. Vira exit code 3. */
public final class StoreUnavailableException extends RuntimeException {
    public StoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`src/main/java/sentinela/infra/ExitCodes.java`:

```java
package sentinela.infra;

public final class ExitCodes {
    public static final int OK = 0;
    public static final int VERIFICATION_FAILED = 1;
    public static final int USAGE = 2;
    public static final int STORE_UNAVAILABLE = 3;
    public static final int INVALID_RUN = 4;
    public static final int UNEXPECTED = 5;

    private ExitCodes() {}
}
```

- [ ] **Step 4: Escrever o teste do parser de argumentos**

`src/test/java/sentinela/infra/ArgsTest.java`:

```java
package sentinela.infra;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ArgsTest {
    private static final Map<String, String> DEFAULTS =
            Args.defaults("tps", "600", "mode", "oracle", "log", null);

    @Test
    void usesDefaultsWhenAbsent() {
        Args a = Args.parse(new String[0], DEFAULTS, Set.of("reset"));
        assertEquals(600, a.integer("tps"));
        assertEquals("oracle", a.string("mode"));
        assertTrue(a.optional("log").isEmpty());
        assertFalse(a.flag("reset"));
    }

    @Test
    void overridesValuesAndFlags() {
        Args a = Args.parse(new String[] {"--tps", "50", "--reset", "--log", "x/y.csv"}, DEFAULTS, Set.of("reset"));
        assertEquals(50, a.positiveInt("tps"));
        assertTrue(a.flag("reset"));
        assertEquals(Path.of("x/y.csv"), a.path("log"));
    }

    @Test
    void rejectsUnknownArgument() {
        UsageException e = assertThrows(UsageException.class,
                () -> Args.parse(new String[] {"--nope", "1"}, DEFAULTS, Set.of()));
        assertTrue(e.getMessage().contains("--nope"));
    }

    @Test
    void rejectsMissingValue() {
        assertThrows(UsageException.class, () -> Args.parse(new String[] {"--tps"}, DEFAULTS, Set.of()));
    }

    @Test
    void rejectsPositionalArgument() {
        assertThrows(UsageException.class, () -> Args.parse(new String[] {"tps"}, DEFAULTS, Set.of()));
    }

    @Test
    void rejectsNonIntegerAndNonPositive() {
        Args bad = Args.parse(new String[] {"--tps", "abc"}, DEFAULTS, Set.of());
        assertThrows(UsageException.class, () -> bad.integer("tps"));
        Args zero = Args.parse(new String[] {"--tps", "0"}, DEFAULTS, Set.of());
        assertThrows(UsageException.class, () -> zero.positiveInt("tps"));
    }

    @Test
    void requiredWithoutDefaultFails() {
        Args a = Args.parse(new String[0], DEFAULTS, Set.of());
        assertThrows(UsageException.class, () -> a.string("log"));
    }
}
```

- [ ] **Step 5: Rodar o teste e confirmar a falha**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest=ArgsTest`
Expected: falha de compilação, `cannot find symbol: class Args`.

- [ ] **Step 6: Implementar `Args`**

`src/main/java/sentinela/infra/Args.java`:

```java
package sentinela.infra;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Parser de "--chave valor" e "--flag". Chaves aceitas são as dos defaults mais as flags. */
public final class Args {
    private final Map<String, String> values;
    private final Set<String> flags;

    private Args(Map<String, String> values, Set<String> flags) {
        this.values = values;
        this.flags = flags;
    }

    /** Monta o mapa de defaults a partir de pares chave, valor; valor null significa "sem default". */
    public static Map<String, String> defaults(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("pares chave/valor incompletos");
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            m.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return m;
    }

    public static Args parse(String[] argv, Map<String, String> defaults, Set<String> flagNames) {
        Map<String, String> values = new HashMap<>(defaults);
        Set<String> flags = new HashSet<>();
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if (!a.startsWith("--")) {
                throw new UsageException("argumento inesperado: " + a);
            }
            String key = a.substring(2);
            if (flagNames.contains(key)) {
                flags.add(key);
                continue;
            }
            if (!defaults.containsKey(key)) {
                throw new UsageException("argumento desconhecido: " + a);
            }
            if (i + 1 >= argv.length) {
                throw new UsageException("falta valor para " + a);
            }
            values.put(key, argv[++i]);
        }
        return new Args(values, flags);
    }

    public String string(String key) {
        String v = values.get(key);
        if (v == null) {
            throw new UsageException("argumento obrigatório: --" + key);
        }
        return v;
    }

    public Optional<String> optional(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public int integer(String key) {
        String v = string(key);
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new UsageException("--" + key + " precisa ser inteiro: " + v);
        }
    }

    public int positiveInt(String key) {
        int v = integer(key);
        if (v <= 0) {
            throw new UsageException("--" + key + " precisa ser maior que zero: " + v);
        }
        return v;
    }

    public long longValue(String key) {
        String v = string(key);
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new UsageException("--" + key + " precisa ser inteiro: " + v);
        }
    }

    public boolean flag(String key) {
        return flags.contains(key);
    }

    public Path path(String key) {
        return Path.of(string(key));
    }
}
```

- [ ] **Step 7: Escrever o teste do `Main`**

`src/test/java/sentinela/app/MainTest.java`:

```java
package sentinela.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import sentinela.infra.ExitCodes;

class MainTest {
    @Test
    void noArgumentsIsUsageError() {
        assertEquals(ExitCodes.USAGE, Main.run(new String[0]));
    }

    @Test
    void unknownProgramIsUsageError() {
        assertEquals(ExitCodes.USAGE, Main.run(new String[] {"nao-existe"}));
    }
}
```

- [ ] **Step 8: Implementar `Main`**

`src/main/java/sentinela/app/Main.java`:

```java
package sentinela.app;

import java.util.Arrays;
import sentinela.infra.ExitCodes;
import sentinela.infra.StoreUnavailableException;
import sentinela.infra.UsageException;

public final class Main {
    private static final String USAGE =
            "uso: java -jar sentinela.jar <gerador|ingestor|leitor|backfill|job-diario|relatorio|equivalencia|schema>"
                    + " [--arg valor ...]";

    private Main() {}

    public static void main(String[] argv) {
        System.exit(run(argv));
    }

    static int run(String[] argv) {
        if (argv.length == 0) {
            return usage();
        }
        String[] rest = Arrays.copyOfRange(argv, 1, argv.length);
        try {
            return switch (argv[0]) {
                default -> usage();
            };
        } catch (UsageException e) {
            System.err.println("erro: " + e.getMessage());
            return ExitCodes.USAGE;
        } catch (StoreUnavailableException e) {
            System.err.println("store indisponível: " + e.getMessage());
            e.printStackTrace();
            return ExitCodes.STORE_UNAVAILABLE;
        } catch (Exception e) {
            System.err.println("erro inesperado: " + e);
            e.printStackTrace();
            return ExitCodes.UNEXPECTED;
        }
    }

    private static int usage() {
        System.err.println(USAGE);
        return ExitCodes.USAGE;
    }
}
```

A variável `rest` fica sem uso até a Task 7 acrescentar o primeiro `case`; o compilador não reclama de variável local não usada.

- [ ] **Step 9: Rodar os testes e confirmar que passam**

Run: `pwsh scripts/mvn.ps1 -q test`
Expected: `BUILD SUCCESS`, 9 testes passando (7 de `ArgsTest`, 2 de `MainTest`). A primeira execução baixa as dependências para o volume `sentinela-m2` e demora alguns minutos.

- [ ] **Step 10: Commit**

```powershell
git add pom.xml scripts/mvn.ps1 src
git commit -m "build: scaffold Maven, parser de argumentos e despacho de programas"
```

---

### Task 2: Tipos do domínio

**Files:**
- Create: `src/main/java/sentinela/domain/Unit.java`
- Create: `src/main/java/sentinela/domain/Window.java`
- Create: `src/main/java/sentinela/domain/Bounds.java`
- Create: `src/main/java/sentinela/domain/WindowAgg.java`
- Create: `src/main/java/sentinela/domain/Slice.java`
- Create: `src/main/java/sentinela/domain/Transaction.java`
- Create: `src/main/java/sentinela/domain/AccountAgg.java`
- Create: `src/main/java/sentinela/domain/KeyedSlice.java`
- Test: `src/test/java/sentinela/domain/WindowAggTest.java`
- Test: `src/test/java/sentinela/domain/TransactionTest.java`
- Test: `src/test/java/sentinela/domain/BoundsTest.java`

**Interfaces:**
- Consumes: nada.
- Produces:
  - `enum Unit { MINUTE, HOUR, DAY }`
  - `record Window(String suffix, Unit unit, int n)`
  - `record Bounds(Unit unit, long lo, long hi)` com `boolean contains(Unit u, long index)`
  - `record WindowAgg(long cnt, long sumCents, Long minCents, Long maxCents)` com `EMPTY`, `of(long amountCents)`, `plus(WindowAgg)`
  - `record Slice(Unit unit, long index, WindowAgg agg)`
  - `record Transaction(long txId, long accountId, Instant ts, long amountCents)`
  - `record AccountAgg(long accountId, WindowAgg agg)` (linha de `DAILY_AGG`)
  - `record KeyedSlice(long accountId, Slice slice)` (fatia com dono, para a reconstrução do Valkey)
  - O port `FeatureStorePort` depende de `FeatureVector` e por isso é criado na Task 4.

- [ ] **Step 1: Escrever os testes**

`src/test/java/sentinela/domain/WindowAggTest.java`:

```java
package sentinela.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WindowAggTest {
    @Test
    void emptyIsNeutral() {
        WindowAgg a = WindowAgg.of(500);
        assertEquals(a, WindowAgg.EMPTY.plus(a));
        assertEquals(a, a.plus(WindowAgg.EMPTY));
        assertEquals(WindowAgg.EMPTY, WindowAgg.EMPTY.plus(WindowAgg.EMPTY));
    }

    @Test
    void combinesCountSumMinMax() {
        WindowAgg r = WindowAgg.of(2000).plus(WindowAgg.of(30000)).plus(WindowAgg.of(13000));
        assertEquals(new WindowAgg(3, 45000, 2000L, 30000L), r);
    }

    @Test
    void rejectsInconsistentEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new WindowAgg(0, 10, null, null));
        assertThrows(IllegalArgumentException.class, () -> new WindowAgg(0, 0, 1L, 1L));
    }

    @Test
    void rejectsNonEmptyWithoutMinMax() {
        assertThrows(IllegalArgumentException.class, () -> new WindowAgg(1, 10, null, 10L));
        assertThrows(IllegalArgumentException.class, () -> new WindowAgg(-1, 0, null, null));
    }
}
```

`src/test/java/sentinela/domain/TransactionTest.java`:

```java
package sentinela.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TransactionTest {
    @Test
    void acceptsMicrosecondPrecision() {
        Instant ts = Instant.parse("2026-10-29T12:00:00.123456Z");
        assertEquals(ts, new Transaction(1, 7, ts, 100).ts());
    }

    @Test
    void rejectsSubMicrosecondPrecision() {
        Instant ts = Instant.parse("2026-10-29T12:00:00.123456789Z");
        assertThrows(IllegalArgumentException.class, () -> new Transaction(1, 7, ts, 100));
    }

    @Test
    void rejectsNonPositiveAmountAndAccount() {
        Instant ts = Instant.parse("2026-10-29T12:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> new Transaction(1, 7, ts, 0));
        assertThrows(IllegalArgumentException.class, () -> new Transaction(1, 0, ts, 10));
    }
}
```

`src/test/java/sentinela/domain/BoundsTest.java`:

```java
package sentinela.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BoundsTest {
    @Test
    void containsIsInclusiveAndUnitSensitive() {
        Bounds b = new Bounds(Unit.MINUTE, 10, 14);
        assertTrue(b.contains(Unit.MINUTE, 10));
        assertTrue(b.contains(Unit.MINUTE, 14));
        assertFalse(b.contains(Unit.MINUTE, 9));
        assertFalse(b.contains(Unit.MINUTE, 15));
        assertFalse(b.contains(Unit.HOUR, 12));
    }
}
```

- [ ] **Step 2: Rodar e confirmar a falha**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest='WindowAggTest,TransactionTest,BoundsTest'`
Expected: falha de compilação (`cannot find symbol: class WindowAgg`).

- [ ] **Step 3: Implementar os tipos**

`src/main/java/sentinela/domain/Unit.java`:

```java
package sentinela.domain;

/** Unidade de fatia: minuto e hora em epoch UTC, dia civil em FS_ZONE. */
public enum Unit {
    MINUTE,
    HOUR,
    DAY
}
```

`src/main/java/sentinela/domain/Window.java`:

```java
package sentinela.domain;

/** Uma das 19 janelas: sufixo do nome ("5m", "24h", "31d"), unidade da fatia e quantidade de fatias. */
public record Window(String suffix, Unit unit, int n) {}
```

`src/main/java/sentinela/domain/Bounds.java`:

```java
package sentinela.domain;

/** Limites de uma janela em índices epoch da unidade, ambos inclusivos. */
public record Bounds(Unit unit, long lo, long hi) {
    public boolean contains(Unit u, long index) {
        return u == unit && index >= lo && index <= hi;
    }
}
```

`src/main/java/sentinela/domain/WindowAgg.java`:

```java
package sentinela.domain;

/** As quatro métricas decomponíveis de uma fatia ou janela, em centavos. min/max são nulos quando cnt = 0. */
public record WindowAgg(long cnt, long sumCents, Long minCents, Long maxCents) {
    public static final WindowAgg EMPTY = new WindowAgg(0, 0, null, null);

    public WindowAgg {
        if (cnt < 0) {
            throw new IllegalArgumentException("cnt negativo: " + cnt);
        }
        if (cnt == 0 && (sumCents != 0 || minCents != null || maxCents != null)) {
            throw new IllegalArgumentException("agregado vazio com valores: " + sumCents + "/" + minCents + "/" + maxCents);
        }
        if (cnt > 0 && (minCents == null || maxCents == null)) {
            throw new IllegalArgumentException("agregado com cnt > 0 sem min/max");
        }
    }

    public static WindowAgg of(long amountCents) {
        return new WindowAgg(1, amountCents, amountCents, amountCents);
    }

    public WindowAgg plus(WindowAgg o) {
        if (o.cnt == 0) {
            return this;
        }
        if (cnt == 0) {
            return o;
        }
        return new WindowAgg(cnt + o.cnt, sumCents + o.sumCents,
                Math.min(minCents, o.minCents), Math.max(maxCents, o.maxCents));
    }
}
```

`src/main/java/sentinela/domain/Slice.java`:

```java
package sentinela.domain;

/** Uma fatia agregada: unidade, índice epoch da unidade e métricas. */
public record Slice(Unit unit, long index, WindowAgg agg) {}
```

`src/main/java/sentinela/domain/Transaction.java`:

```java
package sentinela.domain;

import java.time.Instant;

/** Transação com instante em precisão de microssegundo (TIMESTAMP(6) no Oracle). */
public record Transaction(long txId, long accountId, Instant ts, long amountCents) {
    public Transaction {
        if (accountId <= 0) {
            throw new IllegalArgumentException("account_id precisa ser positivo: " + accountId);
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amount precisa ser positivo: " + amountCents);
        }
        if (ts.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("ts com precisão abaixo de microssegundo: " + ts);
        }
    }
}
```

`src/main/java/sentinela/domain/AccountAgg.java`:

```java
package sentinela.domain;

/** Agregado de uma conta num dia (linha de DAILY_AGG). */
public record AccountAgg(long accountId, WindowAgg agg) {}
```

`src/main/java/sentinela/domain/KeyedSlice.java`:

```java
package sentinela.domain;

/** Fatia de uma conta específica, usada para reconstruir o Valkey a partir do Oracle. */
public record KeyedSlice(long accountId, Slice slice) {}
```

- [ ] **Step 4: Rodar e confirmar que passam**

Run: `pwsh scripts/mvn.ps1 -q test`
Expected: `BUILD SUCCESS`, todos os testes passando.

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat(domain): tipos de transação, fatia, limites e agregado"
```

---

### Task 3: Catálogo de janelas

**Files:**
- Create: `src/main/java/sentinela/domain/WindowCatalog.java`
- Test: `src/test/java/sentinela/domain/WindowCatalogTest.java`

**Interfaces:**
- Consumes: `Unit`, `Window`, `Bounds` (Task 2).
- Produces:
  - `WindowCatalog.WINDOWS: List<Window>` (19, na ordem da spec) e `WindowCatalog.SIZE = 19`.
  - `WindowCatalog.FIRST_DAY_WINDOW = 15` (índice da janela `7d`).
  - `WindowCatalog.bounds(Instant now, ZoneId zone) → List<Bounds>` (um por janela, mesma ordem).
  - `WindowCatalog.epochMinute(Instant)`, `epochHour(Instant)`, `epochDay(Instant, ZoneId) → long`.
  - `WindowCatalog.sliceStart(Unit, long index, ZoneId) → Instant` (início da fatia).

- [ ] **Step 1: Escrever o teste**

`src/test/java/sentinela/domain/WindowCatalogTest.java`:

```java
package sentinela.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindowCatalogTest {
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    @Test
    void hasNineteenWindowsInSpecOrder() {
        List<String> suffixes = WindowCatalog.WINDOWS.stream().map(Window::suffix).toList();
        assertEquals(List.of("1m", "5m", "10m", "15m", "30m", "1h", "2h", "3h", "5h", "6h", "9h", "12h",
                "24h", "48h", "72h", "7d", "31d", "62d", "92d"), suffixes);
        assertEquals(19, WindowCatalog.SIZE);
        assertEquals("7d", WindowCatalog.WINDOWS.get(WindowCatalog.FIRST_DAY_WINDOW).suffix());
    }

    @Test
    void oneHourUsesSixtyMinuteSlices() {
        assertEquals(new Window("1h", Unit.MINUTE, 60), WindowCatalog.WINDOWS.get(5));
    }

    @Test
    void minuteAndHourBoundsAlignToCurrentSlice() {
        Instant now = Instant.parse("2026-10-29T15:07:30Z");
        long m = now.getEpochSecond() / 60;
        long h = now.getEpochSecond() / 3600;
        List<Bounds> b = WindowCatalog.bounds(now, SP);
        assertEquals(19, b.size());
        assertEquals(new Bounds(Unit.MINUTE, m, m), b.get(0));
        assertEquals(new Bounds(Unit.MINUTE, m - 4, m), b.get(1));
        assertEquals(new Bounds(Unit.MINUTE, m - 29, m), b.get(4));
        assertEquals(new Bounds(Unit.MINUTE, m - 59, m), b.get(5));
        assertEquals(new Bounds(Unit.HOUR, h - 1, h), b.get(6));
        assertEquals(new Bounds(Unit.HOUR, h - 23, h), b.get(12));
        assertEquals(new Bounds(Unit.HOUR, h - 71, h), b.get(14));
    }

    @Test
    void dayBoundsExcludeTodayInZone() {
        Instant now = Instant.parse("2026-10-29T15:07:30Z"); // 12:07 em São Paulo
        long today = LocalDate.of(2026, 10, 29).toEpochDay();
        List<Bounds> b = WindowCatalog.bounds(now, SP);
        assertEquals(new Bounds(Unit.DAY, today - 7, today - 1), b.get(15));
        assertEquals(new Bounds(Unit.DAY, today - 31, today - 1), b.get(16));
        assertEquals(new Bounds(Unit.DAY, today - 92, today - 1), b.get(18));
    }

    @Test
    void civilDayFollowsZoneNotUtc() {
        Instant now = Instant.parse("2026-10-30T01:30:00Z"); // 22:30 de 29/10 em São Paulo
        assertEquals(LocalDate.of(2026, 10, 29).toEpochDay(), WindowCatalog.epochDay(now, SP));
        assertEquals(LocalDate.of(2026, 10, 30).toEpochDay(), WindowCatalog.epochDay(now, ZoneOffset.UTC));
    }

    @Test
    void exactSliceBoundaryBelongsToNewSlice() {
        Instant boundary = Instant.parse("2026-10-29T15:00:00Z");
        long m = boundary.getEpochSecond() / 60;
        long h = boundary.getEpochSecond() / 3600;
        assertEquals(m, WindowCatalog.epochMinute(boundary));
        assertEquals(m - 1, WindowCatalog.epochMinute(boundary.minusNanos(1_000)));
        assertEquals(h, WindowCatalog.epochHour(boundary));
        assertEquals(h - 1, WindowCatalog.epochHour(boundary.minusNanos(1_000)));
    }

    @Test
    void negativeEpochUsesFloor() {
        assertEquals(-1, WindowCatalog.epochMinute(Instant.ofEpochSecond(-1)));
        assertEquals(-1, WindowCatalog.epochHour(Instant.ofEpochSecond(-1)));
    }

    @Test
    void sliceStartInvertsIndex() {
        Instant t = Instant.parse("2026-10-29T15:07:30Z");
        assertEquals(Instant.parse("2026-10-29T15:07:00Z"),
                WindowCatalog.sliceStart(Unit.MINUTE, WindowCatalog.epochMinute(t), SP));
        assertEquals(Instant.parse("2026-10-29T15:00:00Z"),
                WindowCatalog.sliceStart(Unit.HOUR, WindowCatalog.epochHour(t), SP));
        assertEquals(Instant.parse("2026-10-29T03:00:00Z"),
                WindowCatalog.sliceStart(Unit.DAY, LocalDate.of(2026, 10, 29).toEpochDay(), SP));
    }
}
```

- [ ] **Step 2: Rodar e confirmar a falha**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest=WindowCatalogTest`
Expected: falha de compilação (`cannot find symbol: class WindowCatalog`).

- [ ] **Step 3: Implementar**

`src/main/java/sentinela/domain/WindowCatalog.java`:

```java
package sentinela.domain;

import static sentinela.domain.Unit.DAY;
import static sentinela.domain.Unit.HOUR;
import static sentinela.domain.Unit.MINUTE;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * As 19 janelas da seção 3 da spec funcional e a regra única de alinhamento.
 * Minutos e horas: fatia corrente e as n-1 anteriores, em epoch UTC.
 * Dias: os n dias civis fechados antes de hoje, em FS_ZONE.
 */
public final class WindowCatalog {
    public static final List<Window> WINDOWS = List.of(
            new Window("1m", MINUTE, 1),
            new Window("5m", MINUTE, 5),
            new Window("10m", MINUTE, 10),
            new Window("15m", MINUTE, 15),
            new Window("30m", MINUTE, 30),
            new Window("1h", MINUTE, 60),
            new Window("2h", HOUR, 2),
            new Window("3h", HOUR, 3),
            new Window("5h", HOUR, 5),
            new Window("6h", HOUR, 6),
            new Window("9h", HOUR, 9),
            new Window("12h", HOUR, 12),
            new Window("24h", HOUR, 24),
            new Window("48h", HOUR, 48),
            new Window("72h", HOUR, 72),
            new Window("7d", DAY, 7),
            new Window("31d", DAY, 31),
            new Window("62d", DAY, 62),
            new Window("92d", DAY, 92));

    public static final int SIZE = WINDOWS.size();
    public static final int FIRST_DAY_WINDOW = 15;

    private WindowCatalog() {}

    public static long epochMinute(Instant t) {
        return Math.floorDiv(t.getEpochSecond(), 60);
    }

    public static long epochHour(Instant t) {
        return Math.floorDiv(t.getEpochSecond(), 3600);
    }

    public static long epochDay(Instant t, ZoneId zone) {
        return LocalDate.ofInstant(t, zone).toEpochDay();
    }

    public static Instant sliceStart(Unit unit, long index, ZoneId zone) {
        return switch (unit) {
            case MINUTE -> Instant.ofEpochSecond(index * 60);
            case HOUR -> Instant.ofEpochSecond(index * 3600);
            case DAY -> LocalDate.ofEpochDay(index).atStartOfDay(zone).toInstant();
        };
    }

    public static List<Bounds> bounds(Instant now, ZoneId zone) {
        long m = epochMinute(now);
        long h = epochHour(now);
        long d = epochDay(now, zone);
        return WINDOWS.stream().map(w -> switch (w.unit()) {
            case MINUTE -> new Bounds(MINUTE, m - (w.n() - 1), m);
            case HOUR -> new Bounds(HOUR, h - (w.n() - 1), h);
            case DAY -> new Bounds(DAY, d - w.n(), d - 1);
        }).toList();
    }
}
```

- [ ] **Step 4: Rodar e confirmar que passa**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest=WindowCatalogTest`
Expected: `BUILD SUCCESS`, 8 testes.

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat(domain): catálogo das 19 janelas e regra de alinhamento"
```

---

### Task 4: Vetor de features, combinação de fatias e port

**Files:**
- Create: `src/main/java/sentinela/domain/FeatureVector.java`
- Create: `src/main/java/sentinela/domain/SliceAggregator.java`
- Create: `src/main/java/sentinela/domain/FeatureStorePort.java`
- Test: `src/test/java/sentinela/domain/FeatureVectorTest.java`
- Test: `src/test/java/sentinela/domain/SliceAggregatorTest.java`

**Interfaces:**
- Consumes: `WindowAgg`, `Slice`, `Bounds`, `Transaction`, `WindowCatalog` (Tasks 2–3).
- Produces:
  - `record FeatureVector(List<WindowAgg> aggs)` com `static empty()`, `agg(int i)`, `values() → List<BigDecimal>` (95, anuláveis), `static names() → List<String>` (95).
  - `SliceAggregator.aggregate(Iterable<Slice> slices, List<Bounds> bounds) → FeatureVector`.
  - `interface FeatureStorePort { void ingest(Transaction tx); FeatureVector read(long accountId, Instant now); }`.

- [ ] **Step 1: Escrever os testes**

`src/test/java/sentinela/domain/FeatureVectorTest.java`:

```java
package sentinela.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeatureVectorTest {
    @Test
    void namesFollowWindowAndMetricOrder() {
        List<String> n = FeatureVector.names();
        assertEquals(95, n.size());
        assertEquals(List.of("cnt_1m", "sum_1m", "min_1m", "max_1m", "avg_1m"), n.subList(0, 5));
        assertEquals("cnt_1h", n.get(25));
        assertEquals("cnt_7d", n.get(75));
        assertEquals("avg_92d", n.get(94));
    }

    @Test
    void emptyVectorHasZeroCountAndSumAndNullOthers() {
        List<BigDecimal> v = FeatureVector.empty().values();
        assertEquals(95, v.size());
        for (int w = 0; w < 19; w++) {
            assertEquals(0, v.get(w * 5).compareTo(BigDecimal.ZERO), "cnt");
            assertEquals(0, v.get(w * 5 + 1).compareTo(BigDecimal.ZERO), "sum");
            assertNull(v.get(w * 5 + 2), "min");
            assertNull(v.get(w * 5 + 3), "max");
            assertNull(v.get(w * 5 + 4), "avg");
        }
    }

    @Test
    void valuesConvertCentsAndComputeAverage() {
        List<BigDecimal> v = withFirst(new WindowAgg(3, 45000, 2000L, 30000L)).values();
        assertEquals(new BigDecimal("3"), v.get(0));
        assertEquals(new BigDecimal("450.00"), v.get(1));
        assertEquals(new BigDecimal("20.00"), v.get(2));
        assertEquals(new BigDecimal("300.00"), v.get(3));
        assertEquals(new BigDecimal("150.0000"), v.get(4));
    }

    @Test
    void averageUsesHalfEvenAtScaleFour() {
        // 1 centavo / 8 = R$ 0,00125 → HALF_EVEN dá 0,0012 (HALF_UP daria 0,0013)
        assertEquals(new BigDecimal("0.0012"), withFirst(new WindowAgg(8, 1, 1L, 1L)).values().get(4));
    }

    @Test
    void rejectsWrongSize() {
        assertThrows(IllegalArgumentException.class, () -> new FeatureVector(List.of(WindowAgg.EMPTY)));
    }

    @Test
    void equalityComparesAggregates() {
        assertEquals(withFirst(WindowAgg.of(10)), withFirst(WindowAgg.of(10)));
        assertNotEquals(withFirst(WindowAgg.of(10)), withFirst(WindowAgg.of(11)));
    }

    private static FeatureVector withFirst(WindowAgg first) {
        List<WindowAgg> aggs = new ArrayList<>(Collections.nCopies(19, WindowAgg.EMPTY));
        aggs.set(0, first);
        return new FeatureVector(aggs);
    }
}
```

`src/test/java/sentinela/domain/SliceAggregatorTest.java`:

```java
package sentinela.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class SliceAggregatorTest {
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Instant NOW = Instant.parse("2026-10-29T15:07:30Z");
    private static final long M = WindowCatalog.epochMinute(NOW);
    private static final long H = WindowCatalog.epochHour(NOW);
    private static final long T = WindowCatalog.epochDay(NOW, SP);

    private static FeatureVector agg(Slice... slices) {
        return SliceAggregator.aggregate(List.of(slices), WindowCatalog.bounds(NOW, SP));
    }

    @Test
    void noSlicesGivesEmptyVector() {
        assertEquals(FeatureVector.empty(), agg());
    }

    @Test
    void currentMinuteCountsInAllMinuteWindowsAndOneHour() {
        FeatureVector v = agg(new Slice(Unit.MINUTE, M, WindowAgg.of(100)));
        for (int i = 0; i <= 5; i++) {
            assertEquals(1, v.agg(i).cnt(), "janela " + i);
        }
        assertEquals(0, v.agg(6).cnt(), "2h usa fatias de hora, não de minuto");
    }

    @Test
    void minuteSliceRespectsLowerBound() {
        FeatureVector v = agg(new Slice(Unit.MINUTE, M - 4, WindowAgg.of(100)),
                new Slice(Unit.MINUTE, M - 5, WindowAgg.of(200)));
        assertEquals(1, v.agg(1).cnt(), "5m: só M-4");
        assertEquals(2, v.agg(2).cnt(), "10m: M-4 e M-5");
        assertEquals(new WindowAgg(2, 300, 100L, 200L), v.agg(5));
    }

    @Test
    void minuteSliceOutsideOneHourIsIgnored() {
        assertEquals(FeatureVector.empty(), agg(new Slice(Unit.MINUTE, M - 60, WindowAgg.of(100))));
    }

    @Test
    void futureSlicesAreIgnored() {
        FeatureVector v = agg(new Slice(Unit.MINUTE, M + 1, WindowAgg.of(100)),
                new Slice(Unit.HOUR, H + 1, WindowAgg.of(100)),
                new Slice(Unit.DAY, T, WindowAgg.of(100)));
        assertEquals(FeatureVector.empty(), v);
    }

    @Test
    void hourSlicesFeedHourWindows() {
        FeatureVector v = agg(new Slice(Unit.HOUR, H - 2, new WindowAgg(2, 500, 100L, 400L)));
        assertEquals(0, v.agg(6).cnt(), "2h = H-1..H");
        assertEquals(2, v.agg(7).cnt(), "3h = H-2..H");
        assertEquals(2, v.agg(14).cnt(), "72h");
    }

    @Test
    void daySlicesFeedDayWindowsOnlyWhenClosed() {
        FeatureVector v = agg(new Slice(Unit.DAY, T - 1, WindowAgg.of(100)),
                new Slice(Unit.DAY, T - 8, WindowAgg.of(300)),
                new Slice(Unit.DAY, T - 93, WindowAgg.of(999)));
        assertEquals(new WindowAgg(1, 100, 100L, 100L), v.agg(15));
        assertEquals(new WindowAgg(2, 400, 100L, 300L), v.agg(16));
        assertEquals(2, v.agg(18).cnt(), "T-93 fica fora de 92d");
    }
}
```

- [ ] **Step 2: Rodar e confirmar a falha**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest='FeatureVectorTest,SliceAggregatorTest'`
Expected: falha de compilação (`cannot find symbol: class FeatureVector`).

- [ ] **Step 3: Implementar**

`src/main/java/sentinela/domain/FeatureVector.java`:

```java
package sentinela.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Os 19 agregados de uma leitura. values() expande para os 95 valores na ordem da spec. */
public record FeatureVector(List<WindowAgg> aggs) {
    private static final List<String> METRICS = List.of("cnt", "sum", "min", "max", "avg");
    private static final List<String> NAMES = buildNames();
    private static final FeatureVector EMPTY =
            new FeatureVector(Collections.nCopies(WindowCatalog.SIZE, WindowAgg.EMPTY));

    public FeatureVector {
        if (aggs.size() != WindowCatalog.SIZE) {
            throw new IllegalArgumentException("vetor precisa de " + WindowCatalog.SIZE + " janelas: " + aggs.size());
        }
        aggs = List.copyOf(aggs);
    }

    public static FeatureVector empty() {
        return EMPTY;
    }

    public static List<String> names() {
        return NAMES;
    }

    public WindowAgg agg(int window) {
        return aggs.get(window);
    }

    /** 95 valores; min, max e avg são nulos quando cnt = 0. */
    public List<BigDecimal> values() {
        List<BigDecimal> out = new ArrayList<>(NAMES.size());
        for (WindowAgg a : aggs) {
            out.add(BigDecimal.valueOf(a.cnt()));
            out.add(BigDecimal.valueOf(a.sumCents(), 2));
            out.add(a.minCents() == null ? null : BigDecimal.valueOf(a.minCents(), 2));
            out.add(a.maxCents() == null ? null : BigDecimal.valueOf(a.maxCents(), 2));
            out.add(a.cnt() == 0 ? null
                    : BigDecimal.valueOf(a.sumCents())
                            .divide(BigDecimal.valueOf(a.cnt() * 100), 4, RoundingMode.HALF_EVEN));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<String> buildNames() {
        List<String> names = new ArrayList<>();
        for (Window w : WindowCatalog.WINDOWS) {
            for (String m : METRICS) {
                names.add(m + "_" + w.suffix());
            }
        }
        return List.copyOf(names);
    }
}
```

`src/main/java/sentinela/domain/SliceAggregator.java`:

```java
package sentinela.domain;

import java.util.Arrays;
import java.util.List;

/** Combina fatias nas janelas. Fatias fora de qualquer limite, inclusive futuras, são ignoradas. */
public final class SliceAggregator {
    private SliceAggregator() {}

    public static FeatureVector aggregate(Iterable<Slice> slices, List<Bounds> bounds) {
        WindowAgg[] acc = new WindowAgg[bounds.size()];
        Arrays.fill(acc, WindowAgg.EMPTY);
        for (Slice s : slices) {
            for (int i = 0; i < acc.length; i++) {
                if (bounds.get(i).contains(s.unit(), s.index())) {
                    acc[i] = acc[i].plus(s.agg());
                }
            }
        }
        return new FeatureVector(Arrays.asList(acc));
    }
}
```

`src/main/java/sentinela/domain/FeatureStorePort.java`:

```java
package sentinela.domain;

import java.time.Instant;

/** O único contrato que o domínio conhece. Implementações nunca leem o relógio. */
public interface FeatureStorePort {
    void ingest(Transaction tx);

    FeatureVector read(long accountId, Instant now);
}
```

- [ ] **Step 4: Rodar e confirmar que passam**

Run: `pwsh scripts/mvn.ps1 -q test`
Expected: `BUILD SUCCESS`, todos os testes.

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat(domain): vetor de 95 features, combinação de fatias e port"
```

---

### Task 5: Amostradores determinísticos

**Files:**
- Create: `src/main/java/sentinela/workload/Seeds.java`
- Create: `src/main/java/sentinela/workload/AccountSampler.java`
- Create: `src/main/java/sentinela/workload/AmountSampler.java`
- Test: `src/test/java/sentinela/workload/SamplersTest.java`

**Interfaces:**
- Consumes: `UsageException` (Task 1).
- Produces:
  - `Seeds.stream(long seed, String purpose) → SplittableRandom`. Propósitos usados no projeto: `"gerador.contas"`, `"gerador.valores"`, `"leitor.contas"`, `"backfill.contas"`, `"backfill.valores"`, `"backfill.ts"`, `"backfill.dia." + epochDay`, `"equivalencia"`.
  - `AccountSampler.Distribution { UNIFORM, SKEWED }` com `static parse(String)` (`"uniform"`/`"skewed"`).
  - `new AccountSampler(long accounts, Distribution d, SplittableRandom rnd)`, `long next()` em `[1, accounts]`, `long hotAccounts()`.
  - `new AmountSampler(SplittableRandom rnd)`, `long nextCents()` em `[100, 5_000_000]`; constantes `MIN_CENTS`, `MAX_CENTS`.

- [ ] **Step 1: Escrever o teste**

`src/test/java/sentinela/workload/SamplersTest.java`:

```java
package sentinela.workload;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import sentinela.infra.UsageException;
import sentinela.workload.AccountSampler.Distribution;

class SamplersTest {
    private static final int N = 200_000;

    @Test
    void sameSeedAndPurposeGiveSameSequence() {
        AccountSampler a = new AccountSampler(1000, Distribution.SKEWED, Seeds.stream(42, "x"));
        AccountSampler b = new AccountSampler(1000, Distribution.SKEWED, Seeds.stream(42, "x"));
        for (int i = 0; i < 1000; i++) {
            assertEquals(a.next(), b.next());
        }
    }

    @Test
    void differentPurposesGiveDifferentSequences() {
        AccountSampler a = new AccountSampler(1_000_000, Distribution.UNIFORM, Seeds.stream(42, "a"));
        AccountSampler b = new AccountSampler(1_000_000, Distribution.UNIFORM, Seeds.stream(42, "b"));
        int equal = 0;
        for (int i = 0; i < 1000; i++) {
            if (a.next() == b.next()) {
                equal++;
            }
        }
        assertTrue(equal < 5, "sequências deveriam ser independentes: " + equal);
    }

    @Test
    void skewedPutsAboutHalfOfDrawsInHotAccounts() {
        AccountSampler s = new AccountSampler(100_000, Distribution.SKEWED, Seeds.stream(7, "t"));
        assertEquals(1000, s.hotAccounts());
        int hot = 0;
        for (int i = 0; i < N; i++) {
            long acc = s.next();
            assertTrue(acc >= 1 && acc <= 100_000);
            if (acc <= s.hotAccounts()) {
                hot++;
            }
        }
        double share = hot / (double) N; // esperado 0,5 + 0,5 × 1% = 0,505
        assertTrue(share > 0.49 && share < 0.52, "fração nas quentes: " + share);
    }

    @Test
    void uniformSpreadsDraws() {
        AccountSampler s = new AccountSampler(100_000, Distribution.UNIFORM, Seeds.stream(7, "t"));
        int hot = 0;
        for (int i = 0; i < N; i++) {
            if (s.next() <= 1000) {
                hot++;
            }
        }
        double share = hot / (double) N;
        assertTrue(share > 0.008 && share < 0.012, "fração nas 1% primeiras: " + share);
    }

    @Test
    void tinyUniverseStillHasOneHotAccount() {
        AccountSampler s = new AccountSampler(5, Distribution.SKEWED, Seeds.stream(1, "t"));
        assertEquals(1, s.hotAccounts());
        for (int i = 0; i < 100; i++) {
            long acc = s.next();
            assertTrue(acc >= 1 && acc <= 5);
        }
    }

    @Test
    void parsesDistribution() {
        assertEquals(Distribution.UNIFORM, Distribution.parse("uniform"));
        assertEquals(Distribution.SKEWED, Distribution.parse("skewed"));
        assertThrows(UsageException.class, () -> Distribution.parse("zipf"));
    }

    @Test
    void amountsAreBoundedWithMedianNear150Reais() {
        AmountSampler s = new AmountSampler(Seeds.stream(42, "v"));
        long[] v = new long[N];
        for (int i = 0; i < N; i++) {
            v[i] = s.nextCents();
            assertTrue(v[i] >= AmountSampler.MIN_CENTS && v[i] <= AmountSampler.MAX_CENTS, "fora da faixa: " + v[i]);
        }
        Arrays.sort(v);
        long median = v[N / 2];
        assertTrue(median > 13_500 && median < 16_500, "mediana em centavos: " + median);
    }
}
```

- [ ] **Step 2: Rodar e confirmar a falha**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest=SamplersTest`
Expected: falha de compilação (`cannot find symbol: class AccountSampler`).

- [ ] **Step 3: Implementar**

`src/main/java/sentinela/workload/Seeds.java`:

```java
package sentinela.workload;

import java.util.SplittableRandom;

/** Um fluxo pseudoaleatório independente por propósito, derivado do --seed. */
public final class Seeds {
    private Seeds() {}

    public static SplittableRandom stream(long seed, String purpose) {
        return new SplittableRandom(seed * 0x9E3779B97F4A7C15L ^ purpose.hashCode());
    }
}
```

`src/main/java/sentinela/workload/AccountSampler.java`:

```java
package sentinela.workload;

import java.util.SplittableRandom;
import sentinela.infra.UsageException;

/**
 * Sorteia contas em [1, N]. skewed: com probabilidade 0,5 sorteia entre as contas quentes
 * [1, max(1, N/100)]; senão, uniforme em [1, N]. Cerca de 50% das transações caem em 1% das contas.
 */
public final class AccountSampler {
    public enum Distribution {
        UNIFORM,
        SKEWED;

        public static Distribution parse(String s) {
            return switch (s) {
                case "uniform" -> UNIFORM;
                case "skewed" -> SKEWED;
                default -> throw new UsageException("--distribution precisa ser uniform ou skewed: " + s);
            };
        }
    }

    private final long accounts;
    private final long hot;
    private final Distribution distribution;
    private final SplittableRandom rnd;

    public AccountSampler(long accounts, Distribution distribution, SplittableRandom rnd) {
        if (accounts <= 0) {
            throw new IllegalArgumentException("universo de contas vazio: " + accounts);
        }
        this.accounts = accounts;
        this.hot = Math.max(1, accounts / 100);
        this.distribution = distribution;
        this.rnd = rnd;
    }

    public long next() {
        if (distribution == Distribution.SKEWED && rnd.nextBoolean()) {
            return 1 + rnd.nextLong(hot);
        }
        return 1 + rnd.nextLong(accounts);
    }

    public long hotAccounts() {
        return hot;
    }
}
```

`src/main/java/sentinela/workload/AmountSampler.java`:

```java
package sentinela.workload;

import java.util.SplittableRandom;

/** Valores lognormais com mediana R$ 150 (μ = ln 150, σ = 1,2), em centavos, limitados a [R$ 1; R$ 50.000]. */
public final class AmountSampler {
    public static final long MIN_CENTS = 100;
    public static final long MAX_CENTS = 5_000_000;
    private static final double MU = Math.log(150.0);
    private static final double SIGMA = 1.2;

    private final SplittableRandom rnd;

    public AmountSampler(SplittableRandom rnd) {
        this.rnd = rnd;
    }

    public long nextCents() {
        double reais = Math.exp(MU + SIGMA * rnd.nextGaussian());
        return Math.clamp(Math.round(reais * 100), MIN_CENTS, MAX_CENTS);
    }
}
```

- [ ] **Step 4: Rodar e confirmar que passa**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest=SamplersTest`
Expected: `BUILD SUCCESS`, 7 testes.

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat(workload): amostradores de contas e valores determinísticos por seed"
```

---

### Task 6: Ambiente, log CSV, sidecar, erros e gerador de carga

**Files:**
- Create: `src/main/java/sentinela/infra/Env.java`
- Create: `src/main/java/sentinela/infra/CsvLog.java`
- Create: `src/main/java/sentinela/infra/RunMeta.java`
- Create: `src/main/java/sentinela/infra/ErrorReporter.java`
- Create: `src/main/java/sentinela/workload/LoadRunner.java`
- Test: `src/test/java/sentinela/infra/EnvTest.java`
- Test: `src/test/java/sentinela/infra/CsvLogTest.java`
- Test: `src/test/java/sentinela/infra/RunMetaTest.java`
- Test: `src/test/java/sentinela/infra/ErrorReporterTest.java`
- Test: `src/test/java/sentinela/workload/LoadRunnerTest.java`

**Interfaces:**
- Consumes: `UsageException` (Task 1).
- Produces:
  - `record Env(String oracleUrl, String oracleUser, String oraclePassword, String valkeyUrl, ZoneId zone)` com `static fromSystem()`, `static from(Map<String,String>)`, `requireOracle()`, `requireValkey()`. O `toString()` esconde a senha.
  - `CsvLog(Path path, String program, String mode)` (cria diretórios, escreve o cabeçalho), `record(long tsEpochMs, String operation, long accountId, long latencyUs, boolean ok)`, `close()`. Constante `CsvLog.HEADER`.
  - `RunMeta.sidecarOf(Path csv) → Path`, `RunMeta.write(Path csv, Map<String,String>)`, `RunMeta.put(Path csv, String key, String value)`, `RunMeta.read(Path csv) → Properties`.
  - `ErrorReporter(PrintStream err)`, `report(Throwable)`, `int total()`, `close()`.
  - `LoadRunner<T>(Iterator<T> source, List<LoadRunner.Step<T>> steps, int tps, Duration duration, int threads, CsvLog log, ErrorReporter errors)`, `Result run()`; `record Step<T>(String operation, Consumer<T> call, ToLongFunction<T> accountOf)`; `record Result(long dispatched, boolean sourceExhausted)`.

- [ ] **Step 1: Escrever os testes de `Env`, `RunMeta` e `ErrorReporter`**

`src/test/java/sentinela/infra/EnvTest.java`:

```java
package sentinela.infra;

import static org.junit.jupiter.api.Assertions.*;

import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvTest {
    @Test
    void defaultsZoneToSaoPaulo() {
        assertEquals(ZoneId.of("America/Sao_Paulo"), Env.from(Map.of()).zone());
    }

    @Test
    void rejectsInvalidZone() {
        assertThrows(UsageException.class, () -> Env.from(Map.of("FS_ZONE", "Marte/Olimpo")));
    }

    @Test
    void requireOracleListsMissingVariables() {
        Env env = Env.from(Map.of("ORACLE_URL", "jdbc:oracle:thin:@//h:1521/S"));
        UsageException e = assertThrows(UsageException.class, env::requireOracle);
        assertTrue(e.getMessage().contains("ORACLE_USER"));
        assertTrue(e.getMessage().contains("ORACLE_PASSWORD"));
        assertFalse(e.getMessage().contains("ORACLE_URL"));
    }

    @Test
    void requireValkeyFailsWhenAbsentOrBlank() {
        assertThrows(UsageException.class, () -> Env.from(Map.of("VALKEY_URL", " ")).requireValkey());
        Env.from(Map.of("VALKEY_URL", "redis://localhost:6379")).requireValkey();
    }

    @Test
    void toStringHidesPassword() {
        Env env = Env.from(Map.of("ORACLE_PASSWORD", "segredo123"));
        assertFalse(env.toString().contains("segredo123"));
    }
}
```

`src/test/java/sentinela/infra/RunMetaTest.java`:

```java
package sentinela.infra;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunMetaTest {
    @Test
    void sidecarReplacesCsvExtension() {
        assertEquals(Path.of("logs/reader-oracle+valkey-r1.meta.properties"),
                RunMeta.sidecarOf(Path.of("logs/reader-oracle+valkey-r1.csv")));
    }

    @Test
    void writesReadsAndUpdates(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("ingestor-oracle-r1.csv");
        RunMeta.write(csv, Map.of("tps", "600", "warmup", "30"));
        RunMeta.put(csv, "end_epoch_ms", "123");
        Properties p = RunMeta.read(csv);
        assertEquals("600", p.getProperty("tps"));
        assertEquals("30", p.getProperty("warmup"));
        assertEquals("123", p.getProperty("end_epoch_ms"));
    }
}
```

`src/test/java/sentinela/infra/ErrorReporterTest.java`:

```java
package sentinela.infra;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ErrorReporterTest {
    @Test
    void countsAllAndPrintsOnlyFirstTenTraces() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ErrorReporter r = new ErrorReporter(new PrintStream(buf, true, StandardCharsets.UTF_8))) {
            for (int i = 0; i < 25; i++) {
                r.report(new IllegalStateException("falha " + i));
            }
            assertEquals(25, r.total());
        }
        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("falha 9"));
        assertFalse(out.contains("falha 10"));
        assertTrue(out.contains("total de erros: 25"));
        assertTrue(out.contains("IllegalStateException"));
    }
}
```

- [ ] **Step 2: Escrever os testes de `CsvLog` e `LoadRunner`**

`src/test/java/sentinela/infra/CsvLogTest.java`:

```java
package sentinela.infra;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvLogTest {
    @Test
    void writesHeaderAndOneLinePerRecord(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("sub/reader-oracle-r1.csv");
        try (CsvLog log = new CsvLog(csv, "reader", "oracle")) {
            log.record(1793296800123L, "read", 4711, 842, true);
            log.record(1793296800124L, "read", 12, 1500, false);
        }
        List<String> lines = Files.readAllLines(csv);
        assertEquals(List.of(
                CsvLog.HEADER,
                "1793296800123,reader,oracle,read,4711,842,ok",
                "1793296800124,reader,oracle,read,12,1500,error"), lines);
        assertEquals("ts_epoch_ms,program,mode,operation,account_id,latency_us,status", CsvLog.HEADER);
    }

    @Test
    void keepsAllRecordsUnderVolume(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("ingestor-oracle-r2.csv");
        try (CsvLog log = new CsvLog(csv, "ingestor", "oracle")) {
            for (int i = 0; i < 250_000; i++) {
                log.record(i, "ingest_oracle", i, 1, true);
            }
        }
        assertEquals(250_001, Files.readAllLines(csv).size());
    }
}
```

`src/test/java/sentinela/workload/LoadRunnerTest.java`:

```java
package sentinela.workload;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sentinela.infra.CsvLog;
import sentinela.infra.ErrorReporter;

class LoadRunnerTest {
    private static ErrorReporter quietErrors() {
        return new ErrorReporter(new PrintStream(OutputStream.nullOutputStream()));
    }

    @Test
    void dispatchesAtTargetRateForDuration(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("r.csv");
        LoadRunner.Result result;
        try (CsvLog log = new CsvLog(csv, "reader", "oracle"); ErrorReporter errors = quietErrors()) {
            LoadRunner<Long> runner = new LoadRunner<>(LongStream.iterate(1, i -> i + 1).boxed().iterator(),
                    List.of(new LoadRunner.Step<Long>("read", acc -> {}, acc -> acc)),
                    200, Duration.ofSeconds(1), 2, log, errors);
            result = runner.run();
        }
        assertEquals(200, result.dispatched());
        assertFalse(result.sourceExhausted());
        List<String> lines = Files.readAllLines(csv);
        assertEquals(201, lines.size());
        long first = Long.parseLong(lines.get(1).split(",")[0]);
        long last = Long.parseLong(lines.get(200).split(",")[0]);
        assertTrue(last - first >= 900, "operações deveriam se espalhar pelo segundo: " + (last - first) + " ms");
    }

    @Test
    void recordsEachStepAndErrors(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("i.csv");
        try (CsvLog log = new CsvLog(csv, "ingestor", "oracle+valkey"); ErrorReporter errors = quietErrors()) {
            LoadRunner<Long> runner = new LoadRunner<>(LongStream.rangeClosed(1, 20).boxed().iterator(),
                    List.of(new LoadRunner.Step<Long>("ingest_oracle", i -> {}, i -> i),
                            new LoadRunner.Step<Long>("ingest_valkey", i -> {
                                if (i % 10 == 0) {
                                    throw new IllegalStateException("valkey fora");
                                }
                            }, i -> i)),
                    1000, Duration.ofSeconds(5), 4, log, errors);
            LoadRunner.Result r = runner.run();
            assertTrue(r.sourceExhausted());
            assertEquals(20, r.dispatched());
            assertEquals(2, errors.total());
        }
        List<String> lines = Files.readAllLines(csv).subList(1, 41);
        assertEquals(20, lines.stream().filter(l -> l.contains(",ingest_oracle,")).count());
        assertEquals(2, lines.stream().filter(l -> l.contains(",ingest_valkey,") && l.endsWith(",error")).count());
    }
}
```

- [ ] **Step 3: Rodar e confirmar a falha**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest='EnvTest,RunMetaTest,ErrorReporterTest,CsvLogTest,LoadRunnerTest'`
Expected: falha de compilação (`cannot find symbol: class Env`).

- [ ] **Step 4: Implementar `Env`, `RunMeta` e `ErrorReporter`**

`src/main/java/sentinela/infra/Env.java`:

```java
package sentinela.infra;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Conexões vêm do ambiente para não aparecerem em logs nem em argumentos. */
public record Env(String oracleUrl, String oracleUser, String oraclePassword, String valkeyUrl, ZoneId zone) {
    public static final String DEFAULT_ZONE = "America/Sao_Paulo";

    public static Env fromSystem() {
        return from(System.getenv());
    }

    public static Env from(Map<String, String> vars) {
        String z = vars.getOrDefault("FS_ZONE", DEFAULT_ZONE);
        ZoneId zone;
        try {
            zone = ZoneId.of(z);
        } catch (DateTimeException e) {
            throw new UsageException("FS_ZONE inválido: " + z);
        }
        return new Env(clean(vars.get("ORACLE_URL")), clean(vars.get("ORACLE_USER")),
                clean(vars.get("ORACLE_PASSWORD")), clean(vars.get("VALKEY_URL")), zone);
    }

    public void requireOracle() {
        List<String> missing = new ArrayList<>();
        if (oracleUrl == null) {
            missing.add("ORACLE_URL");
        }
        if (oracleUser == null) {
            missing.add("ORACLE_USER");
        }
        if (oraclePassword == null) {
            missing.add("ORACLE_PASSWORD");
        }
        if (!missing.isEmpty()) {
            throw new UsageException("variáveis de ambiente ausentes: " + String.join(", ", missing));
        }
    }

    public void requireValkey() {
        if (valkeyUrl == null) {
            throw new UsageException("variável de ambiente ausente: VALKEY_URL (obrigatória no modo oracle+valkey)");
        }
    }

    @Override
    public String toString() {
        return "Env[oracleUrl=" + oracleUrl + ", oracleUser=" + oracleUser + ", oraclePassword=***, valkeyUrl="
                + valkeyUrl + ", zone=" + zone + "]";
    }

    private static String clean(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
```

`src/main/java/sentinela/infra/RunMeta.java`:

```java
package sentinela.infra;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/** Arquivo .meta.properties ao lado de cada log: o Relatório recebe só o diretório. */
public final class RunMeta {
    private RunMeta() {}

    public static Path sidecarOf(Path csv) {
        String name = csv.getFileName().toString();
        String base = name.endsWith(".csv") ? name.substring(0, name.length() - 4) : name;
        return csv.resolveSibling(base + ".meta.properties");
    }

    public static void write(Path csv, Map<String, String> values) throws IOException {
        Properties p = new Properties();
        p.putAll(values);
        store(csv, p);
    }

    public static void put(Path csv, String key, String value) throws IOException {
        Properties p = read(csv);
        p.setProperty(key, value);
        store(csv, p);
    }

    public static Properties read(Path csv) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(sidecarOf(csv), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    private static void store(Path csv, Properties p) throws IOException {
        try (Writer w = Files.newBufferedWriter(sidecarOf(csv), StandardCharsets.UTF_8)) {
            p.store(w, "sentinela");
        }
    }
}
```

`src/main/java/sentinela/infra/ErrorReporter.java`:

```java
package sentinela.infra;

import java.io.PrintStream;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Os 10 primeiros erros com stack trace; depois, resumo por tipo a cada 10 s e no fechamento. */
public final class ErrorReporter implements AutoCloseable {
    private static final int FULL_TRACES = 10;

    private final PrintStream err;
    private final AtomicInteger total = new AtomicInteger();
    private final Map<String, LongAdder> byType = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer;

    public ErrorReporter(PrintStream err) {
        this.err = err;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "erros");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(this::summary, 10, 10, TimeUnit.SECONDS);
    }

    public void report(Throwable t) {
        int n = total.incrementAndGet();
        byType.computeIfAbsent(key(t), k -> new LongAdder()).increment();
        if (n <= FULL_TRACES) {
            synchronized (err) {
                err.println("erro #" + n + ":");
                t.printStackTrace(err);
            }
        }
    }

    public int total() {
        return total.get();
    }

    @Override
    public void close() {
        timer.shutdownNow();
        if (total.get() > 0) {
            err.println("total de erros: " + total.get() + " " + snapshot());
        }
    }

    private void summary() {
        if (total.get() > FULL_TRACES) {
            err.println("erros até agora: " + total.get() + " " + snapshot());
        }
    }

    private Map<String, Long> snapshot() {
        Map<String, Long> m = new TreeMap<>();
        byType.forEach((k, v) -> m.put(k, v.sum()));
        return m;
    }

    private static String key(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String k = t.getClass().getSimpleName();
        return root == t ? k : k + " <- " + root.getClass().getSimpleName();
    }
}
```

- [ ] **Step 5: Implementar `CsvLog`**

`src/main/java/sentinela/infra/CsvLog.java`:

```java
package sentinela.infra;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** Log de latência: os workers enfileiram, um thread dedicado escreve. Nenhum I/O no caminho quente. */
public final class CsvLog implements AutoCloseable {
    public static final String HEADER = "ts_epoch_ms,program,mode,operation,account_id,latency_us,status";

    private record Entry(long tsEpochMs, String operation, long accountId, long latencyUs, boolean ok) {}

    private static final Entry POISON = new Entry(0, null, 0, 0, false);

    private final BlockingQueue<Entry> queue = new ArrayBlockingQueue<>(100_000);
    private final String program;
    private final String mode;
    private final Thread writer;
    private volatile IOException failure;

    public CsvLog(Path path, String program, String mode) throws IOException {
        this.program = program;
        this.mode = mode;
        Files.createDirectories(path.toAbsolutePath().getParent());
        BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        out.write(HEADER);
        out.write('\n');
        this.writer = Thread.ofPlatform().name("csv-log").start(() -> drain(out));
    }

    public void record(long tsEpochMs, String operation, long accountId, long latencyUs, boolean ok) {
        try {
            queue.put(new Entry(tsEpochMs, operation, accountId, latencyUs, ok));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompido ao registrar no log", e);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            queue.put(POISON);
            writer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrompido ao fechar o log", e);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void drain(BufferedWriter out) {
        List<Entry> batch = new ArrayList<>(4096);
        long lastFlush = System.nanoTime();
        try {
            while (true) {
                Entry first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, 4095);
                }
                for (Entry e : batch) {
                    if (e == POISON) {
                        finish(out);
                        return;
                    }
                    write(out, e);
                }
                batch.clear();
                if (System.nanoTime() - lastFlush >= 1_000_000_000L) {
                    flush(out);
                    lastFlush = System.nanoTime();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(out);
        }
    }

    private void write(BufferedWriter out, Entry e) {
        if (failure != null) {
            return; // continua esvaziando a fila para não travar os workers
        }
        try {
            out.write(Long.toString(e.tsEpochMs()));
            out.write(',');
            out.write(program);
            out.write(',');
            out.write(mode);
            out.write(',');
            out.write(e.operation());
            out.write(',');
            out.write(Long.toString(e.accountId()));
            out.write(',');
            out.write(Long.toString(e.latencyUs()));
            out.write(',');
            out.write(e.ok() ? "ok" : "error");
            out.write('\n');
        } catch (IOException ex) {
            failure = ex;
        }
    }

    private void flush(BufferedWriter out) {
        if (failure != null) {
            return;
        }
        try {
            out.flush();
        } catch (IOException ex) {
            failure = ex;
        }
    }

    private void finish(BufferedWriter out) {
        try {
            out.close();
        } catch (IOException ex) {
            if (failure == null) {
                failure = ex;
            }
        }
    }
}
```

- [ ] **Step 6: Implementar `LoadRunner`**

`src/main/java/sentinela/workload/LoadRunner.java`:

```java
package sentinela.workload;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;
import sentinela.infra.CsvLog;
import sentinela.infra.ErrorReporter;

/**
 * Carga em laço aberto: um despachante emite um item a cada 1/tps s; workers executam os passos
 * em sequência e registram uma linha de log por passo. A latência cobre só a chamada do passo.
 */
public final class LoadRunner<T> {
    public record Step<T>(String operation, Consumer<T> call, ToLongFunction<T> accountOf) {}

    public record Result(long dispatched, boolean sourceExhausted) {}

    private static final Object POISON = new Object();

    private final Iterator<T> source;
    private final List<Step<T>> steps;
    private final int tps;
    private final Duration duration;
    private final int threads;
    private final CsvLog log;
    private final ErrorReporter errors;

    public LoadRunner(Iterator<T> source, List<Step<T>> steps, int tps, Duration duration, int threads,
            CsvLog log, ErrorReporter errors) {
        this.source = source;
        this.steps = List.copyOf(steps);
        this.tps = tps;
        this.duration = duration;
        this.threads = threads;
        this.log = log;
        this.errors = errors;
    }

    public Result run() throws InterruptedException {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(Math.max(1, tps));
        List<Thread> workers = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            workers.add(Thread.ofPlatform().name("worker-" + i).start(() -> work(queue)));
        }
        long start = System.nanoTime();
        long end = start + duration.toNanos();
        long k = 0;
        boolean exhausted = false;
        while (true) {
            long due = start + (k * 1_000_000_000L) / tps;
            if (due >= end) {
                break;
            }
            if (!source.hasNext()) {
                exhausted = true;
                break;
            }
            long wait = due - System.nanoTime();
            while (wait > 0) {
                LockSupport.parkNanos(wait);
                wait = due - System.nanoTime();
            }
            queue.put(source.next());
            k++;
        }
        for (int i = 0; i < threads; i++) {
            queue.put(POISON);
        }
        for (Thread w : workers) {
            w.join();
        }
        return new Result(k, exhausted);
    }

    @SuppressWarnings("unchecked")
    private void work(BlockingQueue<Object> queue) {
        try {
            while (true) {
                Object o = queue.take();
                if (o == POISON) {
                    return;
                }
                T item = (T) o;
                for (Step<T> step : steps) {
                    long tsMs = System.currentTimeMillis();
                    long t0 = System.nanoTime();
                    boolean ok = true;
                    try {
                        step.call().accept(item);
                    } catch (RuntimeException e) {
                        ok = false;
                        errors.report(e);
                    }
                    long latencyUs = (System.nanoTime() - t0) / 1_000;
                    log.record(tsMs, step.operation(), step.accountOf().applyAsLong(item), latencyUs, ok);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 7: Rodar e confirmar que passam**

Run: `pwsh scripts/mvn.ps1 -q test`
Expected: `BUILD SUCCESS`, todos os testes. Se `dispatchesAtTargetRateForDuration` falhar por `last - first`, rode de novo com a máquina sem outra carga: o teste mede tempo real.

- [ ] **Step 8: Commit**

```powershell
git add src
git commit -m "feat(infra): log CSV assíncrono, sidecar, ambiente, erros e gerador de carga open-loop"
```

---

### Task 7: Dataset NDJSON e programa `gerador`

**Files:**
- Create: `src/main/java/sentinela/workload/Dataset.java`
- Create: `src/main/java/sentinela/app/GeneratorApp.java`
- Modify: `src/main/java/sentinela/app/Main.java` (novo `case`)
- Test: `src/test/java/sentinela/workload/DatasetTest.java`
- Test: `src/test/java/sentinela/app/GeneratorAppTest.java`

**Interfaces:**
- Consumes: `Args`, `UsageException`, `ExitCodes` (Task 1); `Seeds`, `AccountSampler`, `AmountSampler` (Task 5).
- Produces:
  - `record Dataset.Row(long txId, long accountId, long amountCents)`.
  - `Dataset.write(Path, Iterator<Row>)`, `Dataset.readAll(Path) → List<Row>`. Linhas em branco são ignoradas; linha malformada → `UsageException("dataset linha N: ...")`.
  - `GeneratorApp.run(String[] argv) → int`. Args: `--accounts` (100000), `--seed` (42), `--distribution` (skewed), `--dataset` (`./data/tx.ndjson`), `--count` (200000).

- [ ] **Step 1: Escrever os testes**

`src/test/java/sentinela/workload/DatasetTest.java`:

```java
package sentinela.workload;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sentinela.infra.UsageException;

class DatasetTest {
    @Test
    void roundTripsRows(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("a/tx.ndjson");
        List<Dataset.Row> rows = List.of(new Dataset.Row(1, 4711, 12345), new Dataset.Row(2, 7, 100));
        Dataset.write(p, rows.iterator());
        assertEquals("{\"tx_id\":1,\"account_id\":4711,\"amount\":123.45}", Files.readAllLines(p).get(0));
        assertEquals(rows, Dataset.readAll(p));
    }

    @Test
    void ignoresBlankLinesAndFieldOrder(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("tx.ndjson");
        Files.writeString(p, "\n{\"amount\":1.5,\"account_id\":3,\"tx_id\":9}\n   \n");
        assertEquals(List.of(new Dataset.Row(9, 3, 150)), Dataset.readAll(p));
    }

    @Test
    void reportsLineNumberOfMalformedLine(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("tx.ndjson");
        Files.writeString(p, "{\"tx_id\":1,\"account_id\":1,\"amount\":1.00}\n{\"tx_id\":2,\"account_id\":\n");
        UsageException e = assertThrows(UsageException.class, () -> Dataset.readAll(p));
        assertTrue(e.getMessage().startsWith("dataset linha 2:"), e.getMessage());
    }

    @Test
    void rejectsMissingFieldAndExcessDecimals(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("tx.ndjson");
        Files.writeString(p, "{\"tx_id\":1,\"amount\":1.00}\n");
        assertThrows(UsageException.class, () -> Dataset.readAll(p));
        Files.writeString(p, "{\"tx_id\":1,\"account_id\":1,\"amount\":1.001}\n");
        assertThrows(UsageException.class, () -> Dataset.readAll(p));
    }
}
```

`src/test/java/sentinela/app/GeneratorAppTest.java`:

```java
package sentinela.app;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sentinela.infra.ExitCodes;
import sentinela.workload.Dataset;

class GeneratorAppTest {
    @Test
    void generatesSequentialIdsWithinUniverse(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("tx.ndjson");
        int code = GeneratorApp.run(new String[] {"--count", "1000", "--accounts", "50", "--dataset", p.toString()});
        assertEquals(ExitCodes.OK, code);
        List<Dataset.Row> rows = Dataset.readAll(p);
        assertEquals(1000, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i + 1, rows.get(i).txId());
            assertTrue(rows.get(i).accountId() >= 1 && rows.get(i).accountId() <= 50);
        }
    }

    @Test
    void sameSeedGivesSameFile(@TempDir Path dir) throws Exception {
        Path a = dir.resolve("a.ndjson");
        Path b = dir.resolve("b.ndjson");
        GeneratorApp.run(new String[] {"--count", "500", "--seed", "7", "--dataset", a.toString()});
        GeneratorApp.run(new String[] {"--count", "500", "--seed", "7", "--dataset", b.toString()});
        assertEquals(Files.readString(a), Files.readString(b));
    }
}
```

- [ ] **Step 2: Rodar e confirmar a falha**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest='DatasetTest,GeneratorAppTest'`
Expected: falha de compilação (`cannot find symbol: class Dataset`).

- [ ] **Step 3: Implementar `Dataset`**

`src/main/java/sentinela/workload/Dataset.java`:

```java
package sentinela.workload;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import sentinela.infra.UsageException;

/** Arquivo NDJSON: {"tx_id":1,"account_id":4711,"amount":123.45} por linha. */
public final class Dataset {
    public record Row(long txId, long accountId, long amountCents) {}

    private static final JsonFactory JSON = new JsonFactory();

    private Dataset() {}

    public static void write(Path path, Iterator<Row> rows) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            while (rows.hasNext()) {
                Row r = rows.next();
                w.write("{\"tx_id\":");
                w.write(Long.toString(r.txId()));
                w.write(",\"account_id\":");
                w.write(Long.toString(r.accountId()));
                w.write(",\"amount\":");
                w.write(BigDecimal.valueOf(r.amountCents(), 2).toPlainString());
                w.write("}\n");
            }
        }
    }

    public static List<Row> readAll(Path path) throws IOException {
        List<Row> out = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (!line.isBlank()) {
                    out.add(parse(line, lineNo));
                }
            }
        }
        return out;
    }

    private static Row parse(String line, int lineNo) {
        try (JsonParser p = JSON.createParser(line)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw bad(lineNo, "esperado um objeto JSON");
            }
            Long txId = null;
            Long accountId = null;
            Long amountCents = null;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "tx_id" -> txId = p.getLongValue();
                    case "account_id" -> accountId = p.getLongValue();
                    case "amount" -> amountCents = p.getDecimalValue().movePointRight(2).longValueExact();
                    default -> p.skipChildren();
                }
            }
            if (txId == null || accountId == null || amountCents == null) {
                throw bad(lineNo, "faltam campos (tx_id, account_id, amount)");
            }
            return new Row(txId, accountId, amountCents);
        } catch (IOException | ArithmeticException e) {
            throw bad(lineNo, e.getMessage());
        }
    }

    private static UsageException bad(int lineNo, String msg) {
        return new UsageException("dataset linha " + lineNo + ": " + msg);
    }
}
```

- [ ] **Step 4: Implementar `GeneratorApp` e registrar no `Main`**

`src/main/java/sentinela/app/GeneratorApp.java`:

```java
package sentinela.app;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import sentinela.infra.Args;
import sentinela.infra.ExitCodes;
import sentinela.workload.AccountSampler;
import sentinela.workload.AccountSampler.Distribution;
import sentinela.workload.AmountSampler;
import sentinela.workload.Dataset;
import sentinela.workload.Seeds;

/** Produz o dataset de transações, determinístico por --seed. */
public final class GeneratorApp {
    static final Map<String, String> DEFAULTS = Args.defaults(
            "accounts", "100000",
            "seed", "42",
            "distribution", "skewed",
            "dataset", "./data/tx.ndjson",
            "count", "200000");

    private GeneratorApp() {}

    public static int run(String[] argv) throws Exception {
        Args a = Args.parse(argv, DEFAULTS, Set.of());
        int accounts = a.positiveInt("accounts");
        long seed = a.longValue("seed");
        Distribution distribution = Distribution.parse(a.string("distribution"));
        Path dataset = a.path("dataset");
        int count = a.positiveInt("count");

        AccountSampler accountsSampler = new AccountSampler(accounts, distribution, Seeds.stream(seed, "gerador.contas"));
        AmountSampler amounts = new AmountSampler(Seeds.stream(seed, "gerador.valores"));
        Iterator<Dataset.Row> rows = new Iterator<>() {
            private long next = 1;

            @Override
            public boolean hasNext() {
                return next <= count;
            }

            @Override
            public Dataset.Row next() {
                return new Dataset.Row(next++, accountsSampler.next(), amounts.nextCents());
            }
        };
        Dataset.write(dataset, rows);
        System.err.printf("gerador: %d transações, %d contas (%s), seed %d -> %s%n",
                count, accounts, a.string("distribution"), seed, dataset);
        return ExitCodes.OK;
    }
}
```

Em `src/main/java/sentinela/app/Main.java`, dentro do `switch`, antes de `default`:

```java
                case "gerador" -> GeneratorApp.run(rest);
```

- [ ] **Step 5: Rodar e confirmar que passam**

Run: `pwsh scripts/mvn.ps1 -q test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```powershell
git add src
git commit -m "feat(gerador): dataset NDJSON determinístico por seed"
```

---

### Task 8: Relatório — leitura dos logs e estatística

**Files:**
- Create: `src/main/java/sentinela/report/LogRow.java`
- Create: `src/main/java/sentinela/report/LogFile.java`
- Create: `src/main/java/sentinela/report/LogLoader.java`
- Create: `src/main/java/sentinela/report/Stats.java`
- Create: `src/main/java/sentinela/report/GroupStats.java`
- Create: `src/main/java/sentinela/report/SeriesRow.java`
- Create: `src/main/java/sentinela/report/ReportBuilder.java`
- Test: `src/test/java/sentinela/report/StatsTest.java`
- Test: `src/test/java/sentinela/report/ReportBuilderTest.java`
- Test helper: `src/test/java/sentinela/report/LogFixtures.java`

**Interfaces:**
- Consumes: `CsvLog.HEADER`, `RunMeta` (Task 6).
- Produces:
  - `record LogRow(long tsMs, String program, String mode, String operation, long accountId, long latencyUs, boolean ok)`.
  - `record LogFile(Path csv, Properties meta, List<LogRow> rows)` com `run()`, `startMs()`, `warmupS()`, `targetTps()`.
  - `LogLoader.load(Path dir, PrintStream warn) → List<LogFile>`.
  - `Stats.nearestRank(long[] sorted, int permille) → long` (p95 = 950, p99 = 990).
  - `record GroupStats(String run, long startMs, String program, String mode, String operation, long ops, long errors, double errorRate, double durationS, int targetTps, double achievedTps, double deviation, long minUs, double avgUs, long p95Us, long p99Us, long maxUs, boolean tpsOk, boolean errorOk)`.
  - `record SeriesRow(String run, String program, String mode, String operation, long second, long ops, boolean warmup)`.
  - `ReportBuilder.build(List<LogFile>, PrintStream warn) → List<GroupStats>` e `ReportBuilder.series(List<LogFile>) → List<SeriesRow>`.

- [ ] **Step 1: Escrever os testes**

`src/test/java/sentinela/report/LogFixtures.java`:

```java
package sentinela.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import sentinela.infra.CsvLog;
import sentinela.infra.RunMeta;

/** Escreve logs sintéticos: 60 leituras a cada 100 ms; latência (i+1)×100 µs; a última com erro. */
final class LogFixtures {
    static final long START = 1_000_000;

    private LogFixtures() {}

    static Path readerLog(Path dir, String mode, String run, long start) throws IOException {
        Path csv = dir.resolve("reader-" + mode + "-" + run + ".csv");
        List<String> lines = new ArrayList<>();
        lines.add(CsvLog.HEADER);
        for (int i = 0; i < 60; i++) {
            String status = i == 59 ? "error" : "ok";
            lines.add((start + i * 100L) + ",reader," + mode + ",read," + (i + 1) + "," + ((i + 1) * 100) + "," + status);
        }
        Files.write(csv, lines);
        RunMeta.write(csv, Map.of("run", run, "start_epoch_ms", Long.toString(start), "warmup", "2", "tps", "10",
                "program", "reader", "mode", mode));
        return csv;
    }
}
```

`src/test/java/sentinela/report/StatsTest.java`:

```java
package sentinela.report;

import static org.junit.jupiter.api.Assertions.*;

import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class StatsTest {
    @Test
    void nearestRankWithoutInterpolation() {
        long[] hundred = LongStream.rangeClosed(1, 100).toArray();
        assertEquals(95, Stats.nearestRank(hundred, 950));
        assertEquals(99, Stats.nearestRank(hundred, 990));
        long[] ten = LongStream.rangeClosed(1, 10).toArray();
        assertEquals(10, Stats.nearestRank(ten, 950), "ceil(9,5) = 10");
        assertEquals(10, Stats.nearestRank(ten, 990));
        assertEquals(5, Stats.nearestRank(ten, 500));
        assertEquals(42, Stats.nearestRank(new long[] {42}, 990));
    }

    @Test
    void emptySampleIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Stats.nearestRank(new long[0], 950));
    }
}
```

`src/test/java/sentinela/report/ReportBuilderTest.java`:

```java
package sentinela.report;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sentinela.infra.CsvLog;
import sentinela.infra.RunMeta;

class ReportBuilderTest {
    private final ByteArrayOutputStream warnings = new ByteArrayOutputStream();
    private final PrintStream warn = new PrintStream(warnings, true, StandardCharsets.UTF_8);

    @Test
    void computesStatsAfterWarmup(@TempDir Path dir) throws Exception {
        LogFixtures.readerLog(dir, "oracle", "r1", LogFixtures.START);
        List<GroupStats> stats = ReportBuilder.build(LogLoader.load(dir, warn), warn);
        assertEquals(1, stats.size());
        GroupStats g = stats.get(0);
        assertEquals("r1", g.run());
        assertEquals("reader", g.program());
        assertEquals("oracle", g.mode());
        assertEquals("read", g.operation());
        assertEquals(39, g.ops(), "linhas 20..58 ok depois de 2 s de warmup");
        assertEquals(1, g.errors());
        assertEquals(0.025, g.errorRate(), 1e-9);
        assertEquals(3.9, g.durationS(), 1e-9);
        assertEquals(10.0, g.achievedTps(), 1e-9);
        assertEquals(0.0, g.deviation(), 1e-9);
        assertEquals(2100, g.minUs());
        assertEquals(5900, g.maxUs());
        assertEquals(4000.0, g.avgUs(), 1e-9);
        assertEquals(5800, g.p95Us(), "38º de 39 valores");
        assertEquals(5900, g.p99Us());
        assertTrue(g.tpsOk());
        assertFalse(g.errorOk());
    }

    @Test
    void skipsCsvWithoutSidecarWithWarning(@TempDir Path dir) throws Exception {
        LogFixtures.readerLog(dir, "oracle", "r1", LogFixtures.START);
        Files.writeString(dir.resolve("orfao.csv"), CsvLog.HEADER + "\n");
        List<LogFile> files = LogLoader.load(dir, warn);
        assertEquals(1, files.size());
        assertTrue(warnings.toString(StandardCharsets.UTF_8).contains("orfao.csv"));
    }

    @Test
    void skipsGroupEmptyAfterWarmupWithWarning(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("reader-oracle-r9.csv");
        Files.writeString(csv, CsvLog.HEADER + "\n" + LogFixtures.START + ",reader,oracle,read,1,100,ok\n");
        RunMeta.write(csv, Map.of("run", "r9", "start_epoch_ms", Long.toString(LogFixtures.START), "warmup", "30",
                "tps", "10"));
        List<GroupStats> stats = ReportBuilder.build(LogLoader.load(dir, warn), warn);
        assertTrue(stats.isEmpty());
        assertTrue(warnings.toString(StandardCharsets.UTF_8).contains("r9"));
    }

    @Test
    void skipsMalformedLinesWithWarning(@TempDir Path dir) throws Exception {
        Path csv = LogFixtures.readerLog(dir, "oracle", "r1", LogFixtures.START);
        Files.writeString(csv, "lixo,sem,colunas\n", java.nio.file.StandardOpenOption.APPEND);
        List<LogFile> files = LogLoader.load(dir, warn);
        assertEquals(60, files.get(0).rows().size());
        assertTrue(warnings.toString(StandardCharsets.UTF_8).contains("malformada"));
    }

    @Test
    void seriesCountsOkOperationsPerSecondFillingGaps(@TempDir Path dir) throws Exception {
        LogFixtures.readerLog(dir, "oracle", "r1", LogFixtures.START);
        List<SeriesRow> series = ReportBuilder.series(LogLoader.load(dir, warn));
        assertEquals(6, series.size(), "segundos 0..5");
        assertEquals(10, series.get(0).ops());
        assertTrue(series.get(0).warmup());
        assertTrue(series.get(1).warmup());
        assertFalse(series.get(2).warmup());
        assertEquals(9, series.get(5).ops(), "a última linha é erro");
    }
}
```

- [ ] **Step 2: Rodar e confirmar a falha**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest='StatsTest,ReportBuilderTest'`
Expected: falha de compilação (`cannot find symbol: class Stats`).

- [ ] **Step 3: Implementar os tipos e o carregador**

`src/main/java/sentinela/report/LogRow.java`:

```java
package sentinela.report;

public record LogRow(long tsMs, String program, String mode, String operation, long accountId, long latencyUs,
        boolean ok) {}
```

`src/main/java/sentinela/report/LogFile.java`:

```java
package sentinela.report;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

public record LogFile(Path csv, Properties meta, List<LogRow> rows) {
    public String run() {
        return meta.getProperty("run", csv.getFileName().toString());
    }

    public long startMs() {
        return Long.parseLong(meta.getProperty("start_epoch_ms"));
    }

    public int warmupS() {
        return Integer.parseInt(meta.getProperty("warmup", "0"));
    }

    public int targetTps() {
        return Integer.parseInt(meta.getProperty("tps"));
    }
}
```

`src/main/java/sentinela/report/LogLoader.java`:

```java
package sentinela.report;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import sentinela.infra.CsvLog;
import sentinela.infra.RunMeta;

/** Lê os *.csv do diretório (sem recursão) que tenham .meta.properties ao lado. */
public final class LogLoader {
    private LogLoader() {}

    public static List<LogFile> load(Path dir, PrintStream warn) throws IOException {
        List<Path> csvs;
        try (Stream<Path> s = Files.list(dir)) {
            csvs = s.filter(p -> p.getFileName().toString().endsWith(".csv")).sorted().toList();
        }
        List<LogFile> out = new ArrayList<>();
        for (Path csv : csvs) {
            if (!Files.exists(RunMeta.sidecarOf(csv))) {
                warn.println("aviso: " + csv.getFileName() + " sem .meta.properties; ignorado");
                continue;
            }
            out.add(new LogFile(csv, RunMeta.read(csv), readRows(csv, warn)));
        }
        return out;
    }

    private static List<LogRow> readRows(Path csv, PrintStream warn) throws IOException {
        List<LogRow> rows = new ArrayList<>();
        int bad = 0;
        try (BufferedReader r = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.equals(CsvLog.HEADER)) {
                    continue;
                }
                String[] f = line.split(",", -1);
                try {
                    if (f.length != 7) {
                        throw new NumberFormatException("colunas: " + f.length);
                    }
                    rows.add(new LogRow(Long.parseLong(f[0]), f[1], f[2], f[3], Long.parseLong(f[4]),
                            Long.parseLong(f[5]), f[6].equals("ok")));
                } catch (NumberFormatException e) {
                    bad++;
                }
            }
        }
        if (bad > 0) {
            warn.println("aviso: " + csv.getFileName() + ": " + bad + " linha(s) malformada(s) ignorada(s)");
        }
        return rows;
    }
}
```

- [ ] **Step 4: Implementar estatística e agregação**

`src/main/java/sentinela/report/Stats.java`:

```java
package sentinela.report;

public final class Stats {
    private Stats() {}

    /** Percentil nearest-rank, sem interpolação: posição ceil(q × n), base 1. permille: 950 = p95. */
    public static long nearestRank(long[] sorted, int permille) {
        int n = sorted.length;
        if (n == 0) {
            throw new IllegalArgumentException("amostra vazia");
        }
        long rank = ((long) permille * n + 999) / 1000;
        return sorted[(int) Math.max(1, rank) - 1];
    }
}
```

`src/main/java/sentinela/report/GroupStats.java`:

```java
package sentinela.report;

public record GroupStats(String run, long startMs, String program, String mode, String operation,
        long ops, long errors, double errorRate, double durationS, int targetTps, double achievedTps,
        double deviation, long minUs, double avgUs, long p95Us, long p99Us, long maxUs,
        boolean tpsOk, boolean errorOk) {}
```

`src/main/java/sentinela/report/SeriesRow.java`:

```java
package sentinela.report;

public record SeriesRow(String run, String program, String mode, String operation, long second, long ops,
        boolean warmup) {}
```

`src/main/java/sentinela/report/ReportBuilder.java`:

```java
package sentinela.report;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Agrupa por run, program, mode e operation; descarta o warmup; calcula TPS e latências. */
public final class ReportBuilder {
    static final double MAX_TPS_DEVIATION = 0.05;
    static final double MAX_ERROR_RATE = 0.001;

    private ReportBuilder() {}

    public static List<GroupStats> build(List<LogFile> files, PrintStream warn) {
        List<GroupStats> out = new ArrayList<>();
        for (LogFile f : files) {
            long cutoff = f.startMs() + f.warmupS() * 1000L;
            Map<String, List<LogRow>> byOp = f.rows().stream()
                    .filter(r -> r.tsMs() >= cutoff)
                    .collect(Collectors.groupingBy(LogRow::operation, TreeMap::new, Collectors.toList()));
            if (byOp.isEmpty()) {
                warn.println("aviso: run " + f.run() + " (" + f.csv().getFileName() + ") sem operações após o warmup");
                continue;
            }
            byOp.forEach((op, rows) -> {
                GroupStats g = stats(f, op, rows);
                if (g == null) {
                    warn.println("aviso: run " + f.run() + " operação " + op + " sem operações ok após o warmup");
                } else {
                    out.add(g);
                }
            });
        }
        out.sort(Comparator.comparing(GroupStats::run).thenComparing(GroupStats::program)
                .thenComparing(GroupStats::mode).thenComparing(GroupStats::operation));
        return out;
    }

    public static List<SeriesRow> series(List<LogFile> files) {
        List<SeriesRow> out = new ArrayList<>();
        for (LogFile f : files) {
            Map<String, List<LogRow>> byOp = f.rows().stream()
                    .collect(Collectors.groupingBy(LogRow::operation, TreeMap::new, Collectors.toList()));
            byOp.forEach((op, rows) -> {
                long maxSecond = rows.stream().mapToLong(r -> Math.floorDiv(r.tsMs() - f.startMs(), 1000)).max().orElse(-1);
                long[] counts = new long[(int) (maxSecond + 1)];
                for (LogRow r : rows) {
                    long s = Math.floorDiv(r.tsMs() - f.startMs(), 1000);
                    if (r.ok() && s >= 0) {
                        counts[(int) s]++;
                    }
                }
                LogRow sample = rows.get(0);
                for (int s = 0; s < counts.length; s++) {
                    out.add(new SeriesRow(f.run(), sample.program(), sample.mode(), op, s, counts[s], s < f.warmupS()));
                }
            });
        }
        return out;
    }

    private static GroupStats stats(LogFile f, String op, List<LogRow> rows) {
        long[] lat = rows.stream().filter(LogRow::ok).mapToLong(LogRow::latencyUs).sorted().toArray();
        if (lat.length == 0) {
            return null;
        }
        long errors = rows.size() - lat.length;
        long first = rows.stream().mapToLong(LogRow::tsMs).min().orElseThrow();
        long last = rows.stream().mapToLong(LogRow::tsMs).max().orElseThrow();
        double durationS = Math.max(1, last - first) / 1000.0;
        double achieved = lat.length / durationS;
        int target = f.targetTps();
        double deviation = Math.abs(achieved - target) / target;
        double errorRate = errors / (double) rows.size();
        double avg = 0;
        for (long v : lat) {
            avg += v;
        }
        avg /= lat.length;
        LogRow sample = rows.get(0);
        return new GroupStats(f.run(), f.startMs(), sample.program(), sample.mode(), op,
                lat.length, errors, errorRate, durationS, target, achieved, deviation,
                lat[0], avg, Stats.nearestRank(lat, 950), Stats.nearestRank(lat, 990), lat[lat.length - 1],
                deviation < MAX_TPS_DEVIATION, errorRate < MAX_ERROR_RATE);
    }
}
```

- [ ] **Step 5: Rodar e confirmar que passam**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest='StatsTest,ReportBuilderTest'`
Expected: `BUILD SUCCESS`, 7 testes.

- [ ] **Step 6: Commit**

```powershell
git add src
git commit -m "feat(relatorio): leitura dos logs, warmup, TPS e percentis nearest-rank"
```

---

### Task 9: Relatório — saídas e programa `relatorio`

**Files:**
- Create: `src/main/java/sentinela/report/ReportWriter.java`
- Create: `src/main/java/sentinela/app/ReportApp.java`
- Modify: `src/main/java/sentinela/app/Main.java` (novo `case`)
- Test: `src/test/java/sentinela/report/ReportWriterTest.java`
- Test: `src/test/java/sentinela/app/ReportAppTest.java`

**Interfaces:**
- Consumes: `GroupStats`, `SeriesRow`, `LogLoader`, `ReportBuilder` (Task 8); `LogFixtures` (teste da Task 8).
- Produces:
  - `ReportWriter.markdown(List<GroupStats>) → String`, `ReportWriter.csv(List<GroupStats>) → String`, `ReportWriter.seriesCsv(List<SeriesRow>) → String`, `ReportWriter.ms(double us) → String` (ms com uma casa, HALF_UP).
  - `ReportApp.run(String[] argv) → int`: um único argumento, o diretório; grava `report.md`, `report.csv` e `tps-series.csv` em `<dir>/report/`.

- [ ] **Step 1: Escrever os testes**

`src/test/java/sentinela/report/ReportWriterTest.java`:

```java
package sentinela.report;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReportWriterTest {
    private static GroupStats g(String run, long start, String program, String mode, String op, double avgUs) {
        return new GroupStats(run, start, program, mode, op, 1000, 0, 0.0, 100.0, 10, 10.0, 0.0,
                500, avgUs, 1500, 2500, 9000, true, true);
    }

    @Test
    void formatsMillisecondsWithOneDecimalHalfUp() {
        assertEquals("0.8", ReportWriter.ms(842));
        assertEquals("1.1", ReportWriter.ms(1050));
        assertEquals("4.0", ReportWriter.ms(4000));
    }

    @Test
    void markdownHasOneTablePerRunAndComparisonWhenBothModes() {
        String md = ReportWriter.markdown(List.of(
                g("r1", 1, "reader", "oracle", "read", 4000),
                g("r2", 2, "reader", "oracle+valkey", "read", 800),
                g("r2", 2, "ingestor", "oracle+valkey", "ingest_valkey", 300)));
        assertTrue(md.contains("## Execução r1"));
        assertTrue(md.contains("## Execução r2"));
        assertTrue(md.contains("## Comparação dos modos"));
        assertTrue(md.contains("| read | avg (ms) | 4.0 | 0.8 |"), md);
        assertTrue(md.contains("| ingest_valkey | avg (ms) | — | 0.3 |"), md);
    }

    @Test
    void markdownWithoutComparisonWhenSingleMode() {
        String md = ReportWriter.markdown(List.of(g("r1", 1, "reader", "oracle", "read", 4000)));
        assertFalse(md.contains("Comparação dos modos"));
    }

    @Test
    void comparisonUsesLatestRunOfEachMode() {
        String md = ReportWriter.markdown(List.of(
                g("velho", 1, "reader", "oracle", "read", 9000),
                g("novo", 5, "reader", "oracle", "read", 4000),
                g("vk", 6, "reader", "oracle+valkey", "read", 800)));
        assertTrue(md.contains("| read | avg (ms) | 4.0 | 0.8 |"), md);
    }

    @Test
    void csvHasHeaderAndOneLinePerGroup() {
        String csv = ReportWriter.csv(List.of(g("r1", 1, "reader", "oracle", "read", 4000)));
        String[] lines = csv.split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("run,program,mode,operation,ops,errors"));
        assertTrue(lines[1].startsWith("r1,reader,oracle,read,1000,0,"));
    }

    @Test
    void seriesCsvListsSeconds() {
        String csv = ReportWriter.seriesCsv(List.of(new SeriesRow("r1", "reader", "oracle", "read", 0, 10, true)));
        assertEquals("run,program,mode,operation,second,ops,warmup\nr1,reader,oracle,read,0,10,true\n", csv);
    }
}
```

`src/test/java/sentinela/app/ReportAppTest.java`:

```java
package sentinela.app;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sentinela.infra.ExitCodes;
import sentinela.infra.UsageException;

class ReportAppTest {
    @Test
    void writesThreeOutputs(@TempDir Path dir) throws Exception {
        sentinela.report.LogFixturesAccess.readerLog(dir, "oracle", "r1");
        assertEquals(ExitCodes.OK, ReportApp.run(new String[] {dir.toString()}));
        assertTrue(Files.readString(dir.resolve("report/report.md")).contains("## Execução r1"));
        assertTrue(Files.exists(dir.resolve("report/report.csv")));
        assertTrue(Files.exists(dir.resolve("report/tps-series.csv")));
    }

    @Test
    void requiresExactlyOneDirectory() {
        assertThrows(UsageException.class, () -> ReportApp.run(new String[0]));
        assertThrows(UsageException.class, () -> ReportApp.run(new String[] {"a", "b"}));
    }
}
```

Como `LogFixtures` é package-private em `sentinela.report`, crie um acesso público só para testes em `src/test/java/sentinela/report/LogFixturesAccess.java`:

```java
package sentinela.report;

import java.io.IOException;
import java.nio.file.Path;

public final class LogFixturesAccess {
    private LogFixturesAccess() {}

    public static Path readerLog(Path dir, String mode, String run) throws IOException {
        return LogFixtures.readerLog(dir, mode, run, LogFixtures.START);
    }
}
```

- [ ] **Step 2: Rodar e confirmar a falha**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest='ReportWriterTest,ReportAppTest'`
Expected: falha de compilação (`cannot find symbol: class ReportWriter`).

- [ ] **Step 3: Implementar `ReportWriter`**

`src/main/java/sentinela/report/ReportWriter.java`:

```java
package sentinela.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Markdown com uma tabela por execução e a comparação dos modos; CSVs para planilha. */
public final class ReportWriter {
    private static final List<String> MODES = List.of("oracle", "oracle+valkey");
    private static final List<String> OPERATIONS = List.of("read", "ingest_oracle", "ingest_valkey");

    private ReportWriter() {}

    public static String ms(double us) {
        return BigDecimal.valueOf(us).movePointLeft(3).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    public static String markdown(List<GroupStats> stats) {
        StringBuilder sb = new StringBuilder("# Relatório de benchmark\n\nLatências em milissegundos. "
                + "Percentis nearest-rank, sem interpolação. Warmup descartado.\n");
        Map<String, List<GroupStats>> byRun = new LinkedHashMap<>();
        stats.stream().sorted(Comparator.comparingLong(GroupStats::startMs).thenComparing(GroupStats::run))
                .forEach(g -> byRun.computeIfAbsent(g.run(), k -> new ArrayList<>()).add(g));
        byRun.forEach((run, groups) -> {
            sb.append("\n## Execução ").append(run).append("\n\n");
            sb.append("| Programa | Modo | Operação | Ops | Erros | Taxa de erro | Duração (s) | TPS alvo | TPS alcançado"
                    + " | Desvio | min | avg | p95 | p99 | max | TPS ok | Erro ok |\n");
            sb.append("| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |\n");
            for (GroupStats g : groups) {
                sb.append("| ").append(g.program()).append(" | ").append(g.mode()).append(" | ").append(g.operation())
                        .append(" | ").append(g.ops()).append(" | ").append(g.errors())
                        .append(" | ").append(pct(g.errorRate())).append(" | ").append(fmt1(g.durationS()))
                        .append(" | ").append(g.targetTps()).append(" | ").append(fmt1(g.achievedTps()))
                        .append(" | ").append(pct(g.deviation()))
                        .append(" | ").append(ms(g.minUs())).append(" | ").append(ms(g.avgUs()))
                        .append(" | ").append(ms(g.p95Us())).append(" | ").append(ms(g.p99Us()))
                        .append(" | ").append(ms(g.maxUs()))
                        .append(" | ").append(g.tpsOk() ? "sim" : "NÃO").append(" | ").append(g.errorOk() ? "sim" : "NÃO")
                        .append(" |\n");
            }
        });
        appendComparison(sb, stats);
        return sb.toString();
    }

    public static String csv(List<GroupStats> stats) {
        StringBuilder sb = new StringBuilder("run,program,mode,operation,ops,errors,error_rate,duration_s,target_tps,"
                + "achieved_tps,deviation,min_ms,avg_ms,p95_ms,p99_ms,max_ms,tps_ok,err_ok\n");
        for (GroupStats g : stats) {
            sb.append(String.join(",", g.run(), g.program(), g.mode(), g.operation(),
                    Long.toString(g.ops()), Long.toString(g.errors()), fmt(g.errorRate(), 6), fmt(g.durationS(), 3),
                    Integer.toString(g.targetTps()), fmt(g.achievedTps(), 2), fmt(g.deviation(), 6),
                    ms(g.minUs()), ms(g.avgUs()), ms(g.p95Us()), ms(g.p99Us()), ms(g.maxUs()),
                    Boolean.toString(g.tpsOk()), Boolean.toString(g.errorOk()))).append('\n');
        }
        return sb.toString();
    }

    public static String seriesCsv(List<SeriesRow> rows) {
        StringBuilder sb = new StringBuilder("run,program,mode,operation,second,ops,warmup\n");
        for (SeriesRow r : rows) {
            sb.append(String.join(",", r.run(), r.program(), r.mode(), r.operation(), Long.toString(r.second()),
                    Long.toString(r.ops()), Boolean.toString(r.warmup()))).append('\n');
        }
        return sb.toString();
    }

    private static void appendComparison(StringBuilder sb, List<GroupStats> stats) {
        Map<String, String> latestRun = new LinkedHashMap<>();
        for (String mode : MODES) {
            stats.stream().filter(g -> g.mode().equals(mode)).max(Comparator.comparingLong(GroupStats::startMs))
                    .ifPresent(g -> latestRun.put(mode, g.run()));
        }
        if (latestRun.size() < MODES.size()) {
            return;
        }
        sb.append("\n## Comparação dos modos\n\n");
        sb.append("Execuções: oracle = ").append(latestRun.get("oracle"))
                .append("; oracle+valkey = ").append(latestRun.get("oracle+valkey")).append(".\n\n");
        sb.append("| Operação | Medida | oracle | oracle+valkey |\n| --- | --- | ---: | ---: |\n");
        Map<String, Function<GroupStats, String>> measures = new LinkedHashMap<>();
        measures.put("TPS alcançado", g -> fmt1(g.achievedTps()));
        measures.put("avg (ms)", g -> ms(g.avgUs()));
        measures.put("p95 (ms)", g -> ms(g.p95Us()));
        measures.put("p99 (ms)", g -> ms(g.p99Us()));
        measures.put("max (ms)", g -> ms(g.maxUs()));
        for (String op : OPERATIONS) {
            Optional<GroupStats> o = find(stats, latestRun.get("oracle"), "oracle", op);
            Optional<GroupStats> v = find(stats, latestRun.get("oracle+valkey"), "oracle+valkey", op);
            if (o.isEmpty() && v.isEmpty()) {
                continue;
            }
            measures.forEach((name, f) -> sb.append("| ").append(op).append(" | ").append(name).append(" | ")
                    .append(o.map(f).orElse("—")).append(" | ").append(v.map(f).orElse("—")).append(" |\n"));
        }
    }

    private static Optional<GroupStats> find(List<GroupStats> stats, String run, String mode, String op) {
        return stats.stream().filter(g -> g.run().equals(run) && g.mode().equals(mode) && g.operation().equals(op))
                .findFirst();
    }

    private static String fmt1(double v) {
        return fmt(v, 1);
    }

    private static String pct(double v) {
        return fmt(v * 100, 2) + "%";
    }

    private static String fmt(double v, int scale) {
        return String.format(Locale.ROOT, "%." + scale + "f", v);
    }
}
```

- [ ] **Step 4: Implementar `ReportApp` e registrar no `Main`**

`src/main/java/sentinela/app/ReportApp.java`:

```java
package sentinela.app;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import sentinela.infra.ExitCodes;
import sentinela.infra.UsageException;
import sentinela.report.GroupStats;
import sentinela.report.LogFile;
import sentinela.report.LogLoader;
import sentinela.report.ReportBuilder;
import sentinela.report.ReportWriter;

/** relatorio <diretório de logs>: grava report.md, report.csv e tps-series.csv em <dir>/report/. */
public final class ReportApp {
    private ReportApp() {}

    public static int run(String[] argv) throws Exception {
        if (argv.length != 1) {
            throw new UsageException("uso: relatorio <diretório de logs>");
        }
        Path dir = Path.of(argv[0]);
        if (!Files.isDirectory(dir)) {
            throw new UsageException("diretório não encontrado: " + dir);
        }
        List<LogFile> files = LogLoader.load(dir, System.err);
        List<GroupStats> stats = ReportBuilder.build(files, System.err);
        Path out = dir.resolve("report");
        Files.createDirectories(out);
        Files.writeString(out.resolve("report.md"), ReportWriter.markdown(stats), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("report.csv"), ReportWriter.csv(stats), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("tps-series.csv"), ReportWriter.seriesCsv(ReportBuilder.series(files)),
                StandardCharsets.UTF_8);
        System.err.printf("relatorio: %d arquivo(s), %d grupo(s) -> %s%n", files.size(), stats.size(), out);
        return ExitCodes.OK;
    }
}
```

Em `Main.java`, antes de `default`:

```java
                case "relatorio" -> ReportApp.run(rest);
```

- [ ] **Step 5: Rodar e confirmar que passam**

Run: `pwsh scripts/mvn.ps1 -q test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```powershell
git add src
git commit -m "feat(relatorio): Markdown por execução, comparação dos modos e CSVs"
```

---

### Task 10: Usuário, schema e programa `schema`

**Files:**
- Create: `db/00_usuario.sql`
- Create: `db/01_schema.sql`
- Create: `src/main/java/sentinela/infra/SqlScript.java`
- Create: `src/main/java/sentinela/app/SchemaApp.java`
- Modify: `src/main/java/sentinela/app/Main.java` (novo `case`)
- Test: `src/test/java/sentinela/infra/SqlScriptTest.java`

**Interfaces:**
- Consumes: `Args`, `Env`, `ExitCodes`, `StoreUnavailableException` (Tasks 1 e 6).
- Produces:
  - `SqlScript.split(String script) → List<String>`: divide por `;` no fim de linha e remove linhas que são só comentário `--`.
  - `SchemaApp.run(String[] argv) → int`: executa o recurso `/db/01_schema.sql` (empacotado no jar pelo `pom.xml` da Task 1) com o usuário de `ORACLE_USER`.
  - Tabelas `TX` e `DAILY_AGG` e índice `TX_ACC_TS_AMT_IX` no schema `SENTINELA`.

- [ ] **Step 1: Criar os scripts SQL**

`db/00_usuario.sql` (executado como SYSDBA; o chamador define `senha` antes):

```sql
WHENEVER SQLERROR EXIT FAILURE
SET VERIFY OFF
ALTER SESSION SET CONTAINER = SENTINELA;
CREATE USER IF NOT EXISTS sentinela IDENTIFIED BY "&senha" DEFAULT TABLESPACE users QUOTA UNLIMITED ON users;
ALTER USER sentinela IDENTIFIED BY "&senha";
GRANT DB_DEVELOPER_ROLE TO sentinela;
GRANT SELECT_CATALOG_ROLE TO sentinela;
EXIT
```

`SELECT_CATALOG_ROLE` permite ao usuário ler `DBMS_XPLAN` e `V$` ao conferir o plano de execução (Task 13).

`db/01_schema.sql`:

```sql
-- Schema da POC. Idempotente: pode ser executado de novo sem erro.
CREATE TABLE IF NOT EXISTS tx (
  tx_id       NUMBER(19)    NOT NULL,
  account_id  NUMBER(10)    NOT NULL,
  ts          TIMESTAMP(6)  NOT NULL,
  amount      NUMBER(14,2)  NOT NULL,
  CONSTRAINT tx_pk PRIMARY KEY (tx_id)
)
PARTITION BY RANGE (ts) INTERVAL (NUMTODSINTERVAL(1, 'DAY'))
( PARTITION p_inicial VALUES LESS THAN (TIMESTAMP '2000-01-01 00:00:00') );

CREATE INDEX IF NOT EXISTS tx_acc_ts_amt_ix ON tx (account_id, ts, amount) LOCAL;

CREATE TABLE IF NOT EXISTS daily_agg (
  account_id  NUMBER(10)    NOT NULL,
  dia         DATE          NOT NULL,
  cnt         NUMBER(10)    NOT NULL,
  sum_amount  NUMBER(18,2)  NOT NULL,
  min_amount  NUMBER(14,2)  NOT NULL,
  max_amount  NUMBER(14,2)  NOT NULL,
  CONSTRAINT daily_agg_pk PRIMARY KEY (account_id, dia)
)
ORGANIZATION INDEX;
```

- [ ] **Step 2: Escrever o teste do divisor de script**

`src/test/java/sentinela/infra/SqlScriptTest.java`:

```java
package sentinela.infra;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SqlScriptTest {
    @Test
    void splitsOnTrailingSemicolonAndDropsCommentLines() {
        String script = "-- cabeçalho\nCREATE TABLE a (\n  x NUMBER\n);\n\n-- outro\nCREATE INDEX i ON a (x);\n";
        assertEquals(List.of("CREATE TABLE a (\n  x NUMBER\n)", "CREATE INDEX i ON a (x)"), SqlScript.split(script));
    }

    @Test
    void keepsSemicolonInsideLine() {
        assertEquals(List.of("SELECT ';' FROM dual"), SqlScript.split("SELECT ';' FROM dual;\n"));
    }

    @Test
    void schemaResourceHasThreeStatements() throws Exception {
        try (var in = SqlScriptTest.class.getResourceAsStream("/db/01_schema.sql")) {
            assertNotNull(in, "db/01_schema.sql precisa estar no classpath");
            List<String> stmts = SqlScript.split(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(3, stmts.size());
            assertTrue(stmts.get(0).startsWith("CREATE TABLE IF NOT EXISTS tx"));
        }
    }
}
```

- [ ] **Step 3: Rodar e confirmar a falha**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest=SqlScriptTest`
Expected: falha de compilação (`cannot find symbol: class SqlScript`).

- [ ] **Step 4: Implementar `SqlScript` e `SchemaApp`**

`src/main/java/sentinela/infra/SqlScript.java`:

```java
package sentinela.infra;

import java.util.ArrayList;
import java.util.List;

/** Divide um script SQL simples (sem blocos PL/SQL) em comandos para JDBC. */
public final class SqlScript {
    private SqlScript() {}

    public static List<String> split(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("--") || (trimmed.isEmpty() && current.isEmpty())) {
                continue;
            }
            if (trimmed.endsWith(";")) {
                String last = line.substring(0, line.lastIndexOf(';'));
                current.append(last);
                String stmt = current.toString().strip();
                if (!stmt.isEmpty()) {
                    out.add(stmt);
                }
                current.setLength(0);
            } else {
                current.append(line).append('\n');
            }
        }
        String rest = current.toString().strip();
        if (!rest.isEmpty()) {
            out.add(rest);
        }
        return out;
    }
}
```

`src/main/java/sentinela/app/SchemaApp.java`:

```java
package sentinela.app;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import sentinela.infra.Args;
import sentinela.infra.Env;
import sentinela.infra.ExitCodes;
import sentinela.infra.SqlScript;
import sentinela.infra.StoreUnavailableException;

/** Cria TX e DAILY_AGG no schema de ORACLE_USER. Idempotente. */
public final class SchemaApp {
    private SchemaApp() {}

    public static int run(String[] argv) throws Exception {
        Args.parse(argv, Map.of(), Set.of());
        Env env = Env.fromSystem();
        env.requireOracle();
        String script;
        try (InputStream in = SchemaApp.class.getResourceAsStream("/db/01_schema.sql")) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> statements = SqlScript.split(script);
        Connection c;
        try {
            c = DriverManager.getConnection(env.oracleUrl(), env.oracleUser(), env.oraclePassword());
        } catch (SQLException e) {
            throw new StoreUnavailableException("Oracle em " + env.oracleUrl(), e);
        }
        try (c; Statement st = c.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
                System.err.println("schema: ok - " + sql.lines().findFirst().orElse(sql));
            }
        }
        return ExitCodes.OK;
    }
}
```

Em `Main.java`, antes de `default`:

```java
                case "schema" -> SchemaApp.run(rest);
```

- [ ] **Step 5: Rodar os testes unitários**

Run: `pwsh scripts/mvn.ps1 -q test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Criar o usuário no Oracle**

```powershell
$senha = ((Get-Content .env.local | Where-Object { $_ -match '^ORACLE_PASSWORD=' }) -replace '^ORACLE_PASSWORD=', '')
("DEFINE senha = $senha`n" + (Get-Content -Raw db/00_usuario.sql)) | podman exec -i sentinela-oracle sqlplus -s '/ as sysdba'
$LASTEXITCODE
```

Expected: `User created.` (ou nada, se já existir), `User altered.`, `Grant succeeded.` duas vezes e `0`.

- [ ] **Step 7: Empacotar e executar `schema` contra o banco**

```powershell
pwsh scripts/mvn.ps1 -q package -DskipTests
podman run --rm --pod sentinela --env-file .env.local -v "${PWD}/target:/app" docker.io/library/eclipse-temurin:25-jre java -Duser.timezone=UTC -jar /app/sentinela.jar schema
```

Expected: três linhas `schema: ok - CREATE ...` e exit code 0. Rode o mesmo comando de novo: precisa passar outra vez (idempotência).

- [ ] **Step 8: Conferir os objetos criados**

```powershell
$senha = ((Get-Content .env.local | Where-Object { $_ -match '^ORACLE_PASSWORD=' }) -replace '^ORACLE_PASSWORD=', '')
"SET PAGESIZE 50`nSELECT table_name, partitioned, iot_type FROM user_tables ORDER BY 1;`nSELECT index_name, locality FROM user_part_indexes;`nEXIT" | podman exec -i sentinela-oracle sqlplus -s "sentinela/$senha@//localhost:1521/SENTINELA"
```

Expected: `DAILY_AGG NO IOT`, `TX YES` e `TX_ACC_TS_AMT_IX LOCAL`.

- [ ] **Step 9: Commit**

```powershell
git add db src
git commit -m "feat(schema): usuário, DDL de TX e DAILY_AGG e programa schema"
```

---

### Task 11: Ambiente podman reproduzível

**Files:**
- Create: `Containerfile`
- Create: `.containerignore`
- Create: `scripts/poc.ps1`
- Create: `scripts/ambiente.ps1`

**Interfaces:**
- Consumes: programa `schema` (Task 10); `db/00_usuario.sql`.
- Produces:
  - Imagem `localhost/sentinela-poc` com `ENTRYPOINT` no jar; diretório de trabalho `/`, então `./data` e `./logs` são `/data` e `/logs`.
  - `scripts/poc.ps1 <programa> [args...]`: roda um programa num container efêmero no pod, com `./data` e `./logs` montados. Devolve o exit code do programa.
  - `scripts/ambiente.ps1 -Acao up|down|destroy`: idempotente. `up` cria ou reinicia pod, Oracle e Valkey, espera o Oracle, cria o usuário, constrói a imagem e roda `schema`.
  - Container `sentinela-valkey` (`docker.io/valkey/valkey:7.2`, sem persistência).

- [ ] **Step 1: Criar `Containerfile` e `.containerignore`**

`Containerfile`:

```dockerfile
FROM docker.io/library/maven:3.9-eclipse-temurin-25 AS build
WORKDIR /w
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY db db
COPY src src
RUN mvn -B -q package -DskipTests

FROM docker.io/library/eclipse-temurin:25-jre
COPY --from=build /w/target/sentinela.jar /app/sentinela.jar
WORKDIR /
ENTRYPOINT ["java", "-XX:+UseZGC", "-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch", "-Duser.timezone=UTC", "-jar", "/app/sentinela.jar"]
```

`.containerignore`:

```
.git
target
data
logs
docs
.env.local
```

- [ ] **Step 2: Criar `scripts/poc.ps1`**

```powershell
# Roda um programa da POC num container efêmero dentro do pod sentinela.
# Exemplo: pwsh scripts/poc.ps1 gerador --count 1000
param([Parameter(ValueFromRemainingArguments = $true)][string[]] $PocArgs)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$envFile = Join-Path $root '.env.local'
if (-not (Test-Path $envFile)) { throw "Crie $envFile a partir de .env.example" }
$data = Join-Path $root 'data'
$logs = Join-Path $root 'logs'
New-Item -ItemType Directory -Force $data, $logs | Out-Null

podman run --rm --pod sentinela --env-file $envFile `
  -v "${data}:/data" -v "${logs}:/logs" `
  localhost/sentinela-poc @PocArgs
exit $LASTEXITCODE
```

- [ ] **Step 3: Criar `scripts/ambiente.ps1`**

```powershell
# Sobe, derruba ou destrói o ambiente local da POC. Idempotente.
#   up      cria/reinicia pod, Oracle e Valkey; cria usuário; constrói a imagem; roda schema
#   down    remove o pod (o volume do Oracle fica)
#   destroy remove o pod e o volume do Oracle
param([ValidateSet('up', 'down', 'destroy')][string] $Acao = 'up')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$envFile = Join-Path $root '.env.local'
$oracleImage = 'container-registry.oracle.com/database/enterprise:latest'
$valkeyImage = 'docker.io/valkey/valkey:7.2'

function Get-EnvValue([string] $name) {
    $line = Get-Content $envFile | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    if (-not $line) { throw "$name ausente em $envFile" }
    return $line.Substring($name.Length + 1)
}

function Test-Podman([string[]] $podmanArgs) {
    & podman @podmanArgs *> $null
    return $LASTEXITCODE -eq 0
}

function Invoke-Podman {
    & podman @args
    if ($LASTEXITCODE -ne 0) { throw "podman $($args -join ' ') falhou com código $LASTEXITCODE" }
}

switch ($Acao) {
    'up' {
        if (-not (Test-Path $envFile)) { throw "Crie $envFile a partir de .env.example" }
        $oraclePwd = Get-EnvValue 'ORACLE_PWD'

        if (-not (Test-Podman @('image', 'exists', $oracleImage))) { Invoke-Podman pull $oracleImage }
        $release = & podman image inspect $oracleImage --format '{{ index .Labels "release" }}'
        if ($release -notlike '23.26.*') { throw "imagem Oracle com release '$release'; esperado 23.26.x" }

        if (-not (Test-Podman @('pod', 'exists', 'sentinela'))) {
            Invoke-Podman pod create --name sentinela -p 1521:1521 -p 6379:6379 | Out-Null
        }
        if (-not (Test-Podman @('volume', 'exists', 'sentinela-oradata'))) {
            Invoke-Podman volume create sentinela-oradata | Out-Null
        }
        if (Test-Podman @('container', 'exists', 'sentinela-oracle')) {
            Invoke-Podman start sentinela-oracle | Out-Null
        } else {
            Invoke-Podman run -d --pod sentinela --name sentinela-oracle `
                -e ORACLE_SID=ORCLCDB -e ORACLE_PDB=SENTINELA -e "ORACLE_PWD=$oraclePwd" `
                -e INIT_SGA_SIZE=4096 -e INIT_PGA_SIZE=2048 -e ORACLE_CHARACTERSET=AL32UTF8 `
                -v sentinela-oradata:/opt/oracle/oradata $oracleImage | Out-Null
        }
        if (Test-Podman @('container', 'exists', 'sentinela-valkey')) {
            Invoke-Podman start sentinela-valkey | Out-Null
        } else {
            Invoke-Podman run -d --pod sentinela --name sentinela-valkey $valkeyImage `
                valkey-server --save '' --appendonly no | Out-Null
        }

        Write-Host 'aguardando o Oracle (a primeira criação leva de 10 a 20 minutos)...'
        $deadline = (Get-Date).AddMinutes(40)
        while ($true) {
            & podman exec sentinela-oracle /opt/oracle/checkDBStatus.sh *> $null
            if ($LASTEXITCODE -eq 0) { break }
            if ((Get-Date) -gt $deadline) { throw 'Oracle não ficou pronto em 40 minutos; veja podman logs sentinela-oracle' }
            Start-Sleep -Seconds 15
        }

        $sql = "DEFINE senha = $(Get-EnvValue 'ORACLE_PASSWORD')`n" + (Get-Content -Raw (Join-Path $root 'db/00_usuario.sql'))
        $sql | podman exec -i sentinela-oracle sqlplus -s '/ as sysdba'
        if ($LASTEXITCODE -ne 0) { throw 'falha ao criar o usuário da POC' }

        Invoke-Podman build -t sentinela-poc $root
        & (Join-Path $PSScriptRoot 'poc.ps1') schema
        if ($LASTEXITCODE -ne 0) { throw 'falha ao criar o schema' }
        Write-Host 'ambiente pronto'
    }
    'down' {
        if (Test-Podman @('pod', 'exists', 'sentinela')) { Invoke-Podman pod rm -f sentinela | Out-Null }
        Write-Host 'pod removido; volume sentinela-oradata mantido'
    }
    'destroy' {
        if (Test-Podman @('pod', 'exists', 'sentinela')) { Invoke-Podman pod rm -f sentinela | Out-Null }
        if (Test-Podman @('volume', 'exists', 'sentinela-oradata')) { Invoke-Podman volume rm -f sentinela-oradata | Out-Null }
        Write-Host 'pod e volume removidos'
    }
}
```

- [ ] **Step 4: Subir o ambiente**

Run: `pwsh scripts/ambiente.ps1 -Acao up`
Expected: termina com `ambiente pronto`. O Oracle já existente é só reiniciado; o build da imagem baixa as dependências na primeira vez; `schema` imprime três linhas `schema: ok`.

- [ ] **Step 5: Conferir Valkey e a execução de um programa**

```powershell
podman exec sentinela-valkey valkey-cli ping
podman exec sentinela-valkey valkey-cli CONFIG GET save
pwsh scripts/poc.ps1 gerador --count 10 --dataset ./data/smoke.ndjson
Get-Content data/smoke.ndjson | Measure-Object -Line
```

Expected: `PONG`; `save` com valor vazio; o gerador imprime `gerador: 10 transações ...`; o arquivo tem 10 linhas.

- [ ] **Step 6: Conferir a idempotência**

Run: `pwsh scripts/ambiente.ps1 -Acao up`
Expected: termina de novo com `ambiente pronto`, sem erro de "já existe".

- [ ] **Step 7: Commit**

```powershell
Remove-Item data/smoke.ndjson
git add Containerfile .containerignore scripts
git commit -m "build: imagem da POC e scripts do ambiente podman"
```

---

### Task 12: Adapter Oracle do port

**Files:**
- Create: `src/main/java/sentinela/adapter/oracle/OracleDataSources.java`
- Create: `src/main/java/sentinela/adapter/oracle/OracleTime.java`
- Create: `src/main/java/sentinela/adapter/oracle/OracleReadSql.java`
- Create: `src/main/java/sentinela/adapter/oracle/OracleFeatureStore.java`
- Test: `src/test/java/sentinela/adapter/oracle/OracleReadSqlTest.java`
- Test: `src/test/java/sentinela/adapter/oracle/OracleFeatureStoreIT.java`

**Interfaces:**
- Consumes: `FeatureStorePort`, `FeatureVector`, `WindowAgg`, `WindowCatalog`, `Bounds`, `Unit`, `Transaction` (Tasks 2–4); `Env`, `StoreException`, `StoreUnavailableException` (Tasks 1 e 6).
- Produces:
  - `OracleDataSources.pool(Env env, String name, int size, int rowPrefetch, int readTimeoutMs) → PoolDataSource` (pool cheio e testado ao voltar); `OracleDataSources.close(PoolDataSource)`; constantes `HOT_READ_TIMEOUT_MS = 5000`, `NO_TIMEOUT = 0`.
  - `OracleTime` (package-private): `utc(Instant) → LocalDateTime`, `instant(LocalDateTime) → Instant`, `sliceStart(Unit, long) → LocalDateTime` (MINUTE/HOUR), `day(long) → LocalDate`, `dayStartUtc(long epochDay, ZoneId) → LocalDateTime`, `cents(BigDecimal) → long`, `amount(long) → BigDecimal`.
  - `OracleReadSql` (package-private): `SQL`, `binds(long accountId, List<Bounds>) → Object[]` (25 valores).
  - `OracleFeatureStore(DataSource ds, ZoneId zone)` implementa `FeatureStorePort`; `long duplicates()`; constante `READ_SQL_PREFIX = "WITH b AS (SELECT :1 AS lo_1m"` (texto do SQL como aparece em `V$SQL`, usado pela Task 13).

- [ ] **Step 1: Escrever o teste do SQL gerado**

`src/test/java/sentinela/adapter/oracle/OracleReadSqlTest.java`:

```java
package sentinela.adapter.oracle;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import sentinela.domain.WindowCatalog;

class OracleReadSqlTest {
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Instant NOW = Instant.parse("2026-10-29T15:07:30Z");

    @Test
    void placeholderCountMatchesBinds() {
        long placeholders = OracleReadSql.SQL.chars().filter(c -> c == '?').count();
        Object[] binds = OracleReadSql.binds(42, WindowCatalog.bounds(NOW, SP));
        assertEquals(25, binds.length);
        assertEquals(binds.length, placeholders);
    }

    @Test
    void selectsFourColumnsPerWindowInOrder() {
        String sql = OracleReadSql.SQL;
        assertEquals(19, sql.split(" AS cnt_", -1).length - 1);
        assertTrue(sql.indexOf("AS cnt_1m") < sql.indexOf("AS max_72h"));
        assertTrue(sql.indexOf("AS max_72h") < sql.indexOf("AS cnt_7d"));
        assertTrue(sql.contains("AS max_92d"));
        assertTrue(sql.startsWith("WITH b AS (SELECT ? AS lo_1m"));
    }

    @Test
    void bindsFollowWindowBounds() {
        Object[] b = OracleReadSql.binds(42, WindowCatalog.bounds(NOW, SP));
        assertEquals(LocalDateTime.parse("2026-10-29T15:07:00"), b[0], "lo_1m = minuto corrente");
        assertEquals(LocalDateTime.parse("2026-10-29T15:03:00"), b[1], "lo_5m");
        assertEquals(LocalDateTime.parse("2026-10-29T14:08:00"), b[5], "lo_1h = 59 minutos antes");
        assertEquals(LocalDateTime.parse("2026-10-29T14:00:00"), b[6], "lo_2h = hora anterior");
        assertEquals(LocalDateTime.parse("2026-10-26T16:00:00"), b[14], "lo_72h");
        assertEquals(LocalDate.parse("2026-10-22"), b[15], "lo_7d");
        assertEquals(LocalDate.parse("2026-07-29"), b[18], "lo_92d");
        assertEquals(42L, b[19]);
        assertEquals(LocalDateTime.parse("2026-10-26T16:00:00"), b[20], "faixa de TX começa em lo_72h");
        assertEquals(LocalDateTime.parse("2026-10-29T15:08:00"), b[21], "fim do minuto corrente, exclusivo");
        assertEquals(42L, b[22]);
        assertEquals(LocalDate.parse("2026-07-29"), b[23]);
        assertEquals(LocalDate.parse("2026-10-29"), b[24], "hoje, exclusivo");
    }
}
```

- [ ] **Step 2: Rodar e confirmar a falha**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest=OracleReadSqlTest`
Expected: falha de compilação (`cannot find symbol: class OracleReadSql`).

- [ ] **Step 3: Implementar `OracleTime`, `OracleReadSql` e `OracleDataSources`**

`src/main/java/sentinela/adapter/oracle/OracleTime.java`:

```java
package sentinela.adapter.oracle;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import sentinela.domain.Unit;
import sentinela.domain.WindowCatalog;

/** Conversões entre o domínio e as colunas: TIMESTAMP em UTC, DATE como dia civil, NUMBER(14,2). */
final class OracleTime {
    private OracleTime() {}

    static LocalDateTime utc(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    static Instant instant(LocalDateTime utc) {
        return utc.toInstant(ZoneOffset.UTC);
    }

    static LocalDateTime sliceStart(Unit unit, long index) {
        if (unit == Unit.DAY) {
            throw new IllegalArgumentException("dia não é TIMESTAMP; use day()");
        }
        return utc(WindowCatalog.sliceStart(unit, index, ZoneOffset.UTC));
    }

    static LocalDate day(long epochDay) {
        return LocalDate.ofEpochDay(epochDay);
    }

    static LocalDateTime dayStartUtc(long epochDay, ZoneId zone) {
        return utc(WindowCatalog.sliceStart(Unit.DAY, epochDay, zone));
    }

    static long cents(BigDecimal v) {
        return v.movePointRight(2).longValueExact();
    }

    static BigDecimal amount(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}
```

`src/main/java/sentinela/adapter/oracle/OracleReadSql.java`:

```java
package sentinela.adapter.oracle;

import static sentinela.domain.Unit.DAY;
import static sentinela.domain.Unit.HOUR;
import static sentinela.domain.Unit.MINUTE;

import java.util.List;
import sentinela.domain.Bounds;
import sentinela.domain.Window;
import sentinela.domain.WindowCatalog;

/**
 * Gera, a partir do WindowCatalog, o statement único da leitura: agregados condicionais sobre TX
 * (15 janelas de minuto e hora) em CROSS JOIN com agregados sobre DAILY_AGG (4 janelas de dia).
 * Os limites de cada janela entram como colunas da linha b; os predicados de poda usam binds diretos.
 */
final class OracleReadSql {
    static final String SQL = build();

    private static final int LAST_HOUR_WINDOW = WindowCatalog.FIRST_DAY_WINDOW - 1;
    private static final int LAST_DAY_WINDOW = WindowCatalog.SIZE - 1;

    private OracleReadSql() {}

    static Object[] binds(long accountId, List<Bounds> bounds) {
        Object[] b = new Object[WindowCatalog.SIZE + 6];
        int i = 0;
        for (Bounds x : bounds) {
            b[i++] = x.unit() == DAY ? OracleTime.day(x.lo()) : OracleTime.sliceStart(x.unit(), x.lo());
        }
        Bounds currentMinute = bounds.get(0);
        Bounds hours72 = bounds.get(LAST_HOUR_WINDOW);
        Bounds days92 = bounds.get(LAST_DAY_WINDOW);
        b[i++] = accountId;
        b[i++] = OracleTime.sliceStart(HOUR, hours72.lo());
        b[i++] = OracleTime.sliceStart(MINUTE, currentMinute.hi() + 1);
        b[i++] = accountId;
        b[i++] = OracleTime.day(days92.lo());
        b[i] = OracleTime.day(days92.hi() + 1);
        return b;
    }

    private static String build() {
        List<Window> ws = WindowCatalog.WINDOWS;
        StringBuilder sb = new StringBuilder("WITH b AS (SELECT ");
        for (int i = 0; i < ws.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append("? AS lo_").append(ws.get(i).suffix());
        }
        sb.append(" FROM dual) SELECT t.*, d.* FROM (SELECT ");
        for (int i = 0; i < WindowCatalog.FIRST_DAY_WINDOW; i++) {
            String s = ws.get(i).suffix();
            String cond = "x.ts >= b.lo_" + s;
            sb.append(i == 0 ? "" : ", ")
                    .append("COUNT(CASE WHEN ").append(cond).append(" THEN 1 END) AS cnt_").append(s)
                    .append(", SUM(CASE WHEN ").append(cond).append(" THEN x.amount END) AS sum_").append(s)
                    .append(", MIN(CASE WHEN ").append(cond).append(" THEN x.amount END) AS min_").append(s)
                    .append(", MAX(CASE WHEN ").append(cond).append(" THEN x.amount END) AS max_").append(s);
        }
        sb.append(" FROM tx x CROSS JOIN b WHERE x.account_id = ? AND x.ts >= ? AND x.ts < ?) t CROSS JOIN (SELECT ");
        for (int i = WindowCatalog.FIRST_DAY_WINDOW; i < ws.size(); i++) {
            String s = ws.get(i).suffix();
            String cond = "a.dia >= b.lo_" + s;
            sb.append(i == WindowCatalog.FIRST_DAY_WINDOW ? "" : ", ")
                    .append("SUM(CASE WHEN ").append(cond).append(" THEN a.cnt END) AS cnt_").append(s)
                    .append(", SUM(CASE WHEN ").append(cond).append(" THEN a.sum_amount END) AS sum_").append(s)
                    .append(", MIN(CASE WHEN ").append(cond).append(" THEN a.min_amount END) AS min_").append(s)
                    .append(", MAX(CASE WHEN ").append(cond).append(" THEN a.max_amount END) AS max_").append(s);
        }
        sb.append(" FROM daily_agg a CROSS JOIN b WHERE a.account_id = ? AND a.dia >= ? AND a.dia < ?) d");
        return sb.toString();
    }
}
```

`src/main/java/sentinela/adapter/oracle/OracleDataSources.java`:

```java
package sentinela.adapter.oracle;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import oracle.ucp.UniversalConnectionPoolException;
import oracle.ucp.admin.UniversalConnectionPoolManagerImpl;
import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.PoolDataSourceFactory;
import sentinela.infra.Env;
import sentinela.infra.StoreUnavailableException;

/** Pool UCP de tamanho fixo, cheio antes de o relógio do benchmark começar. */
public final class OracleDataSources {
    public static final int HOT_READ_TIMEOUT_MS = 5_000;
    public static final int NO_TIMEOUT = 0;

    private OracleDataSources() {}

    public static PoolDataSource pool(Env env, String name, int size, int rowPrefetch, int readTimeoutMs) {
        env.requireOracle();
        try {
            PoolDataSource pds = PoolDataSourceFactory.getPoolDataSource();
            pds.setConnectionPoolName(name + "-" + System.nanoTime());
            pds.setConnectionFactoryClassName("oracle.jdbc.pool.OracleDataSource");
            pds.setURL(env.oracleUrl());
            pds.setUser(env.oracleUser());
            pds.setPassword(env.oraclePassword());
            pds.setInitialPoolSize(size);
            pds.setMinPoolSize(size);
            pds.setMaxPoolSize(size);
            pds.setValidateConnectionOnBorrow(false);
            Properties p = new Properties();
            p.setProperty("oracle.jdbc.implicitStatementCacheSize", "20");
            p.setProperty("defaultRowPrefetch", Integer.toString(rowPrefetch));
            if (readTimeoutMs > 0) {
                p.setProperty("oracle.jdbc.ReadTimeout", Integer.toString(readTimeoutMs));
            }
            pds.setConnectionProperties(p);
            try (Connection c = pds.getConnection()) {
                if (!c.isValid(5)) {
                    throw new SQLException("conexão inválida");
                }
            }
            return pds;
        } catch (SQLException e) {
            throw new StoreUnavailableException("Oracle em " + env.oracleUrl(), e);
        }
    }

    public static void close(PoolDataSource pds) {
        try {
            UniversalConnectionPoolManagerImpl.getUniversalConnectionPoolManager()
                    .destroyConnectionPool(pds.getConnectionPoolName());
        } catch (UniversalConnectionPoolException e) {
            System.err.println("aviso: falha ao fechar o pool " + pds.getConnectionPoolName() + ": " + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Rodar o teste do SQL**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest=OracleReadSqlTest`
Expected: `BUILD SUCCESS`, 3 testes.

- [ ] **Step 5: Escrever o teste de integração do adapter**

`src/test/java/sentinela/adapter/oracle/OracleFeatureStoreIT.java`:

```java
package sentinela.adapter.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import oracle.ucp.jdbc.PoolDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sentinela.domain.FeatureVector;
import sentinela.domain.Transaction;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowCatalog;
import sentinela.infra.Env;

@Tag("integration")
class OracleFeatureStoreIT {
    private static final long ACC = 990_000_001L;
    private static Env env;
    private static PoolDataSource ds;
    private static OracleFeatureStore store;

    @BeforeAll
    static void setUp() {
        env = Env.fromSystem();
        assumeTrue(env.oracleUrl() != null, "ORACLE_URL ausente");
        ds = OracleDataSources.pool(env, "it-oracle", 2, 1, OracleDataSources.HOT_READ_TIMEOUT_MS);
        store = new OracleFeatureStore(ds, env.zone());
    }

    @AfterAll
    static void tearDown() {
        if (ds != null) {
            OracleDataSources.close(ds);
        }
    }

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = ds.getConnection()) {
            for (String sql : new String[] {"DELETE FROM tx WHERE account_id = ?", "DELETE FROM daily_agg WHERE account_id = ?"}) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setLong(1, ACC);
                    ps.executeUpdate();
                }
            }
        }
    }

    @Test
    void unknownAccountReadsEmptyVector() {
        assertEquals(FeatureVector.empty(), store.read(ACC, Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    @Test
    void readsMinuteAndHourWindowsAndIgnoresDuplicates() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(30);
        store.ingest(new Transaction(9_900_000_001L, ACC, now.minusSeconds(10), 1000));
        store.ingest(new Transaction(9_900_000_002L, ACC, now.minus(2, ChronoUnit.HOURS), 2000));
        store.ingest(new Transaction(9_900_000_003L, ACC, now.minus(30, ChronoUnit.HOURS), 3000));
        long before = store.duplicates();
        store.ingest(new Transaction(9_900_000_001L, ACC, now.minusSeconds(10), 1000));
        assertEquals(before + 1, store.duplicates());

        FeatureVector v = store.read(ACC, now);
        assertEquals(WindowAgg.of(1000), v.agg(0), "1m");
        assertEquals(WindowAgg.of(1000), v.agg(5), "1h");
        assertEquals(WindowAgg.of(1000), v.agg(6), "2h = hora corrente e a anterior");
        assertEquals(new WindowAgg(2, 3000, 1000L, 2000L), v.agg(7), "3h");
        assertEquals(new WindowAgg(2, 3000, 1000L, 2000L), v.agg(12), "24h");
        assertEquals(new WindowAgg(3, 6000, 1000L, 3000L), v.agg(13), "48h");
        assertEquals(WindowAgg.EMPTY, v.agg(15), "7d sem DAILY_AGG");
    }

    @Test
    void readsDayWindowsFromDailyAggExcludingToday() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        long today = WindowCatalog.epochDay(now, env.zone());
        insertDaily(today - 1, 2, "5.00", "1.00", "4.00");
        insertDaily(today - 8, 1, "0.50", "0.50", "0.50");
        insertDaily(today, 9, "90.00", "10.00", "10.00");
        FeatureVector v = store.read(ACC, now);
        assertEquals(new WindowAgg(2, 500, 100L, 400L), v.agg(15), "7d");
        assertEquals(new WindowAgg(3, 550, 50L, 400L), v.agg(16), "31d");
        assertEquals(0, v.agg(0).cnt(), "DAILY_AGG não alimenta minutos");
    }

    private static void insertDaily(long epochDay, int cnt, String sum, String min, String max) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO daily_agg (account_id, dia, cnt, sum_amount, min_amount, max_amount) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, ACC);
            ps.setObject(2, LocalDate.ofEpochDay(epochDay));
            ps.setInt(3, cnt);
            ps.setBigDecimal(4, new BigDecimal(sum));
            ps.setBigDecimal(5, new BigDecimal(min));
            ps.setBigDecimal(6, new BigDecimal(max));
            ps.executeUpdate();
        }
    }
}
```

- [ ] **Step 6: Implementar `OracleFeatureStore`**

`src/main/java/sentinela/adapter/oracle/OracleFeatureStore.java`:

```java
package sentinela.adapter.oracle;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import javax.sql.DataSource;
import sentinela.domain.FeatureStorePort;
import sentinela.domain.FeatureVector;
import sentinela.domain.Transaction;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowCatalog;
import sentinela.infra.StoreException;

/** Oracle como store: TX bruta e DAILY_AGG; leitura em um único statement. */
public final class OracleFeatureStore implements FeatureStorePort {
    /** Início do SQL de leitura como o Oracle o guarda em V$SQL (o JDBC troca ? por :n). */
    public static final String READ_SQL_PREFIX = "WITH b AS (SELECT :1 AS lo_1m";

    private static final String INSERT = "INSERT INTO tx (tx_id, account_id, ts, amount) VALUES (?, ?, ?, ?)";
    private static final int ORA_UNIQUE_CONSTRAINT = 1;

    private final DataSource ds;
    private final ZoneId zone;
    private final LongAdder duplicates = new LongAdder();

    public OracleFeatureStore(DataSource ds, ZoneId zone) {
        this.ds = ds;
        this.zone = zone;
    }

    @Override
    public void ingest(Transaction tx) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setLong(1, tx.txId());
            ps.setLong(2, tx.accountId());
            ps.setObject(3, OracleTime.utc(tx.ts()));
            ps.setBigDecimal(4, OracleTime.amount(tx.amountCents()));
            ps.executeUpdate();
        } catch (SQLIntegrityConstraintViolationException e) {
            if (e.getErrorCode() != ORA_UNIQUE_CONSTRAINT) {
                throw new StoreException("ingest no Oracle, tx_id " + tx.txId(), e);
            }
            duplicates.increment(); // idempotência por tx_id
        } catch (SQLException e) {
            throw new StoreException("ingest no Oracle, tx_id " + tx.txId(), e);
        }
    }

    @Override
    public FeatureVector read(long accountId, Instant now) {
        Object[] binds = OracleReadSql.binds(accountId, WindowCatalog.bounds(now, zone));
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(OracleReadSql.SQL)) {
            for (int i = 0; i < binds.length; i++) {
                ps.setObject(i + 1, binds[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                List<WindowAgg> aggs = new ArrayList<>(WindowCatalog.SIZE);
                for (int w = 0; w < WindowCatalog.SIZE; w++) {
                    int base = w * 4 + 1;
                    long cnt = rs.getLong(base);
                    if (cnt == 0) {
                        aggs.add(WindowAgg.EMPTY);
                    } else {
                        aggs.add(new WindowAgg(cnt, cents(rs.getBigDecimal(base + 1)),
                                cents(rs.getBigDecimal(base + 2)), cents(rs.getBigDecimal(base + 3))));
                    }
                }
                return new FeatureVector(aggs);
            }
        } catch (SQLException e) {
            throw new StoreException("read no Oracle, conta " + accountId, e);
        }
    }

    public long duplicates() {
        return duplicates.sum();
    }

    private static long cents(BigDecimal v) {
        return OracleTime.cents(v);
    }
}
```

- [ ] **Step 7: Rodar o teste de integração**

Run: `pwsh scripts/mvn.ps1 -q verify -Pintegration -Dit.test=OracleFeatureStoreIT`
Expected: `BUILD SUCCESS`, 3 testes de `OracleFeatureStoreIT`. Se o teste for pulado (`Tests run: 3, Skipped: 3`), o `.env.local` não chegou ao container: confira se o pod `sentinela` existe (`podman pod exists sentinela`).

- [ ] **Step 8: Commit**

```powershell
git add src
git commit -m "feat(oracle): adapter do port com leitura em um statement e ingestão idempotente"
```

---

### Task 13: Manutenção Oracle

**Files:**
- Create: `src/main/java/sentinela/adapter/oracle/OracleMaintenance.java`
- Test: `src/test/java/sentinela/adapter/oracle/OracleMaintenanceIT.java`

**Interfaces:**
- Consumes: `OracleTime`, `OracleFeatureStore`, `OracleDataSources` (Task 12); `AccountAgg`, `KeyedSlice`, `Slice`, `WindowAgg`, `WindowCatalog`, `Transaction` (Tasks 2–3).
- Produces (`OracleMaintenance(DataSource ds, ZoneId zone)`):
  - `void reset()` — `TRUNCATE` de `TX` e `DAILY_AGG`.
  - `boolean isEmpty()`, `long countTx()`.
  - `int closeDay(long epochDay)` — delete + insert do dia numa transação; devolve as linhas inseridas.
  - `long checkDay(long epochDay)` — linhas da diferença simétrica entre `DAILY_AGG` e o recálculo; 0 = igual.
  - `List<AccountAgg> dailyRows(long epochDay)`.
  - `List<String> dropOldPartitions(long todayEpochDay)` — nomes das partições removidas.
  - `void gatherStats()`.
  - `long bulkInsertTx(Iterator<Transaction>)` — batch de 5.000, commit a cada 50.000.
  - `long bulkInsertDaily(long epochDay, List<AccountAgg>)`.
  - `void streamSlices(Instant now, Consumer<KeyedSlice> sink)` — fatias `m:` (60), `h:` (72) e `d:` (92) de todas as contas, para reconstruir o Valkey.
  - `List<String> readPlan()` — plano real (`DBMS_XPLAN.DISPLAY_CURSOR`) da última execução do SQL de leitura.

- [ ] **Step 1: Escrever o teste de integração**

`src/test/java/sentinela/adapter/oracle/OracleMaintenanceIT.java`:

```java
package sentinela.adapter.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import oracle.ucp.jdbc.PoolDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sentinela.domain.AccountAgg;
import sentinela.domain.KeyedSlice;
import sentinela.domain.Transaction;
import sentinela.domain.Unit;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowCatalog;
import sentinela.infra.Env;

/** Destrutivo: esvazia TX e DAILY_AGG. */
@Tag("integration")
class OracleMaintenanceIT {
    private static Env env;
    private static PoolDataSource ds;
    private static OracleMaintenance m;
    private static OracleFeatureStore store;

    @BeforeAll
    static void setUp() {
        env = Env.fromSystem();
        assumeTrue(env.oracleUrl() != null, "ORACLE_URL ausente");
        ds = OracleDataSources.pool(env, "it-maint", 2, 1000, OracleDataSources.NO_TIMEOUT);
        m = new OracleMaintenance(ds, env.zone());
        store = new OracleFeatureStore(ds, env.zone());
    }

    @AfterAll
    static void tearDown() {
        if (ds != null) {
            OracleDataSources.close(ds);
        }
    }

    @BeforeEach
    void reset() {
        m.reset();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(30);
    }

    @Test
    void resetEmptiesTables() {
        store.ingest(new Transaction(1, 1, now(), 100));
        assertFalse(m.isEmpty());
        m.reset();
        assertTrue(m.isEmpty());
        assertEquals(0, m.countTx());
    }

    @Test
    void closeDayMatchesRecalculationAndIsIdempotent() {
        Instant now = now();
        long yesterday = WindowCatalog.epochDay(now, env.zone()) - 1;
        Instant noon = WindowCatalog.sliceStart(Unit.DAY, yesterday, env.zone()).plus(12, ChronoUnit.HOURS);
        store.ingest(new Transaction(1, 10, noon, 100));
        store.ingest(new Transaction(2, 10, noon.plusSeconds(60), 300));
        store.ingest(new Transaction(3, 11, noon, 50));
        store.ingest(new Transaction(4, 10, now, 999)); // hoje: fora do dia fechado

        assertEquals(2, m.closeDay(yesterday));
        assertEquals(0, m.checkDay(yesterday));
        List<AccountAgg> rows = m.dailyRows(yesterday);
        assertEquals(List.of(new AccountAgg(10, new WindowAgg(2, 400, 100L, 300L)),
                new AccountAgg(11, WindowAgg.of(50))), rows);

        store.ingest(new Transaction(5, 11, noon.plusSeconds(5), 70));
        assertTrue(m.checkDay(yesterday) > 0, "DAILY_AGG desatualizada deve divergir");
        m.closeDay(yesterday);
        assertEquals(0, m.checkDay(yesterday));
    }

    @Test
    void dropsOnlyPartitionsOlderThanRetention() {
        Instant now = now();
        long today = WindowCatalog.epochDay(now, env.zone());
        store.ingest(new Transaction(1, 1, now.minus(100, ChronoUnit.DAYS), 100));
        store.ingest(new Transaction(2, 1, now.minus(10, ChronoUnit.DAYS), 100));
        List<String> dropped = m.dropOldPartitions(today);
        assertFalse(dropped.isEmpty());
        assertEquals(1, m.countTx(), "só a transação de 10 dias atrás fica");
        assertTrue(m.dropOldPartitions(today).isEmpty(), "segunda execução não remove nada");
    }

    @Test
    void bulkInsertsAndStreamsSlices() {
        Instant now = now();
        long m0 = WindowCatalog.epochMinute(now);
        long today = WindowCatalog.epochDay(now, env.zone());
        List<Transaction> txs = new ArrayList<>();
        LongStream.rangeClosed(1, 12_000).forEach(i -> txs.add(new Transaction(i, 1 + i % 3, now.minusSeconds(i % 50), 100)));
        assertEquals(12_000, m.bulkInsertTx(txs.iterator()));
        assertEquals(12_000, m.countTx());
        m.bulkInsertDaily(today - 5, List.of(new AccountAgg(1, new WindowAgg(4, 1000, 100L, 400L))));

        List<KeyedSlice> slices = new ArrayList<>();
        m.streamSlices(now, slices::add);
        long minuteCnt = slices.stream().filter(s -> s.slice().unit() == Unit.MINUTE).mapToLong(s -> s.slice().agg().cnt()).sum();
        long hourCnt = slices.stream().filter(s -> s.slice().unit() == Unit.HOUR).mapToLong(s -> s.slice().agg().cnt()).sum();
        assertEquals(12_000, minuteCnt);
        assertEquals(12_000, hourCnt);
        assertTrue(slices.stream().allMatch(s -> s.slice().unit() != Unit.MINUTE || s.slice().index() <= m0));
        assertTrue(slices.contains(new KeyedSlice(1, new sentinela.domain.Slice(Unit.DAY, today - 5,
                new WindowAgg(4, 1000, 100L, 400L)))));
    }

    @Test
    void readUsesCoveringLocalIndexWithoutTableAccess() {
        store.ingest(new Transaction(1, 1, now(), 100));
        m.gatherStats();
        store.read(1, now());
        String plan = String.join("\n", m.readPlan());
        assertTrue(plan.contains("TX_ACC_TS_AMT_IX"), plan);
        assertTrue(plan.contains("PARTITION RANGE ITERATOR"), plan);
        assertTrue(plan.contains("DAILY_AGG_PK"), plan);
        assertFalse(plan.contains("TABLE ACCESS"), plan);
    }

    @Test
    void isEmptyIgnoresNothingElse() throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO daily_agg VALUES (1, DATE '2026-01-01', 1, 1, 1, 1)")) {
            ps.executeUpdate();
        }
        assertFalse(m.isEmpty(), "DAILY_AGG com linha também conta");
    }
}
```

- [ ] **Step 2: Rodar e confirmar a falha**

Run: `pwsh scripts/mvn.ps1 -q verify -Pintegration -Dit.test=OracleMaintenanceIT`
Expected: falha de compilação (`cannot find symbol: class OracleMaintenance`).

- [ ] **Step 3: Implementar `OracleMaintenance`**

`src/main/java/sentinela/adapter/oracle/OracleMaintenance.java`:

```java
package sentinela.adapter.oracle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import sentinela.domain.AccountAgg;
import sentinela.domain.KeyedSlice;
import sentinela.domain.Slice;
import sentinela.domain.Transaction;
import sentinela.domain.Unit;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowCatalog;
import sentinela.infra.StoreException;

/** Operações fora do port: Job diário, retenção, reset, carga em massa e reconstrução do Valkey. */
public final class OracleMaintenance {
    static final int TX_RETENTION_DAYS = 93;
    static final int DAY_SLICES = 92;
    private static final int BATCH = 5_000;
    private static final int COMMIT_EVERY = 50_000;
    private static final Pattern HIGH_VALUE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");

    private static final String RECALC = "SELECT account_id, COUNT(*), SUM(amount), MIN(amount), MAX(amount)"
            + " FROM tx WHERE ts >= ? AND ts < ? GROUP BY account_id";
    private static final String STORED = "SELECT account_id, cnt, sum_amount, min_amount, max_amount"
            + " FROM daily_agg WHERE dia = ?";

    private final DataSource ds;
    private final ZoneId zone;

    public OracleMaintenance(DataSource ds, ZoneId zone) {
        this.ds = ds;
        this.zone = zone;
    }

    public void reset() {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE TABLE tx DROP ALL STORAGE");
            st.execute("TRUNCATE TABLE daily_agg");
        } catch (SQLException e) {
            throw new StoreException("reset do Oracle", e);
        }
    }

    public boolean isEmpty() {
        return queryLong("SELECT (SELECT COUNT(*) FROM (SELECT 1 FROM tx FETCH FIRST 1 ROWS ONLY))"
                + " + (SELECT COUNT(*) FROM (SELECT 1 FROM daily_agg FETCH FIRST 1 ROWS ONLY)) FROM dual") == 0;
    }

    public long countTx() {
        return queryLong("SELECT COUNT(*) FROM tx");
    }

    public int closeDay(long epochDay) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement("DELETE FROM daily_agg WHERE dia = ?");
                    PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO daily_agg (account_id, dia, cnt, sum_amount, min_amount, max_amount)"
                                    + " SELECT account_id, ?, COUNT(*), SUM(amount), MIN(amount), MAX(amount)"
                                    + " FROM tx WHERE ts >= ? AND ts < ? GROUP BY account_id")) {
                del.setObject(1, OracleTime.day(epochDay));
                del.executeUpdate();
                ins.setObject(1, OracleTime.day(epochDay));
                ins.setObject(2, OracleTime.dayStartUtc(epochDay, zone));
                ins.setObject(3, OracleTime.dayStartUtc(epochDay + 1, zone));
                int n = ins.executeUpdate();
                c.commit();
                return n;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new StoreException("fechamento do dia " + LocalDate.ofEpochDay(epochDay), e);
        }
    }

    public long checkDay(long epochDay) {
        String sql = "SELECT COUNT(*) FROM ((" + STORED + " MINUS " + RECALC + ") UNION ALL (" + RECALC + " MINUS "
                + STORED + "))";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            LocalDate dia = OracleTime.day(epochDay);
            LocalDateTime ini = OracleTime.dayStartUtc(epochDay, zone);
            LocalDateTime fim = OracleTime.dayStartUtc(epochDay + 1, zone);
            ps.setObject(1, dia);
            ps.setObject(2, ini);
            ps.setObject(3, fim);
            ps.setObject(4, ini);
            ps.setObject(5, fim);
            ps.setObject(6, dia);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new StoreException("verificação do dia " + LocalDate.ofEpochDay(epochDay), e);
        }
    }

    public List<AccountAgg> dailyRows(long epochDay) {
        List<AccountAgg> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(STORED + " ORDER BY account_id")) {
            ps.setObject(1, OracleTime.day(epochDay));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new AccountAgg(rs.getLong(1), agg(rs, 2)));
                }
            }
        } catch (SQLException e) {
            throw new StoreException("leitura de DAILY_AGG do dia " + LocalDate.ofEpochDay(epochDay), e);
        }
        return out;
    }

    public List<String> dropOldPartitions(long todayEpochDay) {
        LocalDateTime cutoff = OracleTime.dayStartUtc(todayEpochDay - TX_RETENTION_DAYS, zone);
        List<String> dropped = new ArrayList<>();
        try (Connection c = ds.getConnection()) {
            List<String> candidates = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT partition_name, high_value FROM user_tab_partitions"
                            + " WHERE table_name = 'TX' AND partition_position > 1 ORDER BY partition_position")) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    Matcher mt = HIGH_VALUE.matcher(rs.getString(2));
                    if (mt.find() && !LocalDateTime.parse(mt.group(1).replace(' ', 'T')).isAfter(cutoff)) {
                        candidates.add(name);
                    }
                }
            }
            try (Statement st = c.createStatement()) {
                for (String name : candidates) {
                    st.execute("ALTER TABLE tx DROP PARTITION \"" + name + "\" UPDATE GLOBAL INDEXES");
                    dropped.add(name);
                }
            }
        } catch (SQLException e) {
            throw new StoreException("remoção de partições antigas", e);
        }
        return dropped;
    }

    public void gatherStats() {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, 'TX'); DBMS_STATS.GATHER_TABLE_STATS(USER, 'DAILY_AGG'); END;");
        } catch (SQLException e) {
            throw new StoreException("coleta de estatísticas", e);
        }
    }

    public long bulkInsertTx(Iterator<Transaction> txs) {
        String sql = "INSERT INTO tx (tx_id, account_id, ts, amount) VALUES (?, ?, ?, ?)";
        return bulk(sql, txs, (ps, tx) -> {
            ps.setLong(1, tx.txId());
            ps.setLong(2, tx.accountId());
            ps.setObject(3, OracleTime.utc(tx.ts()));
            ps.setBigDecimal(4, OracleTime.amount(tx.amountCents()));
        });
    }

    public long bulkInsertDaily(long epochDay, List<AccountAgg> rows) {
        String sql = "INSERT INTO daily_agg (account_id, dia, cnt, sum_amount, min_amount, max_amount) VALUES (?, ?, ?, ?, ?, ?)";
        LocalDate dia = OracleTime.day(epochDay);
        return bulk(sql, rows.iterator(), (ps, r) -> {
            ps.setLong(1, r.accountId());
            ps.setObject(2, dia);
            ps.setLong(3, r.agg().cnt());
            ps.setBigDecimal(4, OracleTime.amount(r.agg().sumCents()));
            ps.setBigDecimal(5, OracleTime.amount(r.agg().minCents()));
            ps.setBigDecimal(6, OracleTime.amount(r.agg().maxCents()));
        });
    }

    public void streamSlices(Instant now, Consumer<KeyedSlice> sink) {
        long minute = WindowCatalog.epochMinute(now);
        long hour = WindowCatalog.epochHour(now);
        long today = WindowCatalog.epochDay(now, zone);
        try (Connection c = ds.getConnection()) {
            streamTxSlices(c, "MI", Unit.MINUTE, OracleTime.sliceStart(Unit.MINUTE, minute - 59), sink);
            streamTxSlices(c, "HH", Unit.HOUR, OracleTime.sliceStart(Unit.HOUR, hour - 71), sink);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT account_id, dia, cnt, sum_amount, min_amount, max_amount FROM daily_agg WHERE dia >= ? AND dia < ?")) {
                ps.setObject(1, OracleTime.day(today - DAY_SLICES));
                ps.setObject(2, OracleTime.day(today));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long day = rs.getObject(2, LocalDate.class).toEpochDay();
                        sink.accept(new KeyedSlice(rs.getLong(1), new Slice(Unit.DAY, day, agg(rs, 3))));
                    }
                }
            }
        } catch (SQLException e) {
            throw new StoreException("leitura de fatias para o Valkey", e);
        }
    }

    public List<String> readPlan() {
        List<String> lines = new ArrayList<>();
        try (Connection c = ds.getConnection()) {
            String sqlId;
            int child;
            try (PreparedStatement ps = c.prepareStatement("SELECT sql_id, child_number FROM v$sql"
                    + " WHERE sql_text LIKE ? AND parsing_schema_name = USER ORDER BY last_active_time DESC"
                    + " FETCH FIRST 1 ROWS ONLY")) {
                ps.setString(1, OracleFeatureStore.READ_SQL_PREFIX + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new StoreException("SQL de leitura não encontrado em V$SQL; execute uma leitura antes", null);
                    }
                    sqlId = rs.getString(1);
                    child = rs.getInt(2);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(?, ?, 'BASIC +PARTITION'))")) {
                ps.setString(1, sqlId);
                ps.setInt(2, child);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lines.add(rs.getString(1));
                    }
                }
            }
        } catch (SQLException e) {
            throw new StoreException("leitura do plano de execução", e);
        }
        return lines;
    }

    @FunctionalInterface
    private interface Binder<T> {
        void bind(PreparedStatement ps, T item) throws SQLException;
    }

    private <T> long bulk(String sql, Iterator<T> items, Binder<T> binder) {
        long n = 0;
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                while (items.hasNext()) {
                    binder.bind(ps, items.next());
                    ps.addBatch();
                    n++;
                    if (n % BATCH == 0) {
                        ps.executeBatch();
                    }
                    if (n % COMMIT_EVERY == 0) {
                        c.commit();
                    }
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new StoreException("carga em massa (" + n + " linhas enviadas)", e);
        }
        return n;
    }

    private void streamTxSlices(Connection c, String fmt, Unit unit, LocalDateTime from, Consumer<KeyedSlice> sink)
            throws SQLException {
        String sql = "SELECT account_id, TRUNC(ts, '" + fmt + "'), COUNT(*), SUM(amount), MIN(amount), MAX(amount)"
                + " FROM tx WHERE ts >= ? GROUP BY account_id, TRUNC(ts, '" + fmt + "')";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, from);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Instant start = OracleTime.instant(rs.getObject(2, LocalDateTime.class));
                    long index = unit == Unit.MINUTE ? WindowCatalog.epochMinute(start) : WindowCatalog.epochHour(start);
                    sink.accept(new KeyedSlice(rs.getLong(1), new Slice(unit, index, agg(rs, 3))));
                }
            }
        }
    }

    private static WindowAgg agg(ResultSet rs, int firstColumn) throws SQLException {
        return new WindowAgg(rs.getLong(firstColumn), OracleTime.cents(rs.getBigDecimal(firstColumn + 1)),
                OracleTime.cents(rs.getBigDecimal(firstColumn + 2)), OracleTime.cents(rs.getBigDecimal(firstColumn + 3)));
    }

    private long queryLong(String sql) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new StoreException("consulta: " + sql, e);
        }
    }
}
```

- [ ] **Step 4: Rodar os testes de integração do Oracle**

Run: `pwsh scripts/mvn.ps1 -q verify -Pintegration -Dit.test='Oracle*IT'`
Expected: `BUILD SUCCESS`, 9 testes. Se `readUsesCoveringLocalIndexWithoutTableAccess` falhar, o texto do plano vem na mensagem: um `TABLE ACCESS BY LOCAL INDEX ROWID` indica que o índice coberto não foi usado. Confira `SELECT column_name FROM user_ind_columns WHERE index_name = 'TX_ACC_TS_AMT_IX'` antes de mudar código.

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat(oracle): job diário, retenção, carga em massa, fatias para o Valkey e plano de execução"
```

---

### Task 14: Adapter Valkey do port

**Files:**
- Create: `src/main/resources/lua/ingest.lua`
- Create: `src/main/resources/lua/put_day.lua`
- Create: `src/main/java/sentinela/adapter/valkey/SliceCodec.java`
- Create: `src/main/java/sentinela/adapter/valkey/ValkeyConnections.java`
- Create: `src/main/java/sentinela/adapter/valkey/LuaScripts.java`
- Create: `src/main/java/sentinela/adapter/valkey/ValkeyFeatureStore.java`
- Test: `src/test/java/sentinela/adapter/valkey/SliceCodecTest.java`
- Test: `src/test/java/sentinela/adapter/valkey/ValkeyFeatureStoreIT.java`

**Interfaces:**
- Consumes: `FeatureStorePort`, `FeatureVector`, `Slice`, `SliceAggregator`, `Unit`, `WindowAgg`, `WindowCatalog`, `Transaction` (Tasks 2–4); `Env`, `StoreException`, `StoreUnavailableException` (Tasks 1 e 6).
- Produces:
  - `SliceCodec.key(long accountId) → "fs:{id}"`, `SliceCodec.field(Unit, long) → "m:123"`, `SliceCodec.encode(WindowAgg) → "cnt|sum|min|max"`, `SliceCodec.decode(String field, String value) → Slice` (lança `IllegalArgumentException` se malformado).
  - `ValkeyConnections.open(Env) → ValkeyConnections`; `hot() → StatefulRedisConnection<String,String>` (compartilhada); `openBulk() → StatefulRedisConnection<String,String>` (nova, o chamador fecha); `close()`.
  - `LuaScripts()` (lê os recursos), `load(RedisCommands)`, `ingest(RedisCommands, String key, long minute, long hour, long cents, long minMinute, long minHour)` (recarrega e repete uma vez em `NOSCRIPT`), `putDayAsync(RedisAsyncCommands, String key, long epochDay, String value, long minDay) → RedisFuture<Long>`.
  - `ValkeyFeatureStore(StatefulRedisConnection<String,String> conn, LuaScripts scripts, ZoneId zone)` implementa `FeatureStorePort`. Constantes `MINUTE_SLICES = 60`, `HOUR_SLICES = 72`, `DAY_SLICES = 92`.

- [ ] **Step 1: Criar os scripts Lua**

`src/main/resources/lua/ingest.lua`:

```lua
-- Atualiza as fatias de minuto e hora de uma conta, atomicamente.
-- KEYS[1] = fs:{conta}
-- ARGV[1] = epoch minuto, ARGV[2] = epoch hora, ARGV[3] = valor em centavos,
-- ARGV[4] = menor minuto retido (M-59), ARGV[5] = menor hora retida (H-71)
local key, amt = KEYS[1], tonumber(ARGV[3])

local function merge(field)
  local cur = redis.call('HGET', key, field)
  if not cur then
    redis.call('HSET', key, field, '1|' .. ARGV[3] .. '|' .. ARGV[3] .. '|' .. ARGV[3])
    return true
  end
  local c, s, mn, mx = string.match(cur, '^(%d+)|(%d+)|(%d+)|(%d+)$')
  redis.call('HSET', key, field, string.format('%d|%d|%d|%d',
    tonumber(c) + 1, tonumber(s) + amt, math.min(tonumber(mn), amt), math.max(tonumber(mx), amt)))
  return false
end

local newMinute = merge('m:' .. ARGV[1])
merge('h:' .. ARGV[2])

if newMinute then
  local minM, minH = tonumber(ARGV[4]), tonumber(ARGV[5])
  for _, f in ipairs(redis.call('HKEYS', key)) do
    local kind, idx = string.sub(f, 1, 1), tonumber(string.sub(f, 3))
    if (kind == 'm' and idx < minM) or (kind == 'h' and idx < minH) then
      redis.call('HDEL', key, f)
    end
  end
end
return 0
```

`src/main/resources/lua/put_day.lua`:

```lua
-- Grava (sobrescreve) a fatia de dia de uma conta e remove fatias de dia fora da retenção.
-- KEYS[1] = fs:{conta}; ARGV[1] = epoch dia, ARGV[2] = 'cnt|sum|min|max', ARGV[3] = menor dia retido (T-92)
redis.call('HSET', KEYS[1], 'd:' .. ARGV[1], ARGV[2])
local minD = tonumber(ARGV[3])
for _, f in ipairs(redis.call('HKEYS', KEYS[1])) do
  if string.sub(f, 1, 2) == 'd:' and tonumber(string.sub(f, 3)) < minD then
    redis.call('HDEL', KEYS[1], f)
  end
end
return 0
```

- [ ] **Step 2: Escrever o teste do codec**

`src/test/java/sentinela/adapter/valkey/SliceCodecTest.java`:

```java
package sentinela.adapter.valkey;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import sentinela.domain.Slice;
import sentinela.domain.Unit;
import sentinela.domain.WindowAgg;

class SliceCodecTest {
    @Test
    void formatsKeyAndFields() {
        assertEquals("fs:{4711}", SliceCodec.key(4711));
        assertEquals("m:29856720", SliceCodec.field(Unit.MINUTE, 29856720));
        assertEquals("h:497612", SliceCodec.field(Unit.HOUR, 497612));
        assertEquals("d:20733", SliceCodec.field(Unit.DAY, 20733));
    }

    @Test
    void encodesAndDecodesCents() {
        WindowAgg a = new WindowAgg(3, 45000, 2000L, 30000L);
        assertEquals("3|45000|2000|30000", SliceCodec.encode(a));
        assertEquals(new Slice(Unit.HOUR, 497612, a), SliceCodec.decode("h:497612", "3|45000|2000|30000"));
        assertEquals(new Slice(Unit.DAY, 20733, WindowAgg.of(7)), SliceCodec.decode("d:20733", "1|7|7|7"));
    }

    @Test
    void rejectsMalformedFieldOrValue() {
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decode("x:1", "1|1|1|1"));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decode("m1", "1|1|1|1"));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decode("m:1", "1|1|1"));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decode("m:1", "1|1|1|1|1"));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decode("m:1", "1|1.5|1|1"));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.encode(WindowAgg.EMPTY));
    }
}
```

- [ ] **Step 3: Rodar e confirmar a falha**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest=SliceCodecTest`
Expected: falha de compilação (`cannot find symbol: class SliceCodec`).

- [ ] **Step 4: Implementar `SliceCodec`**

`src/main/java/sentinela/adapter/valkey/SliceCodec.java`:

```java
package sentinela.adapter.valkey;

import sentinela.domain.Slice;
import sentinela.domain.Unit;
import sentinela.domain.WindowAgg;

/** Formato do hash: chave fs:{conta}, campo m:/h:/d:<índice epoch>, valor cnt|sum|min|max em centavos. */
public final class SliceCodec {
    private SliceCodec() {}

    public static String key(long accountId) {
        return "fs:{" + accountId + "}";
    }

    public static String field(Unit unit, long index) {
        return switch (unit) {
            case MINUTE -> "m:";
            case HOUR -> "h:";
            case DAY -> "d:";
        } + index;
    }

    public static String encode(WindowAgg a) {
        if (a.cnt() == 0) {
            throw new IllegalArgumentException("fatia vazia não é gravada");
        }
        return a.cnt() + "|" + a.sumCents() + "|" + a.minCents() + "|" + a.maxCents();
    }

    public static Slice decode(String field, String value) {
        if (field.length() < 3 || field.charAt(1) != ':') {
            throw new IllegalArgumentException("campo inválido: " + field);
        }
        Unit unit = switch (field.charAt(0)) {
            case 'm' -> Unit.MINUTE;
            case 'h' -> Unit.HOUR;
            case 'd' -> Unit.DAY;
            default -> throw new IllegalArgumentException("campo inválido: " + field);
        };
        long index = Long.parseLong(field, 2, field.length(), 10);
        long[] v = new long[4];
        int k = 0;
        int start = 0;
        for (int i = 0; i <= value.length(); i++) {
            if (i == value.length() || value.charAt(i) == '|') {
                if (k == 4) {
                    throw new IllegalArgumentException("valor inválido: " + value);
                }
                v[k++] = Long.parseLong(value, start, i, 10);
                start = i + 1;
            }
        }
        if (k != 4) {
            throw new IllegalArgumentException("valor inválido: " + value);
        }
        return new Slice(unit, index, new WindowAgg(v[0], v[1], v[2], v[3]));
    }
}
```

- [ ] **Step 5: Rodar o teste do codec**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest=SliceCodecTest`
Expected: `BUILD SUCCESS`, 3 testes.

- [ ] **Step 6: Escrever o teste de integração**

`src/test/java/sentinela/adapter/valkey/ValkeyFeatureStoreIT.java`:

```java
package sentinela.adapter.valkey;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.lettuce.core.api.sync.RedisCommands;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sentinela.domain.FeatureVector;
import sentinela.domain.Transaction;
import sentinela.domain.Unit;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowCatalog;
import sentinela.infra.Env;

@Tag("integration")
class ValkeyFeatureStoreIT {
    private static final long ACC = 990_000_002L;
    private static ValkeyConnections conns;
    private static ValkeyFeatureStore store;
    private static RedisCommands<String, String> raw;

    @BeforeAll
    static void setUp() {
        Env env = Env.fromSystem();
        assumeTrue(env.valkeyUrl() != null, "VALKEY_URL ausente");
        conns = ValkeyConnections.open(env);
        store = new ValkeyFeatureStore(conns.hot(), new LuaScripts(), env.zone());
        raw = conns.hot().sync();
    }

    @AfterAll
    static void tearDown() {
        if (conns != null) {
            conns.close();
        }
    }

    @BeforeEach
    void clean() {
        raw.del(SliceCodec.key(ACC));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(30);
    }

    @Test
    void unknownAccountReadsEmptyVector() {
        assertEquals(FeatureVector.empty(), store.read(ACC, now()));
    }

    @Test
    void ingestCombinesMinuteAndHourSlices() {
        Instant now = now();
        store.ingest(new Transaction(1, ACC, now.minusSeconds(5), 100));
        store.ingest(new Transaction(2, ACC, now, 200));
        String key = SliceCodec.key(ACC);
        assertEquals("2|300|100|200", raw.hget(key, SliceCodec.field(Unit.MINUTE, WindowCatalog.epochMinute(now))));
        assertEquals("2|300|100|200", raw.hget(key, SliceCodec.field(Unit.HOUR, WindowCatalog.epochHour(now))));
    }

    @Test
    void readMatchesWindowDefinition() {
        Instant now = now();
        store.ingest(new Transaction(1, ACC, now.minus(30, ChronoUnit.HOURS), 3000));
        store.ingest(new Transaction(2, ACC, now.minus(2, ChronoUnit.HOURS), 2000));
        store.ingest(new Transaction(3, ACC, now.minusSeconds(10), 1000));
        FeatureVector v = store.read(ACC, now);
        assertEquals(WindowAgg.of(1000), v.agg(0), "1m");
        assertEquals(WindowAgg.of(1000), v.agg(5), "1h");
        assertEquals(WindowAgg.of(1000), v.agg(6), "2h");
        assertEquals(new WindowAgg(2, 3000, 1000L, 2000L), v.agg(7), "3h");
        assertEquals(new WindowAgg(2, 3000, 1000L, 2000L), v.agg(12), "24h");
        assertEquals(new WindowAgg(3, 6000, 1000L, 3000L), v.agg(13), "48h");
    }

    @Test
    void newMinuteRemovesFieldsOutsideRetention() {
        Instant now = now();
        long m = WindowCatalog.epochMinute(now);
        long h = WindowCatalog.epochHour(now);
        store.ingest(new Transaction(1, ACC, now.minus(80, ChronoUnit.HOURS), 100));
        store.ingest(new Transaction(2, ACC, now.minus(100, ChronoUnit.MINUTES), 100));
        store.ingest(new Transaction(3, ACC, now, 100));
        String key = SliceCodec.key(ACC);
        assertFalse(raw.hexists(key, SliceCodec.field(Unit.MINUTE, m - 100)), "minuto fora de 60");
        assertFalse(raw.hexists(key, SliceCodec.field(Unit.HOUR, h - 80)), "hora fora de 72");
        assertTrue(raw.hexists(key, SliceCodec.field(Unit.MINUTE, m)));
        assertTrue(raw.hexists(key, SliceCodec.field(Unit.HOUR, WindowCatalog.epochHour(now.minus(100, ChronoUnit.MINUTES)))),
                "hora de 100 minutos atrás continua dentro de 72h");
    }

    @Test
    void reloadsScriptAfterScriptFlush() {
        raw.scriptFlush();
        store.ingest(new Transaction(1, ACC, now(), 100));
        assertEquals(1, store.read(ACC, now()).agg(0).cnt());
    }
}
```

- [ ] **Step 7: Implementar conexões, scripts e o adapter**

`src/main/java/sentinela/adapter/valkey/ValkeyConnections.java`:

```java
package sentinela.adapter.valkey;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import sentinela.infra.Env;
import sentinela.infra.StoreUnavailableException;

/** Um cliente Lettuce; uma conexão compartilhada (multiplexada) para o caminho quente. */
public final class ValkeyConnections implements AutoCloseable {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(2);

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> hot;

    private ValkeyConnections(RedisClient client, StatefulRedisConnection<String, String> hot) {
        this.client = client;
        this.hot = hot;
    }

    public static ValkeyConnections open(Env env) {
        env.requireValkey();
        RedisURI uri = RedisURI.create(env.valkeyUrl());
        uri.setTimeout(COMMAND_TIMEOUT);
        RedisClient client = RedisClient.create(uri);
        try {
            StatefulRedisConnection<String, String> hot = client.connect();
            hot.sync().ping();
            return new ValkeyConnections(client, hot);
        } catch (RedisException e) {
            client.shutdown();
            throw new StoreUnavailableException("Valkey em " + env.valkeyUrl(), e);
        }
    }

    public StatefulRedisConnection<String, String> hot() {
        return hot;
    }

    /** Conexão dedicada para carga em massa; quem abre fecha. */
    public StatefulRedisConnection<String, String> openBulk() {
        return client.connect();
    }

    @Override
    public void close() {
        hot.close();
        client.shutdown();
    }
}
```

`src/main/java/sentinela/adapter/valkey/LuaScripts.java`:

```java
package sentinela.adapter.valkey;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Scripts Lua carregados com SCRIPT LOAD e chamados por EVALSHA; NOSCRIPT recarrega e repete uma vez. */
public final class LuaScripts {
    private final String ingestSource = resource("/lua/ingest.lua");
    private final String putDaySource = resource("/lua/put_day.lua");
    private volatile String ingestSha;
    private volatile String putDaySha;

    public void load(RedisCommands<String, String> sync) {
        ingestSha = sync.scriptLoad(ingestSource);
        putDaySha = sync.scriptLoad(putDaySource);
    }

    public void ingest(RedisCommands<String, String> sync, String key, long minute, long hour, long cents,
            long minMinute, long minHour) {
        String[] keys = {key};
        String[] args = {Long.toString(minute), Long.toString(hour), Long.toString(cents),
                Long.toString(minMinute), Long.toString(minHour)};
        try {
            sync.evalsha(ingestSha, ScriptOutputType.INTEGER, keys, args);
        } catch (RedisNoScriptException e) {
            load(sync);
            sync.evalsha(ingestSha, ScriptOutputType.INTEGER, keys, args);
        }
    }

    /** Requer load() antes, numa conexão com auto-flush ligado. */
    public RedisFuture<Long> putDayAsync(RedisAsyncCommands<String, String> async, String key, long epochDay,
            String value, long minDay) {
        return async.evalsha(putDaySha, ScriptOutputType.INTEGER, new String[] {key},
                Long.toString(epochDay), value, Long.toString(minDay));
    }

    private static String resource(String path) {
        try (InputStream in = LuaScripts.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("recurso ausente: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

`src/main/java/sentinela/adapter/valkey/ValkeyFeatureStore.java`:

```java
package sentinela.adapter.valkey;

import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import sentinela.domain.FeatureStorePort;
import sentinela.domain.FeatureVector;
import sentinela.domain.Slice;
import sentinela.domain.SliceAggregator;
import sentinela.domain.Transaction;
import sentinela.domain.WindowCatalog;
import sentinela.infra.StoreException;

/** Valkey como store: um hash por conta com fatias de minuto, hora e dia. */
public final class ValkeyFeatureStore implements FeatureStorePort {
    public static final int MINUTE_SLICES = 60;
    public static final int HOUR_SLICES = 72;
    public static final int DAY_SLICES = 92;

    private final RedisCommands<String, String> sync;
    private final LuaScripts scripts;
    private final ZoneId zone;

    public ValkeyFeatureStore(StatefulRedisConnection<String, String> conn, LuaScripts scripts, ZoneId zone) {
        this.sync = conn.sync();
        this.scripts = scripts;
        this.zone = zone;
        scripts.load(sync);
    }

    @Override
    public void ingest(Transaction tx) {
        long minute = WindowCatalog.epochMinute(tx.ts());
        long hour = WindowCatalog.epochHour(tx.ts());
        try {
            scripts.ingest(sync, SliceCodec.key(tx.accountId()), minute, hour, tx.amountCents(),
                    minute - (MINUTE_SLICES - 1), hour - (HOUR_SLICES - 1));
        } catch (RedisException e) {
            throw new StoreException("ingest no Valkey, tx_id " + tx.txId(), e);
        }
    }

    @Override
    public FeatureVector read(long accountId, Instant now) {
        Map<String, String> hash;
        try {
            hash = sync.hgetall(SliceCodec.key(accountId));
        } catch (RedisException e) {
            throw new StoreException("read no Valkey, conta " + accountId, e);
        }
        List<Slice> slices = new ArrayList<>(hash.size());
        hash.forEach((field, value) -> slices.add(SliceCodec.decode(field, value)));
        return SliceAggregator.aggregate(slices, WindowCatalog.bounds(now, zone));
    }
}
```

- [ ] **Step 8: Rodar os testes de integração do Valkey**

Run: `pwsh scripts/mvn.ps1 -q verify -Pintegration -Dit.test=ValkeyFeatureStoreIT`
Expected: `BUILD SUCCESS`, 5 testes.

- [ ] **Step 9: Commit**

```powershell
git add src
git commit -m "feat(valkey): adapter do port com script Lua atômico e leitura por HGETALL"
```

---

### Task 15: Manutenção Valkey

**Files:**
- Create: `src/main/java/sentinela/adapter/valkey/AsyncBatch.java`
- Create: `src/main/java/sentinela/adapter/valkey/ValkeyMaintenance.java`
- Test: `src/test/java/sentinela/adapter/valkey/ValkeyMaintenanceIT.java`

**Interfaces:**
- Consumes: `SliceCodec`, `LuaScripts`, `ValkeyConnections`, `ValkeyFeatureStore` (Task 14); `AccountAgg`, `KeyedSlice` (Task 2).
- Produces (`ValkeyMaintenance(StatefulRedisConnection<String,String> bulk, LuaScripts scripts)`; `bulk` vem de `ValkeyConnections.openBulk()`):
  - `void reset()` (`FLUSHDB`), `boolean isEmpty()` (`DBSIZE == 0`).
  - `int putDays(long epochDay, List<AccountAgg> rows, long todayEpochDay)` — 0 se o dia está fora da retenção.
  - `ValkeyMaintenance.SliceWriter sliceWriter()` — `AutoCloseable` com `void write(KeyedSlice)` e `long written()`; grava com `HSET` em lotes de 1.000.

- [ ] **Step 1: Escrever o teste de integração**

`src/test/java/sentinela/adapter/valkey/ValkeyMaintenanceIT.java`:

```java
package sentinela.adapter.valkey;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sentinela.domain.AccountAgg;
import sentinela.domain.FeatureVector;
import sentinela.domain.KeyedSlice;
import sentinela.domain.Slice;
import sentinela.domain.Unit;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowCatalog;
import sentinela.infra.Env;

/** Destrutivo: FLUSHDB no Valkey. */
@Tag("integration")
class ValkeyMaintenanceIT {
    private static Env env;
    private static ValkeyConnections conns;
    private static StatefulRedisConnection<String, String> bulk;
    private static ValkeyMaintenance vm;
    private static ValkeyFeatureStore store;
    private static RedisCommands<String, String> raw;

    @BeforeAll
    static void setUp() {
        env = Env.fromSystem();
        assumeTrue(env.valkeyUrl() != null, "VALKEY_URL ausente");
        conns = ValkeyConnections.open(env);
        bulk = conns.openBulk();
        LuaScripts scripts = new LuaScripts();
        vm = new ValkeyMaintenance(bulk, scripts);
        store = new ValkeyFeatureStore(conns.hot(), scripts, env.zone());
        raw = conns.hot().sync();
    }

    @AfterAll
    static void tearDown() {
        if (conns != null) {
            bulk.close();
            conns.close();
        }
    }

    @BeforeEach
    void reset() {
        vm.reset();
    }

    @Test
    void resetEmpties() {
        raw.hset("fs:{1}", "m:1", "1|1|1|1");
        assertFalse(vm.isEmpty());
        vm.reset();
        assertTrue(vm.isEmpty());
    }

    @Test
    void putDaysWritesOverwritesAndDropsOldDays() {
        long today = WindowCatalog.epochDay(Instant.now(), env.zone());
        raw.hset("fs:{1}", "d:" + (today - 100), "1|1|1|1");
        List<AccountAgg> rows = List.of(new AccountAgg(1, new WindowAgg(2, 500, 100L, 400L)),
                new AccountAgg(2, WindowAgg.of(50)));
        assertEquals(2, vm.putDays(today - 1, rows, today));
        assertEquals("2|500|100|400", raw.hget("fs:{1}", "d:" + (today - 1)));
        assertEquals("1|50|50|50", raw.hget("fs:{2}", "d:" + (today - 1)));
        assertFalse(raw.hexists("fs:{1}", "d:" + (today - 100)), "dia fora da retenção removido");

        vm.putDays(today - 1, List.of(new AccountAgg(1, WindowAgg.of(9))), today);
        assertEquals("1|9|9|9", raw.hget("fs:{1}", "d:" + (today - 1)), "sobrescreve: idempotente");
        assertEquals(0, vm.putDays(today - 93, rows, today), "dia fora da retenção não é gravado");
    }

    @Test
    void putDaysReloadsScriptsAfterFlush() {
        raw.scriptFlush();
        long today = WindowCatalog.epochDay(Instant.now(), env.zone());
        assertEquals(1, vm.putDays(today - 1, List.of(new AccountAgg(1, WindowAgg.of(9))), today));
    }

    @Test
    void sliceWriterRebuildsReadableHashes() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(30);
        long m = WindowCatalog.epochMinute(now);
        long h = WindowCatalog.epochHour(now);
        long today = WindowCatalog.epochDay(now, env.zone());
        List<KeyedSlice> slices = new ArrayList<>();
        for (long acc = 1; acc <= 2_500; acc++) {
            slices.add(new KeyedSlice(acc, new Slice(Unit.MINUTE, m, WindowAgg.of(100))));
            slices.add(new KeyedSlice(acc, new Slice(Unit.HOUR, h, WindowAgg.of(100))));
            slices.add(new KeyedSlice(acc, new Slice(Unit.DAY, today - 1, new WindowAgg(3, 900, 100L, 500L))));
        }
        try (ValkeyMaintenance.SliceWriter w = vm.sliceWriter()) {
            slices.forEach(w::write);
            w.close();
            assertEquals(7_500, w.written());
        }
        FeatureVector v = store.read(2_500, now);
        assertEquals(WindowAgg.of(100), v.agg(0));
        assertEquals(WindowAgg.of(100), v.agg(6));
        assertEquals(new WindowAgg(3, 900, 100L, 500L), v.agg(15));
        assertEquals(2_500, raw.dbsize());
    }
}
```

`SliceWriter.close()` precisa ser idempotente: o teste fecha explicitamente para conferir `written()` e o try-with-resources fecha de novo.

- [ ] **Step 2: Rodar e confirmar a falha**

Run: `pwsh scripts/mvn.ps1 -q verify -Pintegration -Dit.test=ValkeyMaintenanceIT`
Expected: falha de compilação (`cannot find symbol: class ValkeyMaintenance`).

- [ ] **Step 3: Implementar `AsyncBatch` e `ValkeyMaintenance`**

`src/main/java/sentinela/adapter/valkey/AsyncBatch.java`:

```java
package sentinela.adapter.valkey;

import io.lettuce.core.LettuceFutures;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import sentinela.infra.StoreException;

/** Pipeline manual: auto-flush desligado, flush e espera a cada N comandos. Só na conexão de carga em massa. */
final class AsyncBatch implements AutoCloseable {
    private static final Duration WAIT = Duration.ofSeconds(60);

    private final StatefulRedisConnection<String, String> conn;
    private final RedisAsyncCommands<String, String> async;
    private final int size;
    private final List<RedisFuture<?>> pending = new ArrayList<>();
    private long completed;
    private boolean closed;

    AsyncBatch(StatefulRedisConnection<String, String> conn, int size) {
        this.conn = conn;
        this.size = size;
        conn.setAutoFlushCommands(false);
        this.async = conn.async();
    }

    RedisAsyncCommands<String, String> commands() {
        return async;
    }

    void add(RedisFuture<?> future) {
        pending.add(future);
        if (pending.size() >= size) {
            flush();
        }
    }

    long completed() {
        return completed;
    }

    private void flush() {
        if (pending.isEmpty()) {
            return;
        }
        conn.flushCommands();
        if (!LettuceFutures.awaitAll(WAIT, pending.toArray(new RedisFuture<?>[0]))) {
            throw new StoreException("timeout de " + WAIT + " num lote de " + pending.size() + " comandos", null);
        }
        completed += pending.size();
        pending.clear();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            flush();
        } finally {
            conn.setAutoFlushCommands(true);
        }
    }
}
```

`src/main/java/sentinela/adapter/valkey/ValkeyMaintenance.java`:

```java
package sentinela.adapter.valkey;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.List;
import sentinela.domain.AccountAgg;
import sentinela.domain.KeyedSlice;

/** Operações fora do port: reset, fatias de dia do Job e reconstrução a partir do Oracle. */
public final class ValkeyMaintenance {
    private static final int BATCH = 1_000;

    private final StatefulRedisConnection<String, String> bulk;
    private final RedisCommands<String, String> sync;
    private final LuaScripts scripts;

    public ValkeyMaintenance(StatefulRedisConnection<String, String> bulk, LuaScripts scripts) {
        this.bulk = bulk;
        this.sync = bulk.sync();
        this.scripts = scripts;
    }

    public void reset() {
        sync.flushdb();
    }

    public boolean isEmpty() {
        return sync.dbsize() == 0;
    }

    public int putDays(long epochDay, List<AccountAgg> rows, long todayEpochDay) {
        long minDay = todayEpochDay - ValkeyFeatureStore.DAY_SLICES;
        if (epochDay < minDay) {
            return 0;
        }
        scripts.load(sync); // antes de desligar o auto-flush
        try (AsyncBatch batch = new AsyncBatch(bulk, BATCH)) {
            for (AccountAgg r : rows) {
                batch.add(scripts.putDayAsync(batch.commands(), SliceCodec.key(r.accountId()), epochDay,
                        SliceCodec.encode(r.agg()), minDay));
            }
        }
        return rows.size();
    }

    public SliceWriter sliceWriter() {
        return new SliceWriter(new AsyncBatch(bulk, BATCH));
    }

    /** Grava fatias com HSET, sem combinar: usado logo depois de um reset. */
    public static final class SliceWriter implements AutoCloseable {
        private final AsyncBatch batch;

        private SliceWriter(AsyncBatch batch) {
            this.batch = batch;
        }

        public void write(KeyedSlice s) {
            batch.add(batch.commands().hset(SliceCodec.key(s.accountId()),
                    SliceCodec.field(s.slice().unit(), s.slice().index()), SliceCodec.encode(s.slice().agg())));
        }

        public long written() {
            return batch.completed();
        }

        @Override
        public void close() {
            batch.close();
        }
    }
}
```

- [ ] **Step 4: Rodar todos os testes de integração do Valkey**

Run: `pwsh scripts/mvn.ps1 -q verify -Pintegration -Dit.test='Valkey*IT'`
Expected: `BUILD SUCCESS`, 9 testes.

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat(valkey): reset, fatias de dia e reconstrução em pipeline"
```

---

### Task 16: Fábrica de modos, `ingestor` e `leitor`

**Files:**
- Create: `src/main/java/sentinela/app/Mode.java`
- Create: `src/main/java/sentinela/app/NamedSink.java`
- Create: `src/main/java/sentinela/app/Modes.java`
- Create: `src/main/java/sentinela/app/LoadPaths.java`
- Create: `src/main/java/sentinela/app/IngestorApp.java`
- Create: `src/main/java/sentinela/app/ReaderApp.java`
- Modify: `src/main/java/sentinela/app/Main.java` (dois `case`)
- Test: `src/test/java/sentinela/app/ModeTest.java`
- Test: `src/test/java/sentinela/app/LoadPathsTest.java`

**Interfaces:**
- Consumes: `OracleDataSources`, `OracleFeatureStore` (Task 12); `ValkeyConnections`, `LuaScripts`, `ValkeyFeatureStore` (Task 14); `LoadRunner`, `CsvLog`, `RunMeta`, `ErrorReporter`, `Env` (Task 6); `Dataset` (Task 7); `AccountSampler`, `Seeds` (Task 5).
- Produces:
  - `enum Mode { ORACLE, ORACLE_VALKEY }` com `cli()` (`"oracle"`, `"oracle+valkey"`) e `static parse(String)`.
  - `record NamedSink(String operation, FeatureStorePort port)`.
  - `Modes.ingestSinks(Mode, Env, int threads) → Modes.Wired<List<NamedSink>>`; `Modes.reader(Mode, Env, int threads) → Modes.Wired<FeatureStorePort>`; `record Wired<T>(T value, AutoCloseable closer) implements AutoCloseable`.
  - `LoadPaths.defaultRun(Instant) → "yyyyMMdd-HHmmss"` (UTC), `LoadPaths.defaultLog(String program, Mode, String run) → Path`, `LoadPaths.meta(...) → Map<String,String>`.
  - `IngestorApp.run(String[])` e `ReaderApp.run(String[])`; `Main` despacha `ingestor` e `leitor`. No log, `program` é `ingestor` e `reader`.

- [ ] **Step 1: Escrever os testes**

`src/test/java/sentinela/app/ModeTest.java`:

```java
package sentinela.app;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import sentinela.infra.UsageException;

class ModeTest {
    @Test
    void parsesCliNames() {
        assertEquals(Mode.ORACLE, Mode.parse("oracle"));
        assertEquals(Mode.ORACLE_VALKEY, Mode.parse("oracle+valkey"));
        assertEquals("oracle+valkey", Mode.ORACLE_VALKEY.cli());
        assertThrows(UsageException.class, () -> Mode.parse("valkey"));
    }
}
```

`src/test/java/sentinela/app/LoadPathsTest.java`:

```java
package sentinela.app;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoadPathsTest {
    @Test
    void defaultRunIsUtcTimestamp() {
        assertEquals("20261029-150730", LoadPaths.defaultRun(Instant.parse("2026-10-29T15:07:30.999Z")));
    }

    @Test
    void defaultLogFollowsSpecPattern() {
        assertEquals(Path.of("./logs/reader-oracle+valkey-r1.csv"), LoadPaths.defaultLog("reader", Mode.ORACLE_VALKEY, "r1"));
    }

    @Test
    void metaHasFieldsTheReportNeeds() {
        Map<String, String> m = LoadPaths.meta("ingestor", Mode.ORACLE, "r1", 600, 300, 30, 8, 123L);
        assertEquals("ingestor", m.get("program"));
        assertEquals("oracle", m.get("mode"));
        assertEquals("r1", m.get("run"));
        assertEquals("600", m.get("tps"));
        assertEquals("300", m.get("duration"));
        assertEquals("30", m.get("warmup"));
        assertEquals("8", m.get("threads"));
        assertEquals("123", m.get("start_epoch_ms"));
        assertNotNull(m.get("java.version"));
        assertNotNull(m.get("host"));
    }
}
```

- [ ] **Step 2: Rodar e confirmar a falha**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest='ModeTest,LoadPathsTest'`
Expected: falha de compilação (`cannot find symbol: class Mode`).

- [ ] **Step 3: Implementar `Mode`, `NamedSink`, `LoadPaths` e `Modes`**

`src/main/java/sentinela/app/Mode.java`:

```java
package sentinela.app;

import sentinela.infra.UsageException;

public enum Mode {
    ORACLE("oracle"),
    ORACLE_VALKEY("oracle+valkey");

    private final String cli;

    Mode(String cli) {
        this.cli = cli;
    }

    public String cli() {
        return cli;
    }

    public static Mode parse(String s) {
        for (Mode m : values()) {
            if (m.cli.equals(s)) {
                return m;
            }
        }
        throw new UsageException("--mode precisa ser oracle ou oracle+valkey: " + s);
    }
}
```

`src/main/java/sentinela/app/NamedSink.java`:

```java
package sentinela.app;

import sentinela.domain.FeatureStorePort;

/** Um destino de ingestão e o nome da operação no log (ingest_oracle, ingest_valkey). */
public record NamedSink(String operation, FeatureStorePort port) {}
```

`src/main/java/sentinela/app/LoadPaths.java`:

```java
package sentinela.app;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Nomes de execução e de log, e o conteúdo do .meta.properties de Ingestor e Leitor. */
final class LoadPaths {
    private static final DateTimeFormatter RUN = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private LoadPaths() {}

    static String defaultRun(Instant start) {
        return RUN.format(start);
    }

    static Path defaultLog(String program, Mode mode, String run) {
        return Path.of("./logs", program + "-" + mode.cli() + "-" + run + ".csv");
    }

    static Map<String, String> meta(String program, Mode mode, String run, int tps, int duration, int warmup,
            int threads, long startMs) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("program", program);
        m.put("mode", mode.cli());
        m.put("run", run);
        m.put("tps", Integer.toString(tps));
        m.put("duration", Integer.toString(duration));
        m.put("warmup", Integer.toString(warmup));
        m.put("threads", Integer.toString(threads));
        m.put("start_epoch_ms", Long.toString(startMs));
        m.put("java.version", System.getProperty("java.version"));
        m.put("host", host() + " (" + Runtime.getRuntime().availableProcessors() + " CPUs, heap máx "
                + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB)");
        return m;
    }

    private static String host() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "desconhecido";
        }
    }
}
```

`src/main/java/sentinela/app/Modes.java`:

```java
package sentinela.app;

import java.util.ArrayList;
import java.util.List;
import oracle.ucp.jdbc.PoolDataSource;
import sentinela.adapter.oracle.OracleDataSources;
import sentinela.adapter.oracle.OracleFeatureStore;
import sentinela.adapter.valkey.LuaScripts;
import sentinela.adapter.valkey.ValkeyConnections;
import sentinela.adapter.valkey.ValkeyFeatureStore;
import sentinela.domain.FeatureStorePort;
import sentinela.infra.Env;

/** Seleção de adapters por --mode. A ingestão grava sempre no Oracle; no modo oracle+valkey, também no Valkey. */
public final class Modes {
    public record Wired<T>(T value, AutoCloseable closer) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            closer.close();
        }
    }

    private Modes() {}

    public static Wired<List<NamedSink>> ingestSinks(Mode mode, Env env, int threads) {
        PoolDataSource ds = OracleDataSources.pool(env, "ingestor", threads + 2, 1, OracleDataSources.HOT_READ_TIMEOUT_MS);
        OracleFeatureStore oracle = new OracleFeatureStore(ds, env.zone());
        List<NamedSink> sinks = new ArrayList<>(List.of(new NamedSink("ingest_oracle", oracle)));
        ValkeyConnections valkey = null;
        if (mode == Mode.ORACLE_VALKEY) {
            try {
                valkey = ValkeyConnections.open(env);
            } catch (RuntimeException e) {
                OracleDataSources.close(ds);
                throw e;
            }
            sinks.add(new NamedSink("ingest_valkey", new ValkeyFeatureStore(valkey.hot(), new LuaScripts(), env.zone())));
        }
        ValkeyConnections v = valkey;
        return new Wired<>(List.copyOf(sinks), () -> {
            System.err.println("ingestor: duplicatas ignoradas no Oracle (tx_id já existente): " + oracle.duplicates());
            if (v != null) {
                v.close();
            }
            OracleDataSources.close(ds);
        });
    }

    public static Wired<FeatureStorePort> reader(Mode mode, Env env, int threads) {
        if (mode == Mode.ORACLE) {
            PoolDataSource ds = OracleDataSources.pool(env, "leitor", threads + 2, 1, OracleDataSources.HOT_READ_TIMEOUT_MS);
            return new Wired<>(new OracleFeatureStore(ds, env.zone()), () -> OracleDataSources.close(ds));
        }
        ValkeyConnections valkey = ValkeyConnections.open(env);
        return new Wired<>(new ValkeyFeatureStore(valkey.hot(), new LuaScripts(), env.zone()), valkey);
    }
}
```

- [ ] **Step 4: Rodar os testes unitários**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest='ModeTest,LoadPathsTest'`
Expected: `BUILD SUCCESS`, 4 testes.

- [ ] **Step 5: Implementar `IngestorApp` e `ReaderApp`**

`src/main/java/sentinela/app/IngestorApp.java`:

```java
package sentinela.app;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import sentinela.domain.Transaction;
import sentinela.infra.Args;
import sentinela.infra.CsvLog;
import sentinela.infra.Env;
import sentinela.infra.ErrorReporter;
import sentinela.infra.ExitCodes;
import sentinela.infra.RunMeta;
import sentinela.workload.Dataset;
import sentinela.workload.LoadRunner;

/** Aplica o dataset nos stores à taxa alvo, com ts = agora. Substitui o consumer Kafka. */
public final class IngestorApp {
    static final Map<String, String> DEFAULTS = Args.defaults(
            "mode", "oracle",
            "tps", "600",
            "duration", "300",
            "warmup", "30",
            "dataset", "./data/tx.ndjson",
            "log", null,
            "threads", "8",
            "run", null);

    private IngestorApp() {}

    public static int run(String[] argv) throws Exception {
        Args a = Args.parse(argv, DEFAULTS, Set.of());
        Mode mode = Mode.parse(a.string("mode"));
        int tps = a.positiveInt("tps");
        int duration = a.positiveInt("duration");
        int warmup = a.integer("warmup");
        int threads = a.positiveInt("threads");
        Path dataset = a.path("dataset");
        Env env = Env.fromSystem();

        List<Dataset.Row> rows = Dataset.readAll(dataset);
        if ((long) tps * duration > rows.size()) {
            System.err.printf("aviso: dataset com %d transações não cobre tps × duration = %d%n", rows.size(),
                    (long) tps * duration);
        }
        String run = a.optional("run").orElse(LoadPaths.defaultRun(Instant.now()));
        Path log = a.optional("log").map(Path::of).orElse(LoadPaths.defaultLog("ingestor", mode, run));
        Clock clock = Clock.systemUTC();
        Iterator<Dataset.Row> it = rows.iterator();
        Iterator<Transaction> source = new Iterator<>() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public Transaction next() {
                Dataset.Row r = it.next();
                return new Transaction(r.txId(), r.accountId(), clock.instant().truncatedTo(ChronoUnit.MICROS), r.amountCents());
            }
        };

        try (Modes.Wired<List<NamedSink>> sinks = Modes.ingestSinks(mode, env, threads);
                CsvLog csv = new CsvLog(log, "ingestor", mode.cli());
                ErrorReporter errors = new ErrorReporter(System.err)) {
            List<LoadRunner.Step<Transaction>> steps = sinks.value().stream()
                    .map(s -> new LoadRunner.Step<Transaction>(s.operation(), s.port()::ingest, Transaction::accountId))
                    .toList();
            Map<String, String> meta = LoadPaths.meta("ingestor", mode, run, tps, duration, warmup, threads,
                    System.currentTimeMillis());
            meta.put("dataset", dataset.toString());
            RunMeta.write(log, meta);
            LoadRunner.Result r = new LoadRunner<>(source, steps, tps, Duration.ofSeconds(duration), threads, csv, errors).run();
            RunMeta.put(log, "end_epoch_ms", Long.toString(System.currentTimeMillis()));
            System.err.printf("ingestor: %d transações despachadas, %d erro(s) -> %s%n", r.dispatched(), errors.total(), log);
            if (r.sourceExhausted()) {
                System.err.println("rodada inválida: o dataset acabou antes de --duration; gere com --count maior");
                return ExitCodes.INVALID_RUN;
            }
            return ExitCodes.OK;
        }
    }
}
```

`src/main/java/sentinela/app/ReaderApp.java`:

```java
package sentinela.app;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import sentinela.domain.FeatureStorePort;
import sentinela.infra.Args;
import sentinela.infra.CsvLog;
import sentinela.infra.Env;
import sentinela.infra.ErrorReporter;
import sentinela.infra.ExitCodes;
import sentinela.infra.RunMeta;
import sentinela.workload.AccountSampler;
import sentinela.workload.AccountSampler.Distribution;
import sentinela.workload.LoadRunner;
import sentinela.workload.Seeds;

/** Sorteia contas e lê o vetor de 95 features à taxa alvo. Substitui o serviço de scoring. */
public final class ReaderApp {
    static final Map<String, String> DEFAULTS = Args.defaults(
            "mode", "oracle",
            "tps", "600",
            "duration", "300",
            "warmup", "30",
            "accounts", "100000",
            "seed", "42",
            "distribution", "skewed",
            "log", null,
            "threads", "8",
            "run", null);

    private ReaderApp() {}

    public static int run(String[] argv) throws Exception {
        Args a = Args.parse(argv, DEFAULTS, Set.of());
        Mode mode = Mode.parse(a.string("mode"));
        int tps = a.positiveInt("tps");
        int duration = a.positiveInt("duration");
        int warmup = a.integer("warmup");
        int threads = a.positiveInt("threads");
        int accounts = a.positiveInt("accounts");
        long seed = a.longValue("seed");
        Distribution distribution = Distribution.parse(a.string("distribution"));
        Env env = Env.fromSystem();

        String run = a.optional("run").orElse(LoadPaths.defaultRun(Instant.now()));
        Path log = a.optional("log").map(Path::of).orElse(LoadPaths.defaultLog("reader", mode, run));
        Clock clock = Clock.systemUTC();
        AccountSampler sampler = new AccountSampler(accounts, distribution, Seeds.stream(seed, "leitor.contas"));
        Iterator<Long> source = new Iterator<>() {
            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public Long next() {
                return sampler.next();
            }
        };

        try (Modes.Wired<FeatureStorePort> reader = Modes.reader(mode, env, threads);
                CsvLog csv = new CsvLog(log, "reader", mode.cli());
                ErrorReporter errors = new ErrorReporter(System.err)) {
            FeatureStorePort port = reader.value();
            LoadRunner.Step<Long> step = new LoadRunner.Step<>("read", acc -> port.read(acc, clock.instant()), acc -> acc);
            Map<String, String> meta = LoadPaths.meta("reader", mode, run, tps, duration, warmup, threads,
                    System.currentTimeMillis());
            meta.put("accounts", Integer.toString(accounts));
            meta.put("distribution", a.string("distribution"));
            meta.put("seed", Long.toString(seed));
            RunMeta.write(log, meta);
            LoadRunner.Result r = new LoadRunner<>(source, java.util.List.of(step), tps, Duration.ofSeconds(duration),
                    threads, csv, errors).run();
            RunMeta.put(log, "end_epoch_ms", Long.toString(System.currentTimeMillis()));
            System.err.printf("leitor: %d leituras despachadas, %d erro(s) -> %s%n", r.dispatched(), errors.total(), log);
            return ExitCodes.OK;
        }
    }
}
```

Em `Main.java`, antes de `default`:

```java
                case "ingestor" -> IngestorApp.run(rest);
                case "leitor" -> ReaderApp.run(rest);
```

- [ ] **Step 6: Rodar os testes e reconstruir a imagem**

```powershell
pwsh scripts/mvn.ps1 -q test
podman build -t sentinela-poc .
```

Expected: `BUILD SUCCESS` e imagem construída.

- [ ] **Step 7: Smoke test nos dois modos**

Os testes de integração deixam transações com `tx_id` baixos no banco, que colidiriam com o dataset do smoke. Esvazie os stores antes:

```powershell
$senha = ((Get-Content .env.local | Where-Object { $_ -match '^ORACLE_PASSWORD=' }) -replace '^ORACLE_PASSWORD=', '')
"TRUNCATE TABLE tx;`nTRUNCATE TABLE daily_agg;`nEXIT" | podman exec -i sentinela-oracle sqlplus -s "sentinela/$senha@//localhost:1521/SENTINELA"
podman exec sentinela-valkey valkey-cli FLUSHDB
```

Depois rode os programas:

```powershell
pwsh scripts/poc.ps1 gerador --count 3000 --accounts 1000 --dataset ./data/smoke.ndjson
pwsh scripts/poc.ps1 ingestor --mode oracle+valkey --tps 50 --duration 20 --warmup 5 --dataset ./data/smoke.ndjson --run smoke
pwsh scripts/poc.ps1 leitor --mode oracle+valkey --tps 50 --duration 20 --warmup 5 --accounts 1000 --run smoke
pwsh scripts/poc.ps1 leitor --mode oracle --tps 50 --duration 20 --warmup 5 --accounts 1000 --run smoke
pwsh scripts/poc.ps1 relatorio /logs
Get-Content logs/report/report.md
```

Expected: cada programa termina com exit code 0; `logs/ingestor-oracle+valkey-smoke.csv` tem 2.001 linhas (1.000 transações × 2 sinks + cabeçalho); o relatório mostra `read`, `ingest_oracle` e `ingest_valkey` com `TPS alcançado` perto de 50 e zero erros, mais a tabela `Comparação dos modos`.

- [ ] **Step 8: Limpar e fazer o commit**

```powershell
Remove-Item -Recurse -Force logs/*, data/smoke.ndjson
git add src
git commit -m "feat(carga): fábrica de modos, programas ingestor e leitor"
```

---

### Task 17: Programa `backfill`

**Files:**
- Create: `src/main/java/sentinela/workload/BackfillGenerator.java`
- Create: `src/main/java/sentinela/app/BackfillApp.java`
- Modify: `src/main/java/sentinela/app/Main.java` (novo `case`)
- Test: `src/test/java/sentinela/workload/BackfillGeneratorTest.java`

**Interfaces:**
- Consumes: `AccountSampler`, `AmountSampler`, `Seeds` (Task 5); `Transaction`, `AccountAgg`, `WindowAgg`, `WindowCatalog`, `Unit` (Tasks 2–3); `OracleMaintenance` (Task 13); `ValkeyMaintenance` (Task 15); `Mode` (Task 16).
- Produces:
  - `BackfillGenerator(int accounts, Distribution d, int txPerDay, long seed, ZoneId zone, Instant now)`; `long today()`; `Iterator<Transaction> recentTransactions()` (de 00:00 local de `today − 3` até `now`, `tx_id` a partir de `TX_ID_BASE + 1`); `List<AccountAgg> syntheticDay(long epochDay)` (ordenado por conta, só `cnt > 0`). Constantes `TX_ID_BASE = 1_000_000_000_000_000L` (10^15) e `RECENT_DAYS = 3`.
  - `BackfillApp.run(String[])`. Args: `--mode` (oracle), `--accounts` (100000), `--days` (92, de 3 a 92), `--seed` (42), `--distribution` (skewed), `--tx-per-day` (5).

- [ ] **Step 1: Escrever o teste do gerador**

`src/test/java/sentinela/workload/BackfillGeneratorTest.java`:

```java
package sentinela.workload;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sentinela.domain.AccountAgg;
import sentinela.domain.Transaction;
import sentinela.domain.Unit;
import sentinela.domain.WindowCatalog;
import sentinela.workload.AccountSampler.Distribution;

class BackfillGeneratorTest {
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Instant NOW = Instant.parse("2026-10-29T15:07:30Z"); // 12:07:30 em São Paulo

    private static BackfillGenerator gen() {
        return new BackfillGenerator(1000, Distribution.SKEWED, 5, 42, SP, NOW);
    }

    @Test
    void recentTransactionsCoverThreeClosedDaysAndTodayUntilNow() {
        BackfillGenerator g = gen();
        Instant from = WindowCatalog.sliceStart(Unit.DAY, g.today() - BackfillGenerator.RECENT_DAYS, SP);
        List<Transaction> txs = new ArrayList<>();
        g.recentTransactions().forEachRemaining(txs::add);
        // 3 dias × 5.000 + 12h07m30s de hoje (≈ 50,5% de 5.000)
        assertTrue(txs.size() > 17_400 && txs.size() < 17_600, "quantidade: " + txs.size());
        Set<Long> ids = new HashSet<>();
        for (Transaction t : txs) {
            assertFalse(t.ts().isBefore(from), "antes de D-3: " + t.ts());
            assertTrue(t.ts().isBefore(NOW), "no futuro: " + t.ts());
            assertTrue(t.txId() > BackfillGenerator.TX_ID_BASE);
            assertTrue(t.accountId() >= 1 && t.accountId() <= 1000);
            assertTrue(ids.add(t.txId()), "tx_id repetido");
        }
        assertTrue(txs.stream().anyMatch(t -> WindowCatalog.epochDay(t.ts(), SP) == g.today()), "inclui hoje");
    }

    @Test
    void syntheticDayAggregatesAllDrawsOfTheDay() {
        long day = gen().today() - 10;
        List<AccountAgg> rows = gen().syntheticDay(day);
        assertEquals(5_000, rows.stream().mapToLong(r -> r.agg().cnt()).sum());
        for (int i = 0; i < rows.size(); i++) {
            AccountAgg r = rows.get(i);
            assertTrue(r.agg().minCents() <= r.agg().maxCents());
            assertTrue(r.agg().sumCents() >= r.agg().maxCents());
            if (i > 0) {
                assertTrue(rows.get(i - 1).accountId() < r.accountId(), "ordenado por conta");
            }
        }
        assertEquals(rows, gen().syntheticDay(day), "determinístico");
        assertNotEquals(rows, gen().syntheticDay(day - 1), "dias diferentes, sorteios diferentes");
    }

    @Test
    void sameSeedGivesSameRecentTransactions() {
        Iterator<Transaction> a = gen().recentTransactions();
        Iterator<Transaction> b = gen().recentTransactions();
        for (int i = 0; i < 1000; i++) {
            assertEquals(a.next(), b.next());
        }
    }
}
```

- [ ] **Step 2: Rodar e confirmar a falha**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest=BackfillGeneratorTest`
Expected: falha de compilação (`cannot find symbol: class BackfillGenerator`).

- [ ] **Step 3: Implementar `BackfillGenerator`**

`src/main/java/sentinela/workload/BackfillGenerator.java`:

```java
package sentinela.workload;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.SplittableRandom;
import sentinela.domain.AccountAgg;
import sentinela.domain.Transaction;
import sentinela.domain.Unit;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowCatalog;
import sentinela.workload.AccountSampler.Distribution;

/**
 * Histórico do Backfill: transações de 00:00 de D-3 até agora (vão para TX) e agregados diários
 * sintéticos para dias mais antigos (vão direto para DAILY_AGG).
 */
public final class BackfillGenerator {
    public static final long TX_ID_BASE = 1_000_000_000_000_000L;
    public static final int RECENT_DAYS = 3;

    private final int accounts;
    private final Distribution distribution;
    private final int txPerDay;
    private final long seed;
    private final ZoneId zone;
    private final Instant now;
    private final long today;

    public BackfillGenerator(int accounts, Distribution distribution, int txPerDay, long seed, ZoneId zone, Instant now) {
        this.accounts = accounts;
        this.distribution = distribution;
        this.txPerDay = txPerDay;
        this.seed = seed;
        this.zone = zone;
        this.now = now;
        this.today = WindowCatalog.epochDay(now, zone);
    }

    public long today() {
        return today;
    }

    public Iterator<Transaction> recentTransactions() {
        AccountSampler accountsSampler = new AccountSampler(accounts, distribution, Seeds.stream(seed, "backfill.contas"));
        AmountSampler amounts = new AmountSampler(Seeds.stream(seed, "backfill.valores"));
        SplittableRandom tsRnd = Seeds.stream(seed, "backfill.ts");
        return new Iterator<>() {
            private long day = today - RECENT_DAYS;
            private long remaining = -1;
            private Instant dayStart;
            private long spanMicros;
            private long nextId = TX_ID_BASE + 1;

            @Override
            public boolean hasNext() {
                while (remaining <= 0) {
                    if (remaining == 0) {
                        day++;
                    }
                    if (day > today) {
                        return false;
                    }
                    dayStart = WindowCatalog.sliceStart(Unit.DAY, day, zone);
                    Instant nextDay = WindowCatalog.sliceStart(Unit.DAY, day + 1, zone);
                    Instant end = nextDay.isBefore(now) ? nextDay : now;
                    spanMicros = Duration.between(dayStart, end).toNanos() / 1_000;
                    long dayMicros = Duration.between(dayStart, nextDay).toNanos() / 1_000;
                    remaining = spanMicros <= 0 ? 0
                            : Math.round((double) accounts * txPerDay * spanMicros / dayMicros);
                    if (remaining == 0 && day == today) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public Transaction next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                remaining--;
                Instant ts = dayStart.plus(tsRnd.nextLong(spanMicros), ChronoUnit.MICROS);
                return new Transaction(nextId++, accountsSampler.next(), ts, amounts.nextCents());
            }
        };
    }

    public List<AccountAgg> syntheticDay(long epochDay) {
        SplittableRandom rnd = Seeds.stream(seed, "backfill.dia." + epochDay);
        AccountSampler accountsSampler = new AccountSampler(accounts, distribution, rnd);
        AmountSampler amounts = new AmountSampler(rnd);
        long[] cnt = new long[accounts + 1];
        long[] sum = new long[accounts + 1];
        long[] min = new long[accounts + 1];
        long[] max = new long[accounts + 1];
        long total = (long) accounts * txPerDay;
        for (long i = 0; i < total; i++) {
            int acc = (int) accountsSampler.next();
            long v = amounts.nextCents();
            if (cnt[acc] == 0) {
                min[acc] = v;
                max[acc] = v;
            } else {
                min[acc] = Math.min(min[acc], v);
                max[acc] = Math.max(max[acc], v);
            }
            cnt[acc]++;
            sum[acc] += v;
        }
        List<AccountAgg> out = new ArrayList<>();
        for (int acc = 1; acc <= accounts; acc++) {
            if (cnt[acc] > 0) {
                out.add(new AccountAgg(acc, new WindowAgg(cnt[acc], sum[acc], min[acc], max[acc])));
            }
        }
        return out;
    }
}
```

A lógica de `hasNext` começa com `remaining = -1` para calcular o primeiro dia sem avançar e só avança `day` depois de esgotar um dia (`remaining == 0`).

- [ ] **Step 4: Rodar o teste do gerador**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest=BackfillGeneratorTest`
Expected: `BUILD SUCCESS`, 3 testes.

- [ ] **Step 5: Implementar `BackfillApp` e registrar no `Main`**

`src/main/java/sentinela/app/BackfillApp.java`:

```java
package sentinela.app;

import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import oracle.ucp.jdbc.PoolDataSource;
import sentinela.adapter.oracle.OracleDataSources;
import sentinela.adapter.oracle.OracleMaintenance;
import sentinela.adapter.valkey.LuaScripts;
import sentinela.adapter.valkey.ValkeyConnections;
import sentinela.adapter.valkey.ValkeyMaintenance;
import sentinela.infra.Args;
import sentinela.infra.Env;
import sentinela.infra.ExitCodes;
import sentinela.infra.UsageException;
import sentinela.workload.AccountSampler.Distribution;
import sentinela.workload.BackfillGenerator;

/** Destrutivo: esvazia os stores e popula 92 dias de histórico. No modo oracle+valkey, reconstrói o Valkey. */
public final class BackfillApp {
    static final Map<String, String> DEFAULTS = Args.defaults(
            "mode", "oracle",
            "accounts", "100000",
            "days", "92",
            "seed", "42",
            "distribution", "skewed",
            "tx-per-day", "5");

    private BackfillApp() {}

    public static int run(String[] argv) throws Exception {
        Args a = Args.parse(argv, DEFAULTS, Set.of());
        Mode mode = Mode.parse(a.string("mode"));
        int accounts = a.positiveInt("accounts");
        int days = a.positiveInt("days");
        if (days < BackfillGenerator.RECENT_DAYS || days > 92) {
            throw new UsageException("--days precisa estar entre 3 e 92: " + days);
        }
        long seed = a.longValue("seed");
        Distribution distribution = Distribution.parse(a.string("distribution"));
        int txPerDay = a.positiveInt("tx-per-day");
        Env env = Env.fromSystem();

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        BackfillGenerator gen = new BackfillGenerator(accounts, distribution, txPerDay, seed, env.zone(), now);
        long today = gen.today();
        PoolDataSource ds = OracleDataSources.pool(env, "backfill", 2, 1000, OracleDataSources.NO_TIMEOUT);
        ValkeyConnections valkey = mode == Mode.ORACLE_VALKEY ? ValkeyConnections.open(env) : null;
        StatefulRedisConnection<String, String> bulk = valkey == null ? null : valkey.openBulk();
        try {
            OracleMaintenance om = new OracleMaintenance(ds, env.zone());
            ValkeyMaintenance vm = bulk == null ? null : new ValkeyMaintenance(bulk, new LuaScripts());
            long t0 = System.nanoTime();

            om.reset();
            if (vm != null) {
                vm.reset();
            }
            step(t0, "stores esvaziados");

            long n = om.bulkInsertTx(gen.recentTransactions());
            step(t0, n + " transações em TX desde " + LocalDate.ofEpochDay(today - BackfillGenerator.RECENT_DAYS));

            for (long d = today - BackfillGenerator.RECENT_DAYS; d < today; d++) {
                int rows = om.closeDay(d);
                step(t0, "DAILY_AGG de " + LocalDate.ofEpochDay(d) + " a partir de TX: " + rows + " contas");
            }

            long synthetic = 0;
            for (long d = today - days; d < today - BackfillGenerator.RECENT_DAYS; d++) {
                synthetic += om.bulkInsertDaily(d, gen.syntheticDay(d));
                if ((d - (today - days)) % 10 == 9) {
                    step(t0, "DAILY_AGG sintético até " + LocalDate.ofEpochDay(d) + ": " + synthetic + " linhas");
                }
            }
            step(t0, "DAILY_AGG sintético: " + synthetic + " linhas");

            om.gatherStats();
            step(t0, "estatísticas coletadas");

            if (vm != null) {
                try (ValkeyMaintenance.SliceWriter w = vm.sliceWriter()) {
                    om.streamSlices(now, w::write);
                    w.close();
                    step(t0, "Valkey reconstruído: " + w.written() + " fatias");
                }
            }
            return ExitCodes.OK;
        } finally {
            if (bulk != null) {
                bulk.close();
            }
            if (valkey != null) {
                valkey.close();
            }
            OracleDataSources.close(ds);
        }
    }

    private static void step(long t0, String msg) {
        System.err.printf("backfill [%5.1fs] %s%n", (System.nanoTime() - t0) / 1e9, msg);
    }
}
```

Em `Main.java`, antes de `default`:

```java
                case "backfill" -> BackfillApp.run(rest);
```

- [ ] **Step 6: Rodar os testes, reconstruir a imagem e executar um backfill pequeno**

```powershell
pwsh scripts/mvn.ps1 -q test
podman build -t sentinela-poc .
pwsh scripts/poc.ps1 backfill --mode oracle+valkey --accounts 2000 --days 10
podman exec sentinela-valkey valkey-cli DBSIZE
```

Expected: o backfill imprime as etapas e termina com exit code 0; a última linha é `Valkey reconstruído: N fatias`; `DBSIZE` dá perto de 2.000 (uma chave por conta com histórico).

- [ ] **Step 7: Commit**

```powershell
git add src
git commit -m "feat(backfill): histórico de TX desde D-3, DAILY_AGG sintético e reconstrução do Valkey"
```

---

### Task 18: Programa `job-diario`

**Files:**
- Create: `src/main/java/sentinela/app/DailyJobApp.java`
- Modify: `src/main/java/sentinela/app/Main.java` (novo `case`)
- Test: `src/test/java/sentinela/app/DailyJobAppTest.java`

**Interfaces:**
- Consumes: `OracleMaintenance` (Task 13); `ValkeyMaintenance` (Task 15); `Mode` (Task 16).
- Produces: `DailyJobApp.run(String[])`. Args: `--mode` (oracle), `--day` (`YYYY-MM-DD`, default D-1). Também `DailyJobApp.parseDay(Optional<String>, long todayEpochDay) → long`.

- [ ] **Step 1: Escrever o teste da validação de `--day`**

`src/test/java/sentinela/app/DailyJobAppTest.java`:

```java
package sentinela.app;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sentinela.infra.UsageException;

class DailyJobAppTest {
    private static final long TODAY = LocalDate.of(2026, 10, 29).toEpochDay();

    @Test
    void defaultsToYesterday() {
        assertEquals(TODAY - 1, DailyJobApp.parseDay(Optional.empty(), TODAY));
    }

    @Test
    void parsesIsoDate() {
        assertEquals(LocalDate.of(2026, 10, 20).toEpochDay(), DailyJobApp.parseDay(Optional.of("2026-10-20"), TODAY));
    }

    @Test
    void rejectsTodayFutureAndGarbage() {
        assertThrows(UsageException.class, () -> DailyJobApp.parseDay(Optional.of("2026-10-29"), TODAY));
        assertThrows(UsageException.class, () -> DailyJobApp.parseDay(Optional.of("2026-11-01"), TODAY));
        assertThrows(UsageException.class, () -> DailyJobApp.parseDay(Optional.of("29/10/2026"), TODAY));
    }
}
```

- [ ] **Step 2: Rodar e confirmar a falha**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest=DailyJobAppTest`
Expected: falha de compilação (`cannot find symbol: class DailyJobApp`).

- [ ] **Step 3: Implementar `DailyJobApp` e registrar no `Main`**

`src/main/java/sentinela/app/DailyJobApp.java`:

```java
package sentinela.app;

import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import oracle.ucp.jdbc.PoolDataSource;
import sentinela.adapter.oracle.OracleDataSources;
import sentinela.adapter.oracle.OracleMaintenance;
import sentinela.adapter.valkey.LuaScripts;
import sentinela.adapter.valkey.ValkeyConnections;
import sentinela.adapter.valkey.ValkeyMaintenance;
import sentinela.domain.WindowCatalog;
import sentinela.infra.Args;
import sentinela.infra.Env;
import sentinela.infra.ExitCodes;
import sentinela.infra.UsageException;

/** Fecha um dia: DAILY_AGG a partir de TX, fatias d: no Valkey e remoção de partições antigas. Idempotente. */
public final class DailyJobApp {
    static final Map<String, String> DEFAULTS = Args.defaults("mode", "oracle", "day", null);

    private DailyJobApp() {}

    public static int run(String[] argv) throws Exception {
        Args a = Args.parse(argv, DEFAULTS, Set.of());
        Mode mode = Mode.parse(a.string("mode"));
        Env env = Env.fromSystem();
        long today = WindowCatalog.epochDay(Instant.now(), env.zone());
        long day = parseDay(a.optional("day"), today);

        PoolDataSource ds = OracleDataSources.pool(env, "job-diario", 1, 1000, OracleDataSources.NO_TIMEOUT);
        ValkeyConnections valkey = mode == Mode.ORACLE_VALKEY ? ValkeyConnections.open(env) : null;
        StatefulRedisConnection<String, String> bulk = valkey == null ? null : valkey.openBulk();
        try {
            OracleMaintenance om = new OracleMaintenance(ds, env.zone());
            int rows = om.closeDay(day);
            System.err.printf("job-diario: DAILY_AGG de %s com %d contas%n", LocalDate.ofEpochDay(day), rows);
            if (bulk != null) {
                int written = new ValkeyMaintenance(bulk, new LuaScripts()).putDays(day, om.dailyRows(day), today);
                System.err.printf("job-diario: %d fatias d:%d gravadas no Valkey%n", written, day);
            }
            List<String> dropped = om.dropOldPartitions(today);
            System.err.println("job-diario: partições removidas: " + (dropped.isEmpty() ? "nenhuma" : dropped));
            return ExitCodes.OK;
        } finally {
            if (bulk != null) {
                bulk.close();
            }
            if (valkey != null) {
                valkey.close();
            }
            OracleDataSources.close(ds);
        }
    }

    static long parseDay(Optional<String> arg, long todayEpochDay) {
        if (arg.isEmpty()) {
            return todayEpochDay - 1;
        }
        long day;
        try {
            day = LocalDate.parse(arg.get()).toEpochDay();
        } catch (DateTimeParseException e) {
            throw new UsageException("--day precisa ser YYYY-MM-DD: " + arg.get());
        }
        if (day >= todayEpochDay) {
            throw new UsageException("--day precisa ser um dia fechado, anterior a hoje: " + arg.get());
        }
        return day;
    }
}
```

Em `Main.java`, antes de `default`:

```java
                case "job-diario" -> DailyJobApp.run(rest);
```

- [ ] **Step 4: Rodar os testes, reconstruir e executar sobre o backfill da Task 17**

```powershell
pwsh scripts/mvn.ps1 -q test
podman build -t sentinela-poc .
pwsh scripts/poc.ps1 job-diario --mode oracle+valkey
pwsh scripts/poc.ps1 job-diario --mode oracle+valkey
```

Expected: as duas execuções imprimem o mesmo número de contas para D-1 (idempotência) e `partições removidas: nenhuma`.

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat(job-diario): fechamento do dia no Oracle e no Valkey com retenção de partições"
```

---

### Task 19: Modelo de referência e dados da equivalência

**Files:**
- Create: `src/main/java/sentinela/verification/ReferenceModel.java`
- Create: `src/main/java/sentinela/verification/EquivalenceData.java`
- Test: `src/test/java/sentinela/verification/ReferenceModelTest.java`
- Test: `src/test/java/sentinela/verification/EquivalenceDataTest.java`

**Interfaces:**
- Consumes: `Transaction`, `FeatureVector`, `WindowAgg`, `Window`, `WindowCatalog`, `Slice`, `SliceAggregator`, `Unit` (Tasks 2–4); `Seeds`, `AmountSampler` (Task 5).
- Produces:
  - `ReferenceModel.compute(List<Transaction> txs, Instant now, ZoneId zone) → FeatureVector`. Usa só a lista de janelas (unidade e `n`); os limites são calculados com `Instant.truncatedTo` e `LocalDate.atStartOfDay`, sem `WindowCatalog.bounds`.
  - `EquivalenceData.generate(int accounts, long seed, Instant now, ZoneId zone) → List<Transaction>` (ordenada por `ts`, depois `tx_id`); `EquivalenceData.accountClass(long accountId) → int` (0 normal, 1 silenciosa na última hora, 2 só hoje, 3 fronteiras); `EquivalenceData.boundaries(Instant now, ZoneId zone) → List<Instant>`.

- [ ] **Step 1: Escrever os testes**

`src/test/java/sentinela/verification/ReferenceModelTest.java`:

```java
package sentinela.verification;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sentinela.domain.FeatureVector;
import sentinela.domain.Slice;
import sentinela.domain.SliceAggregator;
import sentinela.domain.Transaction;
import sentinela.domain.Unit;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowCatalog;

class ReferenceModelTest {
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Instant NOW = Instant.parse("2026-10-29T15:07:30Z");

    @Test
    void emptyHistoryGivesEmptyVector() {
        assertEquals(FeatureVector.empty(), ReferenceModel.compute(List.of(), NOW, SP));
    }

    @Test
    void handPickedTransactions() {
        List<Transaction> txs = List.of(
                new Transaction(1, 1, NOW.minusSeconds(10), 1000),
                new Transaction(2, 1, NOW.minus(2, ChronoUnit.HOURS), 2000),
                new Transaction(3, 1, Instant.parse("2026-10-29T02:59:59.999999Z"), 500), // 23:59 de 28/10 em SP
                new Transaction(4, 1, Instant.parse("2026-10-29T03:00:00Z"), 700)); // 00:00 de 29/10 em SP
        FeatureVector v = ReferenceModel.compute(txs, NOW, SP);
        assertEquals(WindowAgg.of(1000), v.agg(0));
        assertEquals(new WindowAgg(2, 3000, 1000L, 2000L), v.agg(7), "3h");
        assertEquals(WindowAgg.of(500), v.agg(15), "7d tem só o dia 28, não o 29");
        assertEquals(4, v.agg(12).cnt(), "24h em UTC alcança 28/10 16:00Z");
    }

    /** O domínio (WindowCatalog + SliceAggregator) e a referência precisam concordar em dados aleatórios. */
    @Test
    void agreesWithDomainSliceAggregation() {
        List<Transaction> txs = EquivalenceData.generate(400, 7, NOW, SP);
        Map<Long, List<Transaction>> byAccount = new HashMap<>();
        txs.forEach(t -> byAccount.computeIfAbsent(t.accountId(), k -> new ArrayList<>()).add(t));
        for (Map.Entry<Long, List<Transaction>> e : byAccount.entrySet()) {
            FeatureVector expected = ReferenceModel.compute(e.getValue(), NOW, SP);
            FeatureVector actual = SliceAggregator.aggregate(slicesOf(e.getValue()), WindowCatalog.bounds(NOW, SP));
            assertEquals(expected, actual, "conta " + e.getKey());
        }
    }

    private static List<Slice> slicesOf(List<Transaction> txs) {
        Map<String, Slice> m = new HashMap<>();
        for (Transaction t : txs) {
            add(m, new Slice(Unit.MINUTE, WindowCatalog.epochMinute(t.ts()), WindowAgg.of(t.amountCents())));
            add(m, new Slice(Unit.HOUR, WindowCatalog.epochHour(t.ts()), WindowAgg.of(t.amountCents())));
            add(m, new Slice(Unit.DAY, WindowCatalog.epochDay(t.ts(), SP), WindowAgg.of(t.amountCents())));
        }
        return new ArrayList<>(m.values());
    }

    private static void add(Map<String, Slice> m, Slice s) {
        m.merge(s.unit() + ":" + s.index(), s, (a, b) -> new Slice(a.unit(), a.index(), a.agg().plus(b.agg())));
    }
}
```

`src/test/java/sentinela/verification/EquivalenceDataTest.java`:

```java
package sentinela.verification;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import sentinela.domain.Transaction;
import sentinela.domain.Unit;
import sentinela.domain.WindowCatalog;

class EquivalenceDataTest {
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Instant NOW = Instant.parse("2026-10-29T15:07:30.123456Z");

    private final List<Transaction> txs = EquivalenceData.generate(200, 42, NOW, SP);

    @Test
    void isDeterministicSortedAndNeverInTheFuture() {
        assertEquals(txs, EquivalenceData.generate(200, 42, NOW, SP));
        for (int i = 1; i < txs.size(); i++) {
            assertFalse(txs.get(i).ts().isBefore(txs.get(i - 1).ts()), "ordem por ts");
        }
        assertTrue(txs.stream().noneMatch(t -> t.ts().isAfter(NOW)));
        assertTrue(txs.stream().allMatch(t -> t.accountId() >= 1 && t.accountId() <= 200));
    }

    @Test
    void silentAccountsHaveNothingInTheLastHourSlices() {
        Instant lastHour = WindowCatalog.sliceStart(Unit.MINUTE, WindowCatalog.epochMinute(NOW) - 59, SP);
        List<Transaction> silent = txs.stream().filter(t -> EquivalenceData.accountClass(t.accountId()) == 1).toList();
        assertFalse(silent.isEmpty());
        assertTrue(silent.stream().allMatch(t -> t.ts().isBefore(lastHour)));
    }

    @Test
    void todayOnlyAccountsTransactOnlyToday() {
        long today = WindowCatalog.epochDay(NOW, SP);
        List<Transaction> todayOnly = txs.stream().filter(t -> EquivalenceData.accountClass(t.accountId()) == 2).toList();
        assertFalse(todayOnly.isEmpty());
        assertTrue(todayOnly.stream().allMatch(t -> WindowCatalog.epochDay(t.ts(), SP) == today));
    }

    @Test
    void boundaryAccountsHitEveryWindowLowerBound() {
        List<Instant> b = EquivalenceData.boundaries(NOW, SP);
        Instant lo5m = WindowCatalog.sliceStart(Unit.MINUTE, WindowCatalog.epochMinute(NOW) - 4, SP);
        assertTrue(b.contains(lo5m));
        assertTrue(b.contains(lo5m.minusNanos(1_000)));
        assertTrue(b.contains(Instant.parse("2026-10-29T03:00:00Z")), "00:00 local");
        assertTrue(b.contains(Instant.parse("2026-10-29T00:00:00Z")), "00:00 UTC");
        List<Instant> ofAccount3 = txs.stream().filter(t -> t.accountId() == 3).map(Transaction::ts).toList();
        assertTrue(ofAccount3.containsAll(b));
    }
}
```

- [ ] **Step 2: Rodar e confirmar a falha**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest='ReferenceModelTest,EquivalenceDataTest'`
Expected: falha de compilação (`cannot find symbol: class ReferenceModel`).

- [ ] **Step 3: Implementar**

`src/main/java/sentinela/verification/ReferenceModel.java`:

```java
package sentinela.verification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import sentinela.domain.FeatureVector;
import sentinela.domain.Transaction;
import sentinela.domain.Window;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowCatalog;

/**
 * Cálculo direto das 19 janelas sobre a lista de transações, sem fatias e sem WindowCatalog.bounds.
 * Serve de terceiro voto: se Oracle e Valkey errarem do mesmo jeito, a referência discorda.
 */
public final class ReferenceModel {
    private ReferenceModel() {}

    public static FeatureVector compute(List<Transaction> txs, Instant now, ZoneId zone) {
        Instant minuteStart = now.truncatedTo(ChronoUnit.MINUTES);
        Instant hourStart = now.truncatedTo(ChronoUnit.HOURS);
        Instant upper = minuteStart.plus(1, ChronoUnit.MINUTES);
        LocalDate today = LocalDate.ofInstant(now, zone);
        Instant todayStart = today.atStartOfDay(zone).toInstant();
        List<WindowAgg> aggs = new ArrayList<>(WindowCatalog.SIZE);
        for (Window w : WindowCatalog.WINDOWS) {
            Instant from;
            Instant to;
            switch (w.unit()) {
                case MINUTE -> {
                    from = minuteStart.minus(w.n() - 1, ChronoUnit.MINUTES);
                    to = upper;
                }
                case HOUR -> {
                    from = hourStart.minus(w.n() - 1, ChronoUnit.HOURS);
                    to = upper;
                }
                default -> {
                    from = today.minusDays(w.n()).atStartOfDay(zone).toInstant();
                    to = todayStart;
                }
            }
            WindowAgg acc = WindowAgg.EMPTY;
            for (Transaction t : txs) {
                if (!t.ts().isBefore(from) && t.ts().isBefore(to)) {
                    acc = acc.plus(WindowAgg.of(t.amountCents()));
                }
            }
            aggs.add(acc);
        }
        return new FeatureVector(aggs);
    }
}
```

`src/main/java/sentinela/verification/EquivalenceData.java`:

```java
package sentinela.verification;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import sentinela.domain.Bounds;
import sentinela.domain.Transaction;
import sentinela.domain.Unit;
import sentinela.domain.WindowCatalog;
import sentinela.workload.AmountSampler;
import sentinela.workload.Seeds;

/** Sequência do cenário C: 93 dias de transações em quatro classes de conta (conta mod 4). */
public final class EquivalenceData {
    public static final int NORMAL = 0;
    public static final int SILENT_LAST_HOUR = 1;
    public static final int TODAY_ONLY = 2;
    public static final int BOUNDARIES = 3;
    private static final int HISTORY_DAYS = 93;

    private EquivalenceData() {}

    public static int accountClass(long accountId) {
        return (int) (accountId % 4);
    }

    public static List<Transaction> generate(int accounts, long seed, Instant now, ZoneId zone) {
        SplittableRandom rnd = Seeds.stream(seed, "equivalencia");
        AmountSampler amounts = new AmountSampler(rnd);
        Instant start = now.minus(HISTORY_DAYS, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        Instant lastHourStart = WindowCatalog.sliceStart(Unit.MINUTE, WindowCatalog.epochMinute(now) - 59, zone);
        Instant todayStart = WindowCatalog.sliceStart(Unit.DAY, WindowCatalog.epochDay(now, zone), zone);
        List<Instant> boundaries = boundaries(now, zone);
        List<Transaction> out = new ArrayList<>();
        long nextId = 1;
        for (long acc = 1; acc <= accounts; acc++) {
            List<Instant> times = new ArrayList<>();
            switch (accountClass(acc)) {
                case NORMAL -> addUniform(times, rnd, rnd.nextInt(301), start, now, true);
                case SILENT_LAST_HOUR -> addUniform(times, rnd, rnd.nextInt(301), start, lastHourStart, false);
                case TODAY_ONLY -> addUniform(times, rnd, 1 + rnd.nextInt(20), todayStart, now, true);
                default -> times.addAll(boundaries);
            }
            for (Instant ts : times) {
                out.add(new Transaction(nextId++, acc, ts, amounts.nextCents()));
            }
        }
        out.sort(Comparator.comparing(Transaction::ts).thenComparingLong(Transaction::txId));
        return out;
    }

    /** Limite inferior de cada janela e 1 µs antes, mais 00:00 local e 00:00 UTC de hoje e 1 µs antes. */
    public static List<Instant> boundaries(Instant now, ZoneId zone) {
        Set<Instant> s = new LinkedHashSet<>();
        for (Bounds b : WindowCatalog.bounds(now, zone)) {
            Instant lower = WindowCatalog.sliceStart(b.unit(), b.lo(), zone);
            s.add(lower);
            s.add(lower.minusNanos(1_000));
        }
        Instant localMidnight = WindowCatalog.sliceStart(Unit.DAY, WindowCatalog.epochDay(now, zone), zone);
        Instant utcMidnight = now.truncatedTo(ChronoUnit.DAYS);
        for (Instant t : List.of(localMidnight, utcMidnight)) {
            s.add(t);
            s.add(t.minusNanos(1_000));
        }
        return s.stream().filter(t -> !t.isAfter(now)).toList();
    }

    private static void addUniform(List<Instant> times, SplittableRandom rnd, int n, Instant from, Instant to,
            boolean inclusive) {
        long span = Duration.between(from, to).toNanos() / 1_000 + (inclusive ? 1 : 0);
        if (span <= 0) {
            return;
        }
        for (int i = 0; i < n; i++) {
            times.add(from.plus(rnd.nextLong(span), ChronoUnit.MICROS));
        }
    }
}
```

O `now` passado para `generate` precisa ter precisão de microssegundo (o `EquivalenceApp` trunca); `from` em `TODAY_ONLY` é meia-noite, sem fração.

- [ ] **Step 4: Rodar e confirmar que passam**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest='ReferenceModelTest,EquivalenceDataTest'`
Expected: `BUILD SUCCESS`, 7 testes. Se `agreesWithDomainSliceAggregation` falhar, a mensagem mostra a conta e os dois vetores: a divergência está em `WindowCatalog` ou em `SliceAggregator`, não no teste.

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat(verificacao): modelo de referência independente e dados do cenário C"
```

---

### Task 20: Programa `equivalencia`

**Files:**
- Create: `src/main/java/sentinela/verification/EquivalenceCheck.java`
- Create: `src/main/java/sentinela/app/EquivalenceApp.java`
- Modify: `src/main/java/sentinela/app/Main.java` (novo `case`)
- Test: `src/test/java/sentinela/verification/EquivalenceCheckTest.java`
- Test: `src/test/java/sentinela/verification/EquivalenceIT.java`

**Interfaces:**
- Consumes: `ReferenceModel`, `EquivalenceData` (Task 19); `OracleFeatureStore`, `OracleMaintenance`, `OracleDataSources` (Tasks 12–13); `ValkeyFeatureStore`, `ValkeyMaintenance`, `ValkeyConnections`, `LuaScripts` (Tasks 14–15).
- Produces:
  - `EquivalenceCheck(FeatureStorePort oracle, OracleMaintenance om, FeatureStorePort valkey, ValkeyMaintenance vm, ZoneId zone, PrintStream progress)`; `Report run(int accounts, long seed, Instant now, boolean reset)`.
  - `record EquivalenceCheck.CheckResult(String name, boolean passed, List<String> details)`; `record EquivalenceCheck.Report(List<CheckResult> checks)` com `allPassed()`.
  - `EquivalenceCheck.diff(long account, String labelA, FeatureVector a, String labelB, FeatureVector b) → List<String>`.
  - `EquivalenceApp.run(String[])`. Args: `--accounts` (1000), `--seed` (42), `--now` (ISO-8601, default agora), flag `--reset`. Exit 0 se tudo passa, 1 se algo falha.

- [ ] **Step 1: Escrever o teste do `diff`**

`src/test/java/sentinela/verification/EquivalenceCheckTest.java`:

```java
package sentinela.verification;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import sentinela.domain.FeatureVector;
import sentinela.domain.WindowAgg;

class EquivalenceCheckTest {
    @Test
    void diffNamesEachDivergentPosition() {
        List<WindowAgg> aggs = new ArrayList<>(Collections.nCopies(19, WindowAgg.EMPTY));
        aggs.set(1, WindowAgg.of(100));
        FeatureVector b = new FeatureVector(aggs);
        List<String> d = EquivalenceCheck.diff(7, "oracle", FeatureVector.empty(), "valkey", b);
        assertEquals(5, d.size(), "cnt, sum, min, max e avg de 5m");
        assertEquals("conta 7 cnt_5m: oracle=0 valkey=1", d.get(0));
        assertTrue(d.get(2).startsWith("conta 7 min_5m: oracle=null valkey=1.00"));
    }

    @Test
    void equalVectorsHaveNoDiff() {
        assertTrue(EquivalenceCheck.diff(1, "a", FeatureVector.empty(), "b", FeatureVector.empty()).isEmpty());
    }
}
```

- [ ] **Step 2: Rodar e confirmar a falha**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest=EquivalenceCheckTest`
Expected: falha de compilação (`cannot find symbol: class EquivalenceCheck`).

- [ ] **Step 3: Implementar `EquivalenceCheck`**

`src/main/java/sentinela/verification/EquivalenceCheck.java`:

```java
package sentinela.verification;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import sentinela.adapter.oracle.OracleMaintenance;
import sentinela.adapter.valkey.ValkeyMaintenance;
import sentinela.domain.FeatureStorePort;
import sentinela.domain.FeatureVector;
import sentinela.domain.Transaction;
import sentinela.domain.WindowCatalog;
import sentinela.infra.UsageException;

/** Cenário C e critérios funcionais sem carga: mesma sequência nos dois adapters, leituras no mesmo instante lógico. */
public final class EquivalenceCheck {
    public record CheckResult(String name, boolean passed, List<String> details) {}

    public record Report(List<CheckResult> checks) {
        public boolean allPassed() {
            return checks.stream().allMatch(CheckResult::passed);
        }
    }

    private static final int MAX_DETAILS = 20;
    private static final int LAST_MINUTE_WINDOW = 5; // 1h
    private static final int DAY_SLICES = 92;

    private final FeatureStorePort oracle;
    private final OracleMaintenance om;
    private final FeatureStorePort valkey;
    private final ValkeyMaintenance vm;
    private final ZoneId zone;
    private final PrintStream progress;

    public EquivalenceCheck(FeatureStorePort oracle, OracleMaintenance om, FeatureStorePort valkey,
            ValkeyMaintenance vm, ZoneId zone, PrintStream progress) {
        this.oracle = oracle;
        this.om = om;
        this.valkey = valkey;
        this.vm = vm;
        this.zone = zone;
        this.progress = progress;
    }

    public Report run(int accounts, long seed, Instant now, boolean reset) {
        if (!reset && !(om.isEmpty() && vm.isEmpty())) {
            throw new UsageException("os stores não estão vazios; rode com --reset (apaga TX, DAILY_AGG e o Valkey)");
        }
        om.reset();
        vm.reset();

        List<Transaction> txs = EquivalenceData.generate(accounts, seed, now, zone);
        progress.printf("equivalencia: aplicando %d transações nos dois adapters%n", txs.size());
        ingestAll(txs, true);

        long today = WindowCatalog.epochDay(now, zone);
        List<String> jobDetails = new ArrayList<>();
        for (long d = today - DAY_SLICES; d < today; d++) {
            om.closeDay(d);
            vm.putDays(d, om.dailyRows(d), today);
            long diff = om.checkDay(d);
            if (diff != 0) {
                add(jobDetails, "dia " + LocalDate.ofEpochDay(d) + ": " + diff + " linha(s) divergentes");
            }
        }
        progress.println("equivalencia: dias fechados de " + LocalDate.ofEpochDay(today - DAY_SLICES)
                + " a " + LocalDate.ofEpochDay(today - 1));

        Map<Long, List<Transaction>> byAccount = new HashMap<>();
        txs.forEach(t -> byAccount.computeIfAbsent(t.accountId(), k -> new ArrayList<>()).add(t));

        List<FeatureVector> oracleVectors = new ArrayList<>(accounts);
        List<String> shapeDetails = new ArrayList<>();
        List<String> ovDetails = new ArrayList<>();
        List<String> orDetails = new ArrayList<>();
        List<String> silentDetails = new ArrayList<>();
        List<String> todayDetails = new ArrayList<>();
        List<String> names = FeatureVector.names();
        if (names.size() != 95 || !names.get(0).equals("cnt_1m") || !names.get(94).equals("avg_92d")) {
            add(shapeDetails, "nomes fora da ordem da spec: " + names);
        }
        for (long acc = 1; acc <= accounts; acc++) {
            FeatureVector vo = oracle.read(acc, now);
            FeatureVector vv = valkey.read(acc, now);
            FeatureVector vr = ReferenceModel.compute(byAccount.getOrDefault(acc, List.of()), now, zone);
            oracleVectors.add(vo);
            if (vo.values().size() != 95 || vv.values().size() != 95) {
                add(shapeDetails, "conta " + acc + ": vetor sem 95 posições");
            }
            if (!vo.equals(vv)) {
                diff(acc, "oracle", vo, "valkey", vv).forEach(s -> add(ovDetails, s));
            }
            if (!vo.equals(vr)) {
                diff(acc, "oracle", vo, "referencia", vr).forEach(s -> add(orDetails, s));
            }
            int cls = EquivalenceData.accountClass(acc);
            for (int w = 0; w < WindowCatalog.SIZE; w++) {
                boolean minuteWindow = w <= LAST_MINUTE_WINDOW;
                boolean dayWindow = w >= WindowCatalog.FIRST_DAY_WINDOW;
                String name = "cnt_" + WindowCatalog.WINDOWS.get(w).suffix();
                if (cls == EquivalenceData.SILENT_LAST_HOUR && minuteWindow && (vo.agg(w).cnt() != 0 || vv.agg(w).cnt() != 0)) {
                    add(silentDetails, "conta " + acc + " " + name + ": oracle=" + vo.agg(w).cnt() + " valkey=" + vv.agg(w).cnt());
                }
                if (cls == EquivalenceData.TODAY_ONLY && dayWindow && (vo.agg(w).cnt() != 0 || vv.agg(w).cnt() != 0)) {
                    add(todayDetails, "conta " + acc + " " + name + ": oracle=" + vo.agg(w).cnt() + " valkey=" + vv.agg(w).cnt());
                }
            }
        }

        long before = om.countTx();
        progress.println("equivalencia: reaplicando a sequência no Oracle");
        ingestAll(txs, false);
        long after = om.countTx();
        List<String> idemDetails = new ArrayList<>();
        if (before != after) {
            add(idemDetails, "COUNT(*) de TX mudou de " + before + " para " + after);
        }
        for (long acc = 1; acc <= accounts; acc++) {
            FeatureVector again = oracle.read(acc, now);
            if (!again.equals(oracleVectors.get((int) acc - 1))) {
                diff(acc, "antes", oracleVectors.get((int) acc - 1), "depois", again).forEach(s -> add(idemDetails, s));
            }
        }

        return new Report(List.of(
                result("read devolve 95 valores na ordem da seção 3", shapeDetails),
                result("Oracle = Valkey nas 95 posições (" + accounts + " contas)", ovDetails),
                result("Oracle = modelo de referência", orDetails),
                result("sem transação na última hora: cnt = 0 nos minutos e em 1h", silentDetails),
                result("janelas de dias não incluem o dia corrente", todayDetails),
                result("Job diário = recálculo direto sobre TX", jobDetails),
                result("reaplicar a sequência no Oracle não altera os contadores", idemDetails)));
    }

    public static List<String> diff(long account, String labelA, FeatureVector a, String labelB, FeatureVector b) {
        List<String> out = new ArrayList<>();
        List<BigDecimal> va = a.values();
        List<BigDecimal> vb = b.values();
        List<String> names = FeatureVector.names();
        for (int i = 0; i < names.size(); i++) {
            if (!Objects.equals(va.get(i), vb.get(i))) {
                out.add("conta " + account + " " + names.get(i) + ": " + labelA + "=" + plain(va.get(i)) + " " + labelB
                        + "=" + plain(vb.get(i)));
            }
        }
        return out;
    }

    private void ingestAll(List<Transaction> txs, boolean bothStores) {
        int n = 0;
        for (Transaction t : txs) {
            oracle.ingest(t);
            if (bothStores) {
                valkey.ingest(t);
            }
            if (++n % 20_000 == 0) {
                progress.printf("equivalencia: %d/%d%n", n, txs.size());
            }
        }
    }

    private static String plain(BigDecimal v) {
        return v == null ? "null" : v.toPlainString();
    }

    private static void add(List<String> details, String s) {
        if (details.size() < MAX_DETAILS) {
            details.add(s);
        } else if (details.size() == MAX_DETAILS) {
            details.add("…");
        }
    }

    private static CheckResult result(String name, List<String> details) {
        return new CheckResult(name, details.isEmpty(), List.copyOf(details));
    }
}
```

- [ ] **Step 4: Rodar o teste do `diff`**

Run: `pwsh scripts/mvn.ps1 -q test -Dtest=EquivalenceCheckTest`
Expected: `BUILD SUCCESS`, 2 testes.

- [ ] **Step 5: Implementar `EquivalenceApp` e registrar no `Main`**

`src/main/java/sentinela/app/EquivalenceApp.java`:

```java
package sentinela.app;

import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import oracle.ucp.jdbc.PoolDataSource;
import sentinela.adapter.oracle.OracleDataSources;
import sentinela.adapter.oracle.OracleFeatureStore;
import sentinela.adapter.oracle.OracleMaintenance;
import sentinela.adapter.valkey.LuaScripts;
import sentinela.adapter.valkey.ValkeyConnections;
import sentinela.adapter.valkey.ValkeyFeatureStore;
import sentinela.adapter.valkey.ValkeyMaintenance;
import sentinela.infra.Args;
import sentinela.infra.Env;
import sentinela.infra.ExitCodes;
import sentinela.infra.UsageException;
import sentinela.verification.EquivalenceCheck;

/** Cenário C da spec funcional. Destrutivo: exige stores vazios ou --reset. */
public final class EquivalenceApp {
    static final Map<String, String> DEFAULTS = Args.defaults("accounts", "1000", "seed", "42", "now", null);

    private EquivalenceApp() {}

    public static int run(String[] argv) throws Exception {
        Args a = Args.parse(argv, DEFAULTS, Set.of("reset"));
        int accounts = a.positiveInt("accounts");
        if (accounts < 1000) {
            System.err.println("aviso: o critério de aceitação exige pelo menos 1.000 contas; rodando com " + accounts);
        }
        long seed = a.longValue("seed");
        Instant now;
        try {
            now = a.optional("now").map(Instant::parse).orElseGet(Instant::now).truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException e) {
            throw new UsageException("--now precisa ser ISO-8601, como 2026-10-29T15:07:30Z: " + a.string("now"));
        }
        Env env = Env.fromSystem();

        PoolDataSource ds = OracleDataSources.pool(env, "equivalencia", 2, 1000, OracleDataSources.NO_TIMEOUT);
        ValkeyConnections valkey = null;
        StatefulRedisConnection<String, String> bulk = null;
        try {
            valkey = ValkeyConnections.open(env);
            bulk = valkey.openBulk();
            LuaScripts scripts = new LuaScripts();
            EquivalenceCheck check = new EquivalenceCheck(
                    new OracleFeatureStore(ds, env.zone()), new OracleMaintenance(ds, env.zone()),
                    new ValkeyFeatureStore(valkey.hot(), scripts, env.zone()), new ValkeyMaintenance(bulk, scripts),
                    env.zone(), System.err);
            EquivalenceCheck.Report report = check.run(accounts, seed, now, a.flag("reset"));
            System.out.println("equivalencia em " + now + " (" + env.zone() + "), " + accounts + " contas, seed " + seed);
            for (EquivalenceCheck.CheckResult r : report.checks()) {
                System.out.println((r.passed() ? "PASS  " : "FAIL  ") + r.name());
                r.details().forEach(d -> System.out.println("      " + d));
            }
            return report.allPassed() ? ExitCodes.OK : ExitCodes.VERIFICATION_FAILED;
        } finally {
            if (bulk != null) {
                bulk.close();
            }
            if (valkey != null) {
                valkey.close();
            }
            OracleDataSources.close(ds);
        }
    }
}
```

Em `Main.java`, antes de `default`:

```java
                case "equivalencia" -> EquivalenceApp.run(rest);
```

- [ ] **Step 6: Escrever o teste de integração**

`src/test/java/sentinela/verification/EquivalenceIT.java`:

```java
package sentinela.verification;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.lettuce.core.api.StatefulRedisConnection;
import java.io.PrintStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;
import oracle.ucp.jdbc.PoolDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sentinela.adapter.oracle.OracleDataSources;
import sentinela.adapter.oracle.OracleFeatureStore;
import sentinela.adapter.oracle.OracleMaintenance;
import sentinela.adapter.valkey.LuaScripts;
import sentinela.adapter.valkey.ValkeyConnections;
import sentinela.adapter.valkey.ValkeyFeatureStore;
import sentinela.adapter.valkey.ValkeyMaintenance;
import sentinela.infra.Env;

/** Destrutivo. Cenário C com 1.000 contas; leva alguns minutos. */
@Tag("integration")
class EquivalenceIT {
    @Test
    void bothAdaptersAndReferenceAgree() {
        Env env = Env.fromSystem();
        assumeTrue(env.oracleUrl() != null && env.valkeyUrl() != null, "ORACLE_URL ou VALKEY_URL ausente");
        PoolDataSource ds = OracleDataSources.pool(env, "it-equivalencia", 2, 1000, OracleDataSources.NO_TIMEOUT);
        try (ValkeyConnections valkey = ValkeyConnections.open(env);
                StatefulRedisConnection<String, String> bulk = valkey.openBulk()) {
            LuaScripts scripts = new LuaScripts();
            EquivalenceCheck check = new EquivalenceCheck(
                    new OracleFeatureStore(ds, env.zone()), new OracleMaintenance(ds, env.zone()),
                    new ValkeyFeatureStore(valkey.hot(), scripts, env.zone()), new ValkeyMaintenance(bulk, scripts),
                    env.zone(), new PrintStream(System.err));
            EquivalenceCheck.Report report = check.run(1000, 42, Instant.now().truncatedTo(ChronoUnit.MICROS), true);
            String failures = report.checks().stream().filter(r -> !r.passed())
                    .map(r -> r.name() + "\n  " + String.join("\n  ", r.details())).collect(Collectors.joining("\n"));
            assertTrue(report.allPassed(), failures);
        } finally {
            OracleDataSources.close(ds);
        }
    }
}
```

- [ ] **Step 7: Rodar a suíte completa**

Run: `pwsh scripts/mvn.ps1 -q verify -Pintegration`
Expected: `BUILD SUCCESS` com todos os testes unitários e de integração, inclusive `EquivalenceIT` (alguns minutos).

- [ ] **Step 8: Rodar o programa `equivalencia`**

```powershell
podman build -t sentinela-poc .
pwsh scripts/poc.ps1 equivalencia
pwsh scripts/poc.ps1 equivalencia --reset
```

Expected: a primeira chamada recusa com `os stores não estão vazios; rode com --reset` e exit code 2; a segunda imprime sete linhas `PASS` e termina com exit code 0.

- [ ] **Step 9: Commit**

```powershell
git add src
git commit -m "feat(equivalencia): cenário C com Oracle, Valkey e modelo de referência"
```

---

### Task 21: Rodada de benchmark e aceitação

**Files:**
- Create: `scripts/rodada.ps1`
- Modify: `docs/specs/Especificação técnica — POC Feature Store de Fraude.md` (só se algo divergir na execução real: registre a divergência na seção 13)

**Interfaces:**
- Consumes: `scripts/poc.ps1` (Task 11); programas `gerador`, `backfill`, `ingestor`, `leitor`, `relatorio`.
- Produces: `scripts/rodada.ps1 -Mode <oracle|oracle+valkey> [-Run id] [-Tps 600] [-Duration 300] [-Warmup 30] [-Accounts 100000] [-Threads 8] [-Seed 42]`. Gera o dataset se `data/tx.ndjson` não existir, roda o Backfill, sobe Ingestor e Leitor em paralelo com o mesmo `--run`, espera os dois e roda o Relatório.

- [ ] **Step 1: Criar `scripts/rodada.ps1`**

```powershell
# Uma rodada de benchmark num modo: dataset (se faltar), backfill, ingestor + leitor em paralelo, relatório.
# O dataset é reaproveitado entre modos; apague data/tx.ndjson ao mudar --accounts ou --seed.
param(
    [Parameter(Mandatory = $true)][ValidateSet('oracle', 'oracle+valkey')][string] $Mode,
    [string] $Run = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss'),
    [int] $Tps = 600,
    [int] $Duration = 300,
    [int] $Warmup = 30,
    [int] $Accounts = 100000,
    [int] $Threads = 8,
    [int] $Seed = 42
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$poc = Join-Path $PSScriptRoot 'poc.ps1'
$envFile = Join-Path $root '.env.local'
$data = Join-Path $root 'data'
$logs = Join-Path $root 'logs'

function Invoke-Poc {
    & $poc @args
    if ($LASTEXITCODE -ne 0) { throw "programa '$($args[0])' falhou com código $LASTEXITCODE" }
}

if (-not (Test-Path (Join-Path $data 'tx.ndjson'))) {
    $count = [Math]::Max(200000, $Tps * ($Duration + 30))
    Invoke-Poc gerador --accounts $Accounts --seed $Seed --count $count
}
Invoke-Poc backfill --mode $Mode --accounts $Accounts --seed $Seed

$common = @('--mode', $Mode, '--tps', $Tps, '--duration', $Duration, '--warmup', $Warmup, '--threads', $Threads, '--run', $Run)
$ingestor = "sentinela-ingestor-$Run"
$leitor = "sentinela-leitor-$Run"
$mounts = @('-v', "${data}:/data", '-v', "${logs}:/logs")
podman run -d --name $ingestor --pod sentinela --env-file $envFile @mounts localhost/sentinela-poc ingestor @common | Out-Null
podman run -d --name $leitor --pod sentinela --env-file $envFile @mounts localhost/sentinela-poc leitor @common --accounts $Accounts --seed $Seed | Out-Null
Write-Host "rodada $Run ($Mode): ingestor e leitor em execução por $Duration s..."
$codes = @(podman wait $ingestor $leitor)
podman logs $ingestor
podman logs $leitor
podman rm $ingestor $leitor | Out-Null
if ($codes | Where-Object { $_ -ne '0' }) { throw "ingestor/leitor terminaram com códigos: $($codes -join ', ')" }

Invoke-Poc relatorio /logs
Write-Host "rodada $Run ($Mode) concluída; relatório em logs/report/report.md"
```

- [ ] **Step 2: Rodadas curtas nos dois modos**

```powershell
podman build -t sentinela-poc .
Remove-Item -Force -ErrorAction SilentlyContinue data/tx.ndjson
Remove-Item -Recurse -Force -ErrorAction SilentlyContinue logs/*
pwsh scripts/rodada.ps1 -Mode oracle -Run curta-oracle -Tps 100 -Duration 60 -Warmup 10 -Accounts 10000
pwsh scripts/rodada.ps1 -Mode oracle+valkey -Run curta-valkey -Tps 100 -Duration 60 -Warmup 10 -Accounts 10000
Get-Content logs/report/report.md
```

Expected: as duas rodadas terminam com `concluída`; `report.md` tem as tabelas `Execução curta-oracle`, `Execução curta-valkey` e `Comparação dos modos`, com `TPS ok = sim` e `Erro ok = sim` em todas as linhas.

- [ ] **Step 3: Rodada de aceitação (cenários A e B da spec funcional)**

```powershell
Remove-Item -Force data/tx.ndjson
Remove-Item -Recurse -Force logs/*
pwsh scripts/rodada.ps1 -Mode oracle -Run A-oracle
pwsh scripts/rodada.ps1 -Mode oracle+valkey -Run B-oracle-valkey
Get-Content logs/report/report.md
```

Expected: 100 mil contas, 92 dias de histórico, 600 TPS de ingestão e de leitura por 5 minutos em cada modo. O relatório tem a tabela lado a lado. Os critérios de carga da seção 10 da spec funcional são as colunas `TPS ok` (desvio < 5%) e `Erro ok` (erro < 0,1%). Se alguma linha der `NÃO`, não ajuste números: registre a linha e investigue a causa (`tps-series.csv` mostra quedas por segundo; `podman stats` mostra saturação de CPU).

- [ ] **Step 4: Cenário C depois das rodadas**

Run: `pwsh scripts/poc.ps1 equivalencia --reset`
Expected: sete `PASS`. O comando apaga os dados das rodadas; os logs em `logs/` ficam.

- [ ] **Step 5: Commit**

```powershell
git add scripts/rodada.ps1
git commit -m "build: script de rodada de benchmark"
```

Os arquivos de `logs/` e `data/` ficam fora do git (`.gitignore`). Copie `logs/report/report.md` para onde a decisão de arquitetura for registrada.

---

## Self-review do plano

- **Cobertura da spec técnica:** seção 4 → Tasks 2–4; seção 5 → Tasks 10, 12, 13; seção 6 → Tasks 14, 15; seção 7 → Task 16; seção 8 → Task 6; seção 9 → Tasks 7, 9, 10, 16–18, 20; seção 10 → Task 6; seção 11 → Tasks 1, 11, 21; seção 12 → testes de cada task e a matriz abaixo.
- **Matriz de aceitação da spec funcional:** 95 valores na ordem → `FeatureVectorTest`, `equivalencia`; `cnt = 0` sem transação na última hora → `equivalencia` (classe 1); dias sem o dia corrente → `WindowCatalogTest`, `equivalencia` (classe 2); cenário C em 1.000 contas → `EquivalenceIT`, `equivalencia`; idempotência por `tx_id` → `OracleFeatureStoreIT`, `equivalencia`; Job = recálculo → `OracleMaintenanceIT`, `equivalencia`; relatório com TPS e percentis → Tasks 8–9; TPS ±5% e erro < 0,1% → colunas do `report.md` na Task 21.
