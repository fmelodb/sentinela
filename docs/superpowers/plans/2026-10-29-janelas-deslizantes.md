# Janelas deslizantes ao segundo — plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Trocar a regra de alinhamento às fatias pela semântica decidida com o cliente: janelas de 1m a 72h deslizantes `(now − N, now]` ao microssegundo e janelas de dias com N−1 dias fechados mais o dia corrente até `now`, nos dois adapters, e refazer o benchmark comparativo.

**Architecture:** O domínio ganha `RollingWindow`, `DayWindow`, `Limits`, `DaySlice` e `WindowAggregator`, que convivem com os tipos antigos até a última tarefa de código, para o build ficar verde a cada commit. O Oracle continua com um statement por leitura (mais um agregado do dia corrente na varredura de 72h). O Valkey troca as fatias de minuto e hora por um sorted set por conta com as transações de 73h; o hash fica só com os dias fechados.

**Tech Stack:** Java 25, Maven (em container), JUnit 5, ojdbc17/UCP 23.26, Lettuce 6.6, Oracle 26ai, Valkey 7.2, podman.

**Spec:** `docs/specs/Especificação funcional — POC Feature Store de Fraude.md` (seção 3) e `docs/specs/Especificação técnica — POC Feature Store de Fraude.md` (seções 4, 5.3, 6, 9.8, 12, 13).

## Global Constraints

- Java 25, sem dependência nova no `pom.xml`.
- Valores monetários em centavos `long` no domínio e no Valkey; `NUMBER(14,2)` no Oracle; nenhum `double` exceto o score do sorted set (inteiro de microssegundos, exato até 2^53).
- Instantes em microssegundo: `Transaction` rejeita nanossegundos; `WindowCatalog.limits` trunca `now` com `truncatedTo(ChronoUnit.MICROS)`.
- Adapters nunca leem relógio; `now` vem do chamador.
- Comentários e mensagens em português; identificadores em inglês, como no código existente.
- Build e testes só pelo container: `pwsh -NoProfile scripts/mvn.ps1 …`. Argumentos `-D…` entre aspas simples no PowerShell.
- Testes de integração são destrutivos (esvaziam `TX`, `DAILY_AGG` e o Valkey); rodar antes do benchmark, nunca durante.
- Retenção do sorted set: 73 horas (`ts − 73h` descartável). Retenção de fatias de dia no Valkey: `today − 91`.

## Review Focus

- `now` com nanossegundos (relógio do Windows tem 100 ns): Oracle e Valkey precisam receber o mesmo limite truncado ao µs — teste em `WindowCatalogTest.limitsTruncateNowToMicros` (Tarefa 1).
- Leitura exatamente às 00:00 local: o dia corrente começa em `now`, e uma transação 1 µs antes pertence ao dia anterior — teste em `WindowCatalogTest.todayStartsAtLocalMidnight` (Tarefa 1).
- Transação com `ts` no futuro (ingestor um pouco adiantado em relação ao leitor): não entra em janela nenhuma e não descarta do Valkey uma transação ainda dentro de 72h — testes em `WindowAggregatorTest.futureIsIgnored` (Tarefa 1) e `ValkeyFeatureStoreIT.futureTransactionKeepsRetentionMargin` (Tarefa 4).
- Valkey com hash de uma versão anterior (campos `m:`/`h:`): a leitura deve falhar com erro claro, não devolver vetor errado — teste em `SliceCodecTest.rejectsMalformedDayFieldOrValue` (Tarefa 4).
- Conta quente com milhares de transações em 72h: o agregador precisa continuar exato — teste em `WindowAggregatorTest.manyTransactionsMatchDirectSums` (Tarefa 1).

## Arquivos

| Arquivo | Tarefa | Responsabilidade |
| --- | --- | --- |
| `domain/RollingWindow.java`, `DayWindow.java`, `DaySlice.java`, `Limits.java`, `WindowAggregator.java` (novos) | 1 | Definição e combinação das janelas |
| `domain/WindowCatalog.java`, `FeatureVector.java` | 1, 5 | Catálogo novo; o antigo sai na 5 |
| `verification/ReferenceModel.java`, `EquivalenceData.java`, `EquivalenceCheck.java` | 2 | Terceiro voto e cenário C com a regra nova |
| `adapter/oracle/OracleReadSql.java`, `OracleFeatureStore.java` | 3 | SQL de leitura com `Limits` |
| `adapter/valkey/*`, `resources/lua/ingest.lua`, `put_day.lua`, `domain/KeyedSlice.java`, `adapter/oracle/OracleMaintenance.java`, `app/BackfillApp.java` | 4 | Sorted set, leitura em uma ida, reconstrução |
| `domain/Unit.java`, `Window.java`, `Slice.java`, `SliceAggregator.java`, `Bounds.java` (removidos), `adapter/oracle/OracleTime.java`, `workload/BackfillGenerator.java`, `README.md` | 5 | Limpeza |

Caminhos de código relativos a `src/main/java/sentinela/` e de teste a `src/test/java/sentinela/`.

---

### Task 1: Domínio — catálogo deslizante, `Limits` e `WindowAggregator`

**Files:**
- Create: `domain/RollingWindow.java`, `domain/DayWindow.java`, `domain/DaySlice.java`, `domain/Limits.java`, `domain/WindowAggregator.java`
- Modify: `domain/WindowCatalog.java`, `domain/FeatureVector.java`
- Test: `domain/WindowCatalogTest.java` (acrescentar), `domain/WindowAggregatorTest.java` (novo)

**Interfaces:**
- Produces:
  - `record RollingWindow(String suffix, Duration span)`
  - `record DayWindow(String suffix, int n)`
  - `record DaySlice(long epochDay, WindowAgg agg)`
  - `record Limits(Instant now, List<Instant> rollingLo, long today, Instant todayStart, List<Long> closedLo)` com `inRolling(int i, Instant ts)`, `inToday(Instant ts)`, `inClosed(int j, long epochDay)`, `oldestRolling()`, `oldestClosed()`
  - `WindowCatalog.ROLLING` (15), `DAYS` (4), `SUFFIXES` (19), `SIZE` = 19, `FIRST_DAY_WINDOW` = 15, `dayStart(long epochDay, ZoneId)`, `limits(Instant now, ZoneId)`, `epochDay(Instant, ZoneId)` (já existe)
  - `WindowAggregator.aggregate(Iterable<Transaction> recent, Iterable<DaySlice> days, Limits l) → FeatureVector`

- [ ] **Step 1: Testes que falham**

Acrescentar a `domain/WindowCatalogTest.java` (os testes antigos ficam até a Tarefa 5):

```java
    private static final Instant NOW = Instant.parse("2026-10-29T20:23:40.123456Z"); // 17:23:40 em São Paulo

    @Test
    void suffixesFollowSpecOrder() {
        assertEquals(List.of("1m", "5m", "10m", "15m", "30m", "1h", "2h", "3h", "5h", "6h", "9h", "12h",
                "24h", "48h", "72h", "7d", "31d", "62d", "92d"), WindowCatalog.SUFFIXES);
        assertEquals(15, WindowCatalog.ROLLING.size());
        assertEquals(4, WindowCatalog.DAYS.size());
        assertEquals("7d", WindowCatalog.SUFFIXES.get(WindowCatalog.FIRST_DAY_WINDOW));
        assertEquals(new RollingWindow("1h", Duration.ofHours(1)), WindowCatalog.ROLLING.get(5));
        assertEquals(new DayWindow("92d", 92), WindowCatalog.DAYS.get(3));
    }

    @Test
    void rollingLimitsAreExactlySpanBeforeNow() {
        Limits l = WindowCatalog.limits(NOW, SP);
        assertEquals(NOW, l.now());
        assertEquals(Instant.parse("2026-10-29T20:22:40.123456Z"), l.rollingLo().get(0), "1m");
        assertEquals(Instant.parse("2026-10-29T20:18:40.123456Z"), l.rollingLo().get(1), "5m");
        assertEquals(Instant.parse("2026-10-29T19:23:40.123456Z"), l.rollingLo().get(5), "1h");
        assertEquals(Instant.parse("2026-10-29T18:23:40.123456Z"), l.rollingLo().get(6), "2h");
        assertEquals(Instant.parse("2026-10-28T20:23:40.123456Z"), l.rollingLo().get(12), "24h");
        assertEquals(Instant.parse("2026-10-26T20:23:40.123456Z"), l.rollingLo().get(14), "72h");
        assertEquals(l.rollingLo().get(14), l.oldestRolling());
    }

    @Test
    void dayLimitsAreNMinusOneClosedDaysPlusToday() {
        Limits l = WindowCatalog.limits(NOW, SP);
        assertEquals(LocalDate.of(2026, 10, 29).toEpochDay(), l.today());
        assertEquals(Instant.parse("2026-10-29T03:00:00Z"), l.todayStart());
        assertEquals(List.of(LocalDate.of(2026, 10, 23).toEpochDay(), LocalDate.of(2026, 9, 29).toEpochDay(),
                LocalDate.of(2026, 8, 29).toEpochDay(), LocalDate.of(2026, 7, 30).toEpochDay()), l.closedLo());
        assertEquals(LocalDate.of(2026, 7, 30).toEpochDay(), l.oldestClosed());
    }

    @Test
    void limitsTruncateNowToMicros() {
        Limits l = WindowCatalog.limits(Instant.parse("2026-10-29T20:23:40.123456789Z"), SP);
        assertEquals(NOW, l.now());
        assertEquals(Instant.parse("2026-10-29T20:18:40.123456Z"), l.rollingLo().get(1));
    }

    @Test
    void todayStartsAtLocalMidnight() {
        Instant midnight = Instant.parse("2026-10-29T03:00:00Z");
        Limits l = WindowCatalog.limits(midnight, SP);
        assertEquals(midnight, l.todayStart());
        assertTrue(l.inToday(midnight));
        assertFalse(l.inToday(midnight.minusNanos(1_000)), "23:59:59.999999 de ontem");
        assertEquals(LocalDate.of(2026, 10, 28).toEpochDay(),
                WindowCatalog.limits(midnight.minusNanos(1_000), SP).today());
    }

    @Test
    void dayStartIsLocalMidnightInUtc() {
        assertEquals(Instant.parse("2026-10-29T03:00:00Z"),
                WindowCatalog.dayStart(LocalDate.of(2026, 10, 29).toEpochDay(), SP));
    }
```

Imports a acrescentar: `java.time.Duration`.

Criar `domain/WindowAggregatorTest.java`:

```java
package sentinela.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class WindowAggregatorTest {
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Instant NOW = Instant.parse("2026-10-29T20:23:40.123456Z"); // 17:23:40 em São Paulo
    private static final Limits L = WindowCatalog.limits(NOW, SP);
    private static final long T = L.today();

    private static long ids;

    private static Transaction tx(Instant ts, long cents) {
        return new Transaction(++ids, 1, ts, cents);
    }

    private static FeatureVector agg(List<Transaction> txs, List<DaySlice> days) {
        return WindowAggregator.aggregate(txs, days, L);
    }

    @Test
    void nothingGivesEmptyVector() {
        assertEquals(FeatureVector.empty(), agg(List.of(), List.of()));
    }

    @Test
    void lowerBoundIsExclusiveAndUpperInclusive() {
        Instant lo5m = NOW.minus(5, ChronoUnit.MINUTES);
        FeatureVector v = agg(List.of(tx(lo5m, 100), tx(lo5m.plusNanos(1_000), 200), tx(NOW, 300)), List.of());
        assertEquals(WindowAgg.of(300), v.agg(0), "1m: só now");
        assertEquals(new WindowAgg(2, 500, 200L, 300L), v.agg(1), "5m: exatamente now − 5m fica fora");
        assertEquals(new WindowAgg(3, 600, 100L, 300L), v.agg(2), "10m");
    }

    @Test
    void twoHoursCoverExactlyTwoHours() {
        FeatureVector v = agg(List.of(tx(NOW.minus(Duration.ofHours(2)).plusSeconds(1), 100),
                tx(NOW.minus(Duration.ofHours(2)).minusSeconds(1), 200)), List.of());
        assertEquals(WindowAgg.of(100), v.agg(6), "2h");
        assertEquals(new WindowAgg(2, 300, 100L, 200L), v.agg(7), "3h");
    }

    @Test
    void futureIsIgnored() {
        Instant future = NOW.plusNanos(1_000);
        FeatureVector v = agg(List.of(tx(future, 100)), List.of(new DaySlice(T + 1, WindowAgg.of(100))));
        assertEquals(FeatureVector.empty(), v);
    }

    @Test
    void dayWindowsAddTodayToClosedDays() {
        Instant tenAm = L.todayStart().plus(10, ChronoUnit.HOURS);
        FeatureVector v = agg(List.of(tx(tenAm, 1000), tx(L.todayStart(), 10), tx(L.todayStart().minusNanos(1_000), 7)),
                List.of(new DaySlice(T - 1, new WindowAgg(2, 500, 100L, 400L)),
                        new DaySlice(T - 6, WindowAgg.of(50)),
                        new DaySlice(T - 7, WindowAgg.of(60))));
        assertEquals(new WindowAgg(5, 1560, 10L, 1000L), v.agg(15), "7d: T−6..T−1 e hoje desde 00:00");
        assertEquals(new WindowAgg(6, 1620, 10L, 1000L), v.agg(16), "31d inclui T−7");
        assertEquals(new WindowAgg(3, 1017, 7L, 1000L), v.agg(12), "24h vem das transações, inclusive a de ontem");
    }

    @Test
    void daySlicesOutsideClosedRangeAreIgnored() {
        FeatureVector v = agg(List.of(), List.of(new DaySlice(T, WindowAgg.of(100)),
                new DaySlice(T - 91, WindowAgg.of(200)), new DaySlice(T - 92, WindowAgg.of(300))));
        assertEquals(WindowAgg.EMPTY, v.agg(15), "fatia de hoje não conta: hoje vem das transações");
        assertEquals(WindowAgg.of(200), v.agg(18), "92d vai até T−91");
    }

    @Test
    void manyTransactionsMatchDirectSums() {
        SplittableRandom rnd = new SplittableRandom(7);
        List<Transaction> txs = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            txs.add(tx(NOW.minus(rnd.nextLong(Duration.ofHours(80).toNanos() / 1_000), ChronoUnit.MICROS),
                    1 + rnd.nextLong(1_000_000)));
        }
        FeatureVector v = agg(txs, List.of());
        for (int i = 0; i < WindowCatalog.ROLLING.size(); i++) {
            int w = i;
            WindowAgg expected = txs.stream().filter(t -> L.inRolling(w, t.ts()))
                    .map(t -> WindowAgg.of(t.amountCents())).reduce(WindowAgg.EMPTY, WindowAgg::plus);
            assertEquals(expected, v.agg(i), WindowCatalog.SUFFIXES.get(i));
        }
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `pwsh -NoProfile scripts/mvn.ps1 -q test '-Dtest=WindowCatalogTest,WindowAggregatorTest'`
Expected: falha de compilação (`RollingWindow`, `Limits`, `WindowAggregator` não existem).

- [ ] **Step 3: Implementar**

`domain/RollingWindow.java`:

```java
package sentinela.domain;

import java.time.Duration;

/** Janela deslizante ("5m", "24h"): transações com now − span < ts ≤ now. */
public record RollingWindow(String suffix, Duration span) {}
```

`domain/DayWindow.java`:

```java
package sentinela.domain;

/** Janela de dias ("7d"): os n − 1 dias fechados anteriores a hoje mais o dia corrente até now. */
public record DayWindow(String suffix, int n) {}
```

`domain/DaySlice.java`:

```java
package sentinela.domain;

/** Agregado de um dia civil fechado em FS_ZONE, pelo epoch day. */
public record DaySlice(long epochDay, WindowAgg agg) {}
```

`domain/Limits.java`:

```java
package sentinela.domain;

import java.time.Instant;
import java.util.List;

/**
 * Limites de uma leitura em now (spec técnica 4.2). Janela deslizante i: rollingLo[i] < ts ≤ now.
 * Dia corrente: todayStart ≤ ts ≤ now. Janela de dias j: dias fechados de closedLo[j] a today − 1, mais o dia corrente.
 */
public record Limits(Instant now, List<Instant> rollingLo, long today, Instant todayStart, List<Long> closedLo) {
    public Limits {
        rollingLo = List.copyOf(rollingLo);
        closedLo = List.copyOf(closedLo);
    }

    public boolean inRolling(int i, Instant ts) {
        return ts.isAfter(rollingLo.get(i)) && !ts.isAfter(now);
    }

    public boolean inToday(Instant ts) {
        return !ts.isBefore(todayStart) && !ts.isAfter(now);
    }

    public boolean inClosed(int j, long epochDay) {
        return epochDay >= closedLo.get(j) && epochDay < today;
    }

    /** Limite inferior da maior janela deslizante (72h): a faixa de transações que a leitura busca. */
    public Instant oldestRolling() {
        return rollingLo.get(rollingLo.size() - 1);
    }

    /** Primeiro dia fechado da maior janela de dias (92d). */
    public long oldestClosed() {
        return closedLo.get(closedLo.size() - 1);
    }
}
```

`domain/WindowAggregator.java`:

```java
package sentinela.domain;

import java.util.Arrays;

/**
 * Combina as transações recentes e as fatias de dias fechados nas 19 janelas (spec técnica 4.4).
 * Transações e fatias fora de qualquer limite, inclusive futuras, são ignoradas.
 */
public final class WindowAggregator {
    private WindowAggregator() {}

    public static FeatureVector aggregate(Iterable<Transaction> recent, Iterable<DaySlice> days, Limits l) {
        int rolling = WindowCatalog.ROLLING.size();
        WindowAgg[] acc = new WindowAgg[WindowCatalog.SIZE];
        Arrays.fill(acc, WindowAgg.EMPTY);
        WindowAgg today = WindowAgg.EMPTY;
        for (Transaction t : recent) {
            WindowAgg one = WindowAgg.of(t.amountCents());
            for (int i = 0; i < rolling; i++) {
                if (l.inRolling(i, t.ts())) {
                    acc[i] = acc[i].plus(one);
                }
            }
            if (l.inToday(t.ts())) {
                today = today.plus(one);
            }
        }
        for (DaySlice d : days) {
            for (int j = 0; j < WindowCatalog.DAYS.size(); j++) {
                if (l.inClosed(j, d.epochDay())) {
                    acc[rolling + j] = acc[rolling + j].plus(d.agg());
                }
            }
        }
        for (int j = 0; j < WindowCatalog.DAYS.size(); j++) {
            acc[rolling + j] = acc[rolling + j].plus(today);
        }
        return new FeatureVector(Arrays.asList(acc));
    }
}
```

Em `domain/WindowCatalog.java`, acrescentar (mantendo `WINDOWS`, `bounds` e os métodos de fatia até a Tarefa 5) e trocar `SIZE`/`FIRST_DAY_WINDOW` para derivarem do catálogo novo:

```java
    public static final List<RollingWindow> ROLLING = List.of(
            new RollingWindow("1m", Duration.ofMinutes(1)),
            new RollingWindow("5m", Duration.ofMinutes(5)),
            new RollingWindow("10m", Duration.ofMinutes(10)),
            new RollingWindow("15m", Duration.ofMinutes(15)),
            new RollingWindow("30m", Duration.ofMinutes(30)),
            new RollingWindow("1h", Duration.ofHours(1)),
            new RollingWindow("2h", Duration.ofHours(2)),
            new RollingWindow("3h", Duration.ofHours(3)),
            new RollingWindow("5h", Duration.ofHours(5)),
            new RollingWindow("6h", Duration.ofHours(6)),
            new RollingWindow("9h", Duration.ofHours(9)),
            new RollingWindow("12h", Duration.ofHours(12)),
            new RollingWindow("24h", Duration.ofHours(24)),
            new RollingWindow("48h", Duration.ofHours(48)),
            new RollingWindow("72h", Duration.ofHours(72)));

    public static final List<DayWindow> DAYS = List.of(
            new DayWindow("7d", 7),
            new DayWindow("31d", 31),
            new DayWindow("62d", 62),
            new DayWindow("92d", 92));

    public static final List<String> SUFFIXES = Stream.concat(
            ROLLING.stream().map(RollingWindow::suffix), DAYS.stream().map(DayWindow::suffix)).toList();

    public static final int SIZE = SUFFIXES.size();
    public static final int FIRST_DAY_WINDOW = ROLLING.size();

    public static Instant dayStart(long epochDay, ZoneId zone) {
        return LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant();
    }

    public static Limits limits(Instant now, ZoneId zone) {
        Instant n = now.truncatedTo(ChronoUnit.MICROS);
        long today = epochDay(n, zone);
        return new Limits(n, ROLLING.stream().map(w -> n.minus(w.span())).toList(), today, dayStart(today, zone),
                DAYS.stream().map(w -> today - (w.n() - 1)).toList());
    }
```

Os campos `ROLLING`, `DAYS` e `SUFFIXES` ficam antes de `SIZE` (ordem de inicialização estática). Imports: `java.time.Duration`, `java.time.temporal.ChronoUnit`, `java.util.stream.Stream`. Javadoc da classe passa a citar a seção 3 da funcional com a regra nova.

Em `domain/FeatureVector.java`, `buildNames()` passa a iterar `WindowCatalog.SUFFIXES`:

```java
    private static List<String> buildNames() {
        List<String> names = new ArrayList<>();
        for (String suffix : WindowCatalog.SUFFIXES) {
            for (String m : METRICS) {
                names.add(m + "_" + suffix);
            }
        }
        return List.copyOf(names);
    }
```

- [ ] **Step 4: Rodar e ver passar**

Run: `pwsh -NoProfile scripts/mvn.ps1 -q test`
Expected: todos os testes unitários passam (os antigos continuam válidos).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sentinela/domain src/test/java/sentinela/domain
git commit -m "feat(domain): janelas deslizantes, limites ao microssegundo e combinação com o dia corrente"
```

---

### Task 2: Verificação — modelo de referência e cenário C com a regra nova

**Files:**
- Modify: `verification/ReferenceModel.java`, `verification/EquivalenceData.java`, `verification/EquivalenceCheck.java`
- Test: `verification/ReferenceModelTest.java`, `verification/EquivalenceDataTest.java`

**Interfaces:**
- Consumes: `WindowCatalog.ROLLING`, `DAYS`, `SUFFIXES`, `limits`, `dayStart`, `epochDay`; `WindowAggregator.aggregate`; `Limits`; `DaySlice`.
- Produces: `ReferenceModel.compute(List<Transaction>, Instant now, ZoneId) → FeatureVector` (assinatura igual); `EquivalenceData.boundaries(Instant now, ZoneId) → List<Instant>` agora inclui `now + 1 µs`.

- [ ] **Step 1: Testes que falham**

Substituir `verification/ReferenceModelTest.java`:

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
import sentinela.domain.DaySlice;
import sentinela.domain.FeatureVector;
import sentinela.domain.Transaction;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowAggregator;
import sentinela.domain.WindowCatalog;

class ReferenceModelTest {
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Instant NOW = Instant.parse("2026-10-29T20:23:40Z"); // 17:23:40 em São Paulo

    @Test
    void emptyHistoryGivesEmptyVector() {
        assertEquals(FeatureVector.empty(), ReferenceModel.compute(List.of(), NOW, SP));
    }

    @Test
    void handPickedTransactions() {
        List<Transaction> txs = List.of(
                new Transaction(1, 1, NOW.minusSeconds(10), 1000),
                new Transaction(2, 1, NOW.minus(2, ChronoUnit.HOURS), 2000), // exatamente no limite da 2h
                new Transaction(3, 1, Instant.parse("2026-10-29T02:59:59.999999Z"), 500), // 23:59:59 de 28/10 em SP
                new Transaction(4, 1, Instant.parse("2026-10-29T03:00:00Z"), 700)); // 00:00 de 29/10 em SP
        FeatureVector v = ReferenceModel.compute(txs, NOW, SP);
        assertEquals(WindowAgg.of(1000), v.agg(0), "1m");
        assertEquals(WindowAgg.of(1000), v.agg(6), "2h exclui exatamente now − 2h");
        assertEquals(new WindowAgg(2, 3000, 1000L, 2000L), v.agg(7), "3h");
        assertEquals(4, v.agg(12).cnt(), "24h");
        assertEquals(new WindowAgg(4, 4200, 500L, 2000L), v.agg(15), "7d: 28/10 fechado e hoje");
    }

    /** O domínio (limits + WindowAggregator) e a referência precisam concordar em dados aleatórios. */
    @Test
    void agreesWithDomainAggregation() {
        Instant now = Instant.parse("2026-10-29T20:23:40.123456Z");
        List<Transaction> txs = EquivalenceData.generate(400, 7, now, SP);
        Map<Long, List<Transaction>> byAccount = new HashMap<>();
        txs.forEach(t -> byAccount.computeIfAbsent(t.accountId(), k -> new ArrayList<>()).add(t));
        for (Map.Entry<Long, List<Transaction>> e : byAccount.entrySet()) {
            FeatureVector expected = ReferenceModel.compute(e.getValue(), now, SP);
            FeatureVector actual = WindowAggregator.aggregate(e.getValue(), daySlices(e.getValue()),
                    WindowCatalog.limits(now, SP));
            assertEquals(expected, actual, "conta " + e.getKey());
        }
    }

    private static List<DaySlice> daySlices(List<Transaction> txs) {
        Map<Long, WindowAgg> m = new HashMap<>();
        txs.forEach(t -> m.merge(WindowCatalog.epochDay(t.ts(), SP), WindowAgg.of(t.amountCents()), WindowAgg::plus));
        return m.entrySet().stream().map(e -> new DaySlice(e.getKey(), e.getValue())).toList();
    }
}
```

Substituir `verification/EquivalenceDataTest.java`:

```java
package sentinela.verification;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import sentinela.domain.Transaction;
import sentinela.domain.WindowCatalog;

class EquivalenceDataTest {
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Instant NOW = Instant.parse("2026-10-29T15:07:30.123456Z");

    private final List<Transaction> txs = EquivalenceData.generate(200, 42, NOW, SP);

    @Test
    void isDeterministicSortedAndOnlyBoundariesReachTheFuture() {
        assertEquals(txs, EquivalenceData.generate(200, 42, NOW, SP));
        for (int i = 1; i < txs.size(); i++) {
            assertFalse(txs.get(i).ts().isBefore(txs.get(i - 1).ts()), "ordem por ts");
        }
        List<Transaction> future = txs.stream().filter(t -> t.ts().isAfter(NOW)).toList();
        assertFalse(future.isEmpty(), "a classe de fronteiras tem uma transação 1 µs depois de now");
        assertTrue(future.stream().allMatch(t -> EquivalenceData.accountClass(t.accountId()) == EquivalenceData.BOUNDARIES
                && t.ts().equals(NOW.plusNanos(1_000))));
        assertTrue(txs.stream().allMatch(t -> t.accountId() >= 1 && t.accountId() <= 200));
    }

    @Test
    void silentAccountsHaveNothingInTheLastHour() {
        Instant hourAgo = NOW.minus(1, ChronoUnit.HOURS);
        List<Transaction> silent = txs.stream().filter(t -> EquivalenceData.accountClass(t.accountId()) == 1).toList();
        assertFalse(silent.isEmpty());
        assertTrue(silent.stream().noneMatch(t -> t.ts().isAfter(hourAgo)));
    }

    @Test
    void todayOnlyAccountsTransactOnlyTodayUntilNow() {
        long today = WindowCatalog.epochDay(NOW, SP);
        List<Transaction> todayOnly = txs.stream().filter(t -> EquivalenceData.accountClass(t.accountId()) == 2).toList();
        assertFalse(todayOnly.isEmpty());
        assertTrue(todayOnly.stream().allMatch(t -> WindowCatalog.epochDay(t.ts(), SP) == today && !t.ts().isAfter(NOW)));
    }

    @Test
    void boundaryAccountsHitEveryLimitOnBothSides() {
        List<Instant> b = EquivalenceData.boundaries(NOW, SP);
        Instant lo5m = NOW.minus(5, ChronoUnit.MINUTES);
        assertTrue(b.contains(lo5m));
        assertTrue(b.contains(lo5m.plusNanos(1_000)));
        assertTrue(b.contains(NOW));
        assertTrue(b.contains(NOW.plusNanos(1_000)));
        assertTrue(b.contains(Instant.parse("2026-10-29T03:00:00Z")), "00:00 local de hoje");
        assertTrue(b.contains(Instant.parse("2026-10-29T02:59:59.999999Z")));
        assertTrue(b.contains(Instant.parse("2026-10-23T03:00:00Z")), "início do primeiro dia fechado da 7d");
        assertTrue(b.contains(Instant.parse("2026-10-29T00:00:00Z")), "00:00 UTC");
        List<Instant> ofAccount3 = txs.stream().filter(t -> t.accountId() == 3).map(Transaction::ts).toList();
        assertTrue(ofAccount3.containsAll(b));
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `pwsh -NoProfile scripts/mvn.ps1 -q test '-Dtest=ReferenceModelTest,EquivalenceDataTest'`
Expected: FAIL — a referência ainda alinha às fatias (`handPickedTransactions`, `agreesWithDomainAggregation`), e `boundaries` não tem `now + 1 µs`.

- [ ] **Step 3: Implementar**

`verification/ReferenceModel.java`:

```java
package sentinela.verification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import sentinela.domain.DayWindow;
import sentinela.domain.FeatureVector;
import sentinela.domain.RollingWindow;
import sentinela.domain.Transaction;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowCatalog;

/**
 * Cálculo direto das 19 janelas sobre a lista de transações, pela seção 3 da funcional, sem fatias,
 * sem WindowCatalog.limits e sem WindowAggregator. Serve de terceiro voto: se Oracle e Valkey errarem
 * do mesmo jeito, a referência discorda.
 */
public final class ReferenceModel {
    private ReferenceModel() {}

    public static FeatureVector compute(List<Transaction> txs, Instant now, ZoneId zone) {
        LocalDate today = LocalDate.ofInstant(now, zone);
        List<WindowAgg> aggs = new ArrayList<>(WindowCatalog.SIZE);
        for (RollingWindow w : WindowCatalog.ROLLING) {
            Instant from = now.minus(w.span());
            aggs.add(sum(txs, ts -> ts.isAfter(from) && !ts.isAfter(now)));
        }
        for (DayWindow w : WindowCatalog.DAYS) {
            Instant from = today.minusDays(w.n() - 1).atStartOfDay(zone).toInstant();
            aggs.add(sum(txs, ts -> !ts.isBefore(from) && !ts.isAfter(now)));
        }
        return new FeatureVector(aggs);
    }

    private static WindowAgg sum(List<Transaction> txs, Predicate<Instant> in) {
        WindowAgg acc = WindowAgg.EMPTY;
        for (Transaction t : txs) {
            if (in.test(t.ts())) {
                acc = acc.plus(WindowAgg.of(t.amountCents()));
            }
        }
        return acc;
    }
}
```

Em `verification/EquivalenceData.java`:
- Em `generate`, trocar `lastHourStart` e `todayStart` por:

```java
        Instant hourAgo = now.minus(1, ChronoUnit.HOURS);
        Instant todayStart = WindowCatalog.dayStart(WindowCatalog.epochDay(now, zone), zone);
```

  e a classe silenciosa por `addUniform(times, rnd, rnd.nextInt(301), start, hourAgo, true)` (até `now − 1h`, inclusive: o limite exato fica fora da 1h).
- Substituir `boundaries`:

```java
    /**
     * Os dois lados de cada limite da seção 3: now − span (fora) e 1 µs depois (dentro); now (dentro) e
     * 1 µs depois (futuro, fora); 00:00 local de hoje e de cada primeiro dia fechado, e 1 µs antes; 00:00 UTC.
     */
    public static List<Instant> boundaries(Instant now, ZoneId zone) {
        Limits l = WindowCatalog.limits(now, zone);
        Set<Instant> s = new LinkedHashSet<>();
        for (Instant lo : l.rollingLo()) {
            s.add(lo);
            s.add(lo.plusNanos(1_000));
        }
        s.add(l.now());
        s.add(l.now().plusNanos(1_000));
        List<Instant> midnights = new ArrayList<>();
        midnights.add(l.todayStart());
        l.closedLo().forEach(d -> midnights.add(WindowCatalog.dayStart(d, zone)));
        midnights.add(now.truncatedTo(ChronoUnit.DAYS));
        for (Instant t : midnights) {
            s.add(t);
            s.add(t.minusNanos(1_000));
        }
        return List.copyOf(s);
    }
```

  Imports: tirar `Bounds` e `Unit`, acrescentar `sentinela.domain.Limits`. O javadoc da classe continua (93 dias, quatro classes).

Em `verification/EquivalenceCheck.java`:
- `"cnt_" + WindowCatalog.WINDOWS.get(w).suffix()` → `"cnt_" + WindowCatalog.SUFFIXES.get(w)`.
- Verificação da classe 2 passa a exigir o dia corrente nas janelas de dias:

```java
            long accountTxs = byAccount.getOrDefault(acc, List.of()).size();
```

  (declarado antes do laço das janelas) e, no laço:

```java
                if (cls == EquivalenceData.TODAY_ONLY && dayWindow
                        && (vo.agg(w).cnt() != accountTxs || vv.agg(w).cnt() != accountTxs)) {
                    add(todayDetails, "conta " + acc + " " + name + ": esperado=" + accountTxs + " oracle="
                            + vo.agg(w).cnt() + " valkey=" + vv.agg(w).cnt());
                }
```

- Nome do resultado: `"janelas de dias incluem o dia corrente até now"`.

- [ ] **Step 4: Rodar e ver passar**

Run: `pwsh -NoProfile scripts/mvn.ps1 -q test`
Expected: PASS em todos os unitários.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sentinela/verification src/test/java/sentinela/verification
git commit -m "feat(verificacao): referência e cenário C com janelas deslizantes e dia corrente"
```

---

### Task 3: Adapter Oracle — leitura com `Limits`

**Files:**
- Modify: `adapter/oracle/OracleReadSql.java`, `adapter/oracle/OracleFeatureStore.java`
- Test: `adapter/oracle/OracleReadSqlTest.java`, `adapter/oracle/OracleFeatureStoreIT.java`

**Interfaces:**
- Consumes: `WindowCatalog.limits`, `ROLLING`, `DAYS`, `SUFFIXES`, `SIZE`; `Limits`.
- Produces: `OracleReadSql.binds(long accountId, Limits l) → Object[26]`; `OracleReadSql.SQL` com 80 colunas; `OracleFeatureStore.READ_SQL_PREFIX` sem mudança.

- [ ] **Step 1: Testes que falham**

Substituir `adapter/oracle/OracleReadSqlTest.java`:

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
    private static final Instant NOW = Instant.parse("2026-10-29T20:23:40.123456Z"); // 17:23:40 em São Paulo

    @Test
    void placeholderCountMatchesBinds() {
        long placeholders = OracleReadSql.SQL.chars().filter(c -> c == '?').count();
        Object[] binds = OracleReadSql.binds(42, WindowCatalog.limits(NOW, SP));
        assertEquals(26, binds.length);
        assertEquals(binds.length, placeholders);
    }

    @Test
    void selectsFourColumnsPerPartInOrder() {
        String sql = OracleReadSql.SQL;
        assertEquals(20, sql.split(" AS cnt_", -1).length - 1, "15 deslizantes, hoje e 4 de dias");
        assertTrue(sql.indexOf("AS cnt_1m") < sql.indexOf("AS max_72h"));
        assertTrue(sql.indexOf("AS max_72h") < sql.indexOf("AS cnt_hoje"));
        assertTrue(sql.indexOf("AS max_hoje") < sql.indexOf("AS cnt_7d"));
        assertTrue(sql.contains("AS max_92d"));
        assertTrue(sql.startsWith("WITH b AS (SELECT ? AS lo_1m"));
        assertTrue(sql.contains("x.ts > b.lo_5m"), "limite inferior exclusivo");
        assertTrue(sql.contains("x.ts >= b.lo_hoje"), "dia corrente desde 00:00, inclusivo");
        assertTrue(sql.contains("x.account_id = ? AND x.ts > ? AND x.ts <= ?"), "faixa (now − 72h, now]");
        assertTrue(sql.contains("a.account_id = ? AND a.dia >= ? AND a.dia < ?"));
    }

    @Test
    void bindsFollowLimits() {
        Object[] b = OracleReadSql.binds(42, WindowCatalog.limits(NOW, SP));
        assertEquals(LocalDateTime.parse("2026-10-29T20:22:40.123456"), b[0], "lo_1m");
        assertEquals(LocalDateTime.parse("2026-10-29T20:18:40.123456"), b[1], "lo_5m");
        assertEquals(LocalDateTime.parse("2026-10-29T19:23:40.123456"), b[5], "lo_1h");
        assertEquals(LocalDateTime.parse("2026-10-29T18:23:40.123456"), b[6], "lo_2h");
        assertEquals(LocalDateTime.parse("2026-10-26T20:23:40.123456"), b[14], "lo_72h");
        assertEquals(LocalDateTime.parse("2026-10-29T03:00:00"), b[15], "lo_hoje = 00:00 local em UTC");
        assertEquals(LocalDate.parse("2026-10-23"), b[16], "lo_7d");
        assertEquals(LocalDate.parse("2026-07-30"), b[19], "lo_92d");
        assertEquals(42L, b[20]);
        assertEquals(b[14], b[21], "faixa de TX começa em lo_72h, exclusivo");
        assertEquals(LocalDateTime.parse("2026-10-29T20:23:40.123456"), b[22], "now, inclusivo");
        assertEquals(42L, b[23]);
        assertEquals(LocalDate.parse("2026-07-30"), b[24]);
        assertEquals(LocalDate.parse("2026-10-29"), b[25], "hoje, exclusivo");
    }
}
```

Em `adapter/oracle/OracleFeatureStoreIT.java`, substituir os dois testes de leitura (mantendo `unknownAccountReadsEmptyVector` e `insertDaily`):

```java
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void readsRollingWindowsAndIgnoresDuplicates() {
        Instant now = now();
        Transaction t1 = new Transaction(9_900_000_001L, ACC, now.minusSeconds(10), 1000);
        Transaction t2 = new Transaction(9_900_000_002L, ACC, now.minus(2, ChronoUnit.HOURS).plusSeconds(1), 2000);
        Transaction t3 = new Transaction(9_900_000_003L, ACC, now.minus(30, ChronoUnit.HOURS), 3000);
        store.ingest(t1);
        store.ingest(t2);
        store.ingest(t3);
        long before = store.duplicates();
        store.ingest(t1);
        assertEquals(before + 1, store.duplicates());

        FeatureVector v = store.read(ACC, now);
        assertEquals(WindowAgg.of(1000), v.agg(0), "1m");
        assertEquals(WindowAgg.of(1000), v.agg(5), "1h");
        assertEquals(new WindowAgg(2, 3000, 1000L, 2000L), v.agg(6), "2h = exatamente as últimas 2 horas");
        assertEquals(new WindowAgg(2, 3000, 1000L, 2000L), v.agg(12), "24h");
        assertEquals(new WindowAgg(3, 6000, 1000L, 3000L), v.agg(13), "48h");
        long today = WindowCatalog.epochDay(now, env.zone());
        WindowAgg todays = List.of(t1, t2, t3).stream().filter(t -> WindowCatalog.epochDay(t.ts(), env.zone()) == today)
                .map(t -> WindowAgg.of(t.amountCents())).reduce(WindowAgg.EMPTY, WindowAgg::plus);
        assertEquals(todays, v.agg(15), "7d sem DAILY_AGG = só o dia corrente");
    }

    @Test
    void rollingLowerBoundIsExclusiveAndUpperInclusive() {
        Instant now = now();
        Instant lo5m = now.minus(5, ChronoUnit.MINUTES);
        store.ingest(new Transaction(9_900_000_011L, ACC, lo5m, 100));
        store.ingest(new Transaction(9_900_000_012L, ACC, lo5m.plusNanos(1_000), 200));
        store.ingest(new Transaction(9_900_000_013L, ACC, now, 300));
        store.ingest(new Transaction(9_900_000_014L, ACC, now.plusNanos(1_000), 400));
        FeatureVector v = store.read(ACC, now);
        assertEquals(new WindowAgg(2, 500, 200L, 300L), v.agg(1), "5m");
        assertEquals(new WindowAgg(3, 600, 100L, 300L), v.agg(2), "10m");
    }

    @Test
    void dayWindowsCombineDailyAggWithToday() throws Exception {
        Instant now = now();
        long today = WindowCatalog.epochDay(now, env.zone());
        Instant todayStart = WindowCatalog.dayStart(today, env.zone());
        insertDaily(today - 1, 2, "5.00", "1.00", "4.00");
        insertDaily(today - 7, 1, "0.50", "0.50", "0.50");
        insertDaily(today, 9, "90.00", "10.00", "10.00");
        store.ingest(new Transaction(9_900_000_021L, ACC, todayStart, 1000));
        store.ingest(new Transaction(9_900_000_022L, ACC, todayStart.minusNanos(1_000), 7));
        FeatureVector v = store.read(ACC, now);
        assertEquals(new WindowAgg(3, 1500, 100L, 1000L), v.agg(15), "7d: T−1 e hoje; T−7 e a linha de hoje ficam fora");
        assertEquals(new WindowAgg(4, 1550, 50L, 1000L), v.agg(16), "31d");
        assertEquals(0, v.agg(0).cnt(), "DAILY_AGG não alimenta minutos");
    }
```

Imports: acrescentar `java.util.List`.

- [ ] **Step 2: Rodar e ver falhar**

Run: `pwsh -NoProfile scripts/mvn.ps1 -q test '-Dtest=OracleReadSqlTest'`
Expected: falha de compilação (`binds(long, Limits)` não existe).

- [ ] **Step 3: Implementar**

`adapter/oracle/OracleReadSql.java`:

```java
package sentinela.adapter.oracle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import sentinela.domain.DayWindow;
import sentinela.domain.Limits;
import sentinela.domain.RollingWindow;
import sentinela.domain.WindowCatalog;

/**
 * Gera, a partir do WindowCatalog, o statement único da leitura (spec técnica 5.3): agregados condicionais
 * sobre TX para as 15 janelas deslizantes e o dia corrente, em CROSS JOIN com agregados sobre DAILY_AGG
 * para os dias fechados das 4 janelas de dias. Os limites entram como colunas da linha b; os predicados
 * de poda usam binds diretos.
 */
final class OracleReadSql {
    static final String TODAY = "hoje";
    static final String SQL = build();

    private OracleReadSql() {}

    static Object[] binds(long accountId, Limits l) {
        Object[] b = new Object[WindowCatalog.SIZE + 1 + 6];
        int i = 0;
        for (Instant lo : l.rollingLo()) {
            b[i++] = OracleTime.utc(lo);
        }
        b[i++] = OracleTime.utc(l.todayStart());
        for (long d : l.closedLo()) {
            b[i++] = OracleTime.day(d);
        }
        b[i++] = accountId;
        b[i++] = OracleTime.utc(l.oldestRolling());
        b[i++] = OracleTime.utc(l.now());
        b[i++] = accountId;
        b[i++] = OracleTime.day(l.oldestClosed());
        b[i] = OracleTime.day(l.today());
        return b;
    }

    private static String build() {
        List<String> lows = new ArrayList<>();
        WindowCatalog.ROLLING.stream().map(RollingWindow::suffix).forEach(lows::add);
        lows.add(TODAY);
        WindowCatalog.DAYS.stream().map(DayWindow::suffix).forEach(lows::add);
        StringBuilder sb = new StringBuilder("WITH b AS (SELECT ");
        for (int i = 0; i < lows.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append("? AS lo_").append(lows.get(i));
        }
        sb.append(" FROM dual) SELECT t.*, d.* FROM (SELECT ");
        for (RollingWindow w : WindowCatalog.ROLLING) {
            txAggregates(sb, w.suffix(), "x.ts > b.lo_" + w.suffix());
            sb.append(", ");
        }
        txAggregates(sb, TODAY, "x.ts >= b.lo_" + TODAY);
        sb.append(" FROM tx x CROSS JOIN b WHERE x.account_id = ? AND x.ts > ? AND x.ts <= ?) t CROSS JOIN (SELECT ");
        for (int i = 0; i < WindowCatalog.DAYS.size(); i++) {
            String s = WindowCatalog.DAYS.get(i).suffix();
            String cond = "a.dia >= b.lo_" + s;
            sb.append(i == 0 ? "" : ", ")
                    .append("SUM(CASE WHEN ").append(cond).append(" THEN a.cnt END) AS cnt_").append(s)
                    .append(", SUM(CASE WHEN ").append(cond).append(" THEN a.sum_amount END) AS sum_").append(s)
                    .append(", MIN(CASE WHEN ").append(cond).append(" THEN a.min_amount END) AS min_").append(s)
                    .append(", MAX(CASE WHEN ").append(cond).append(" THEN a.max_amount END) AS max_").append(s);
        }
        sb.append(" FROM daily_agg a CROSS JOIN b WHERE a.account_id = ? AND a.dia >= ? AND a.dia < ?) d");
        return sb.toString();
    }

    private static void txAggregates(StringBuilder sb, String s, String cond) {
        sb.append("COUNT(CASE WHEN ").append(cond).append(" THEN 1 END) AS cnt_").append(s)
                .append(", SUM(CASE WHEN ").append(cond).append(" THEN x.amount END) AS sum_").append(s)
                .append(", MIN(CASE WHEN ").append(cond).append(" THEN x.amount END) AS min_").append(s)
                .append(", MAX(CASE WHEN ").append(cond).append(" THEN x.amount END) AS max_").append(s);
    }
}
```

Em `adapter/oracle/OracleFeatureStore.java`, trocar o corpo de `read` e acrescentar `agg`:

```java
    @Override
    public FeatureVector read(long accountId, Instant now) {
        Object[] binds = OracleReadSql.binds(accountId, WindowCatalog.limits(now, zone));
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(OracleReadSql.SQL)) {
            for (int i = 0; i < binds.length; i++) {
                ps.setObject(i + 1, binds[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                List<WindowAgg> aggs = new ArrayList<>(WindowCatalog.SIZE);
                int col = 1;
                for (int w = 0; w < WindowCatalog.ROLLING.size(); w++, col += 4) {
                    aggs.add(agg(rs, col));
                }
                WindowAgg today = agg(rs, col);
                col += 4;
                for (int j = 0; j < WindowCatalog.DAYS.size(); j++, col += 4) {
                    aggs.add(agg(rs, col).plus(today)); // dias fechados + dia corrente (spec técnica 5.3)
                }
                return new FeatureVector(aggs);
            }
        } catch (SQLException e) {
            throw new StoreException("read no Oracle, conta " + accountId, e);
        }
    }

    /** Quatro colunas a partir de base: cnt (COUNT ou SUM, nulo vira 0), sum, min, max. */
    private static WindowAgg agg(ResultSet rs, int base) throws SQLException {
        long cnt = rs.getLong(base);
        if (cnt == 0) {
            return WindowAgg.EMPTY;
        }
        return new WindowAgg(cnt, cents(rs.getBigDecimal(base + 1)), cents(rs.getBigDecimal(base + 2)),
                cents(rs.getBigDecimal(base + 3)));
    }
```

Mudar o javadoc da classe para "TX e DAILY_AGG; leitura em um único statement (spec técnica 5.3)".

- [ ] **Step 4: Rodar e ver passar**

Run: `pwsh -NoProfile scripts/mvn.ps1 -q test`
Expected: PASS.
Run: `pwsh -NoProfile scripts/mvn.ps1 -q verify -Pintegration '-Dit.test=OracleFeatureStoreIT' '-Dtest=NoSuchTest' '-Dsurefire.failIfNoSpecifiedTests=false'`
Expected: PASS nos 4 testes de `OracleFeatureStoreIT`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/sentinela/adapter/oracle src/test/java/sentinela/adapter/oracle
git commit -m "feat(oracle): leitura deslizante ao microssegundo e dia corrente nas janelas de dias"
```

---

### Task 4: Adapter Valkey — sorted set de 73h, leitura em uma ida e reconstrução

**Files:**
- Modify: `adapter/valkey/SliceCodec.java`, `adapter/valkey/LuaScripts.java`, `adapter/valkey/ValkeyFeatureStore.java`, `adapter/valkey/ValkeyMaintenance.java`, `resources/lua/ingest.lua`, `resources/lua/put_day.lua`, `domain/KeyedSlice.java`, `adapter/oracle/OracleMaintenance.java`, `app/BackfillApp.java`
- Test: `adapter/valkey/SliceCodecTest.java`, `adapter/valkey/ValkeyFeatureStoreIT.java`, `adapter/valkey/ValkeyMaintenanceIT.java`, `adapter/oracle/OracleMaintenanceIT.java`

**Interfaces:**
- Consumes: `WindowCatalog.limits`; `Limits.oldestRolling/oldestClosed/now/today`; `WindowAggregator.aggregate`; `DaySlice`.
- Produces:
  - `SliceCodec.key(long)`, `txKey(long)`, `dayField(long)`, `micros(Instant) → long`, `instant(long) → Instant`, `member(Transaction) → String`, `decodeMember(long accountId, String member, double score) → Transaction`, `encode(WindowAgg)`, `decodeDay(String field, String value) → DaySlice`
  - `LuaScripts.ingest(RedisCommands, String txKey, long tsMicros, String member, long dropUpToMicros)`
  - `ValkeyFeatureStore.TX_RETENTION = Duration.ofHours(73)`, `DAY_SLICES = 91`
  - `ValkeyMaintenance.SliceWriter.writeTx(Transaction)`, `writeDay(KeyedSlice)`
  - `record KeyedSlice(long accountId, DaySlice slice)`
  - `OracleMaintenance.streamRecent(Instant now, Consumer<Transaction>)`, `streamDays(Instant now, Consumer<KeyedSlice>)` (substituem `streamSlices`)

- [ ] **Step 1: Testes que falham**

Substituir `adapter/valkey/SliceCodecTest.java`:

```java
package sentinela.adapter.valkey;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import sentinela.domain.DaySlice;
import sentinela.domain.Transaction;
import sentinela.domain.WindowAgg;

class SliceCodecTest {
    private static final Instant TS = Instant.parse("2026-10-29T20:23:40.123456Z");

    @Test
    void formatsKeysAndFields() {
        assertEquals("fs:{4711}", SliceCodec.key(4711));
        assertEquals("fs:{4711}:tx", SliceCodec.txKey(4711));
        assertEquals("d:20754", SliceCodec.dayField(20754));
    }

    @Test
    void microsRoundTrip() {
        assertEquals(1_793_305_420_123_456L, SliceCodec.micros(TS));
        assertEquals(TS, SliceCodec.instant(1_793_305_420_123_456L));
        assertEquals(Instant.parse("1969-12-31T23:59:59.999999Z"), SliceCodec.instant(-1));
    }

    @Test
    void encodesAndDecodesMembers() {
        Transaction tx = new Transaction(98231, 4711, TS, 45000);
        assertEquals("98231|45000", SliceCodec.member(tx));
        assertEquals(tx, SliceCodec.decodeMember(4711, "98231|45000", 1.793305420123456E15));
    }

    @Test
    void encodesAndDecodesDaySlices() {
        WindowAgg a = new WindowAgg(3, 45000, 2000L, 30000L);
        assertEquals("3|45000|2000|30000", SliceCodec.encode(a));
        assertEquals(new DaySlice(20754, a), SliceCodec.decodeDay("d:20754", "3|45000|2000|30000"));
        assertEquals(new DaySlice(20733, WindowAgg.of(7)), SliceCodec.decodeDay("d:20733", "1|7|7|7"));
    }

    @Test
    void rejectsMalformedDayFieldOrValue() {
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decodeDay("m:1", "1|1|1|1"), "fatia de minuto antiga");
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decodeDay("h:1", "1|1|1|1"), "fatia de hora antiga");
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decodeDay("d1", "1|1|1|1"));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decodeDay("d:1", "1|1|1"));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decodeDay("d:1", "1|1|1|1|1"));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decodeDay("d:1", "1|1.5|1|1"));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.encode(WindowAgg.EMPTY));
    }

    @Test
    void rejectsMalformedMember() {
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decodeMember(1, "98231", 1e15));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decodeMember(1, "98231|1|2", 1e15));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decodeMember(1, "a|1", 1e15));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decodeMember(1, "1|1", 1.5));
    }
}
```

Substituir `adapter/valkey/ValkeyFeatureStoreIT.java`:

```java
package sentinela.adapter.valkey;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
class ValkeyFeatureStoreIT {
    private static final long ACC = 990_000_002L;
    private static Env env;
    private static ValkeyConnections conns;
    private static ValkeyFeatureStore store;
    private static RedisCommands<String, String> raw;

    @BeforeAll
    static void setUp() {
        env = Env.fromSystem();
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
        raw.del(SliceCodec.key(ACC), SliceCodec.txKey(ACC));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void unknownAccountReadsEmptyVector() {
        assertEquals(FeatureVector.empty(), store.read(ACC, now()));
    }

    @Test
    void ingestKeepsOneMemberPerTxIdAndTheFirstTs() {
        Instant now = now();
        store.ingest(new Transaction(1, ACC, now.minusSeconds(5), 100));
        store.ingest(new Transaction(2, ACC, now, 200));
        store.ingest(new Transaction(1, ACC, now, 100)); // reentrega com outro ts
        String key = SliceCodec.txKey(ACC);
        assertEquals(2, raw.zcard(key));
        assertEquals((double) SliceCodec.micros(now.minusSeconds(5)), raw.zscore(key, "1|100"));
    }

    @Test
    void readMatchesWindowDefinition() {
        Instant now = now();
        store.ingest(new Transaction(1, ACC, now.minus(30, ChronoUnit.HOURS), 3000));
        store.ingest(new Transaction(2, ACC, now.minus(2, ChronoUnit.HOURS).plusSeconds(1), 2000));
        store.ingest(new Transaction(3, ACC, now.minusSeconds(10), 1000));
        FeatureVector v = store.read(ACC, now);
        assertEquals(WindowAgg.of(1000), v.agg(0), "1m");
        assertEquals(WindowAgg.of(1000), v.agg(5), "1h");
        assertEquals(new WindowAgg(2, 3000, 1000L, 2000L), v.agg(6), "2h");
        assertEquals(new WindowAgg(2, 3000, 1000L, 2000L), v.agg(12), "24h");
        assertEquals(new WindowAgg(3, 6000, 1000L, 3000L), v.agg(13), "48h");
    }

    @Test
    void rollingLowerBoundIsExclusiveAndUpperInclusive() {
        Instant now = now();
        Instant lo5m = now.minus(5, ChronoUnit.MINUTES);
        store.ingest(new Transaction(11, ACC, lo5m, 100));
        store.ingest(new Transaction(12, ACC, lo5m.plusNanos(1_000), 200));
        store.ingest(new Transaction(13, ACC, now, 300));
        store.ingest(new Transaction(14, ACC, now.plusNanos(1_000), 400));
        FeatureVector v = store.read(ACC, now);
        assertEquals(new WindowAgg(2, 500, 200L, 300L), v.agg(1), "5m");
        assertEquals(new WindowAgg(3, 600, 100L, 300L), v.agg(2), "10m");
    }

    @Test
    void dayWindowsCombineDaySlicesAndToday() {
        Instant now = now();
        long today = WindowCatalog.epochDay(now, env.zone());
        Instant todayStart = WindowCatalog.dayStart(today, env.zone());
        raw.hset(SliceCodec.key(ACC), SliceCodec.dayField(today - 1), "2|500|100|400");
        raw.hset(SliceCodec.key(ACC), SliceCodec.dayField(today - 7), "1|50|50|50");
        store.ingest(new Transaction(21, ACC, todayStart.minusNanos(1_000), 7));
        store.ingest(new Transaction(22, ACC, todayStart, 1000));
        FeatureVector v = store.read(ACC, now);
        assertEquals(new WindowAgg(3, 1500, 100L, 1000L), v.agg(15), "7d");
        assertEquals(new WindowAgg(4, 1550, 50L, 1000L), v.agg(16), "31d");
    }

    @Test
    void ingestDropsTransactionsOlderThanRetention() {
        Instant now = now();
        store.ingest(new Transaction(31, ACC, now.minus(80, ChronoUnit.HOURS), 100));
        store.ingest(new Transaction(32, ACC, now.minus(72, ChronoUnit.HOURS).minusMinutes(30), 100));
        store.ingest(new Transaction(33, ACC, now, 100));
        assertEquals(2, raw.zcard(SliceCodec.txKey(ACC)), "80h sai; 72h30 fica na folga de 73h");
        assertEquals(1, store.read(ACC, now).agg(14).cnt(), "72h conta só a de agora");
    }

    @Test
    void futureTransactionKeepsRetentionMargin() {
        Instant now = now();
        store.ingest(new Transaction(41, ACC, now.minus(Duration.ofHours(72)).plusNanos(1_000), 100));
        store.ingest(new Transaction(42, ACC, now.plusSeconds(30), 100)); // ingestor adiantado
        assertEquals(WindowAgg.of(100), store.read(ACC, now).agg(14), "72h ainda vê a transação da borda");
    }

    @Test
    void reloadsScriptAfterScriptFlush() {
        raw.scriptFlush();
        Instant now = now();
        store.ingest(new Transaction(1, ACC, now, 100));
        assertEquals(1, store.read(ACC, now).agg(0).cnt());
    }
}
```

Em `adapter/valkey/ValkeyMaintenanceIT.java`:
- `resetEmpties`: `raw.hset("fs:{1}", "d:1", "1|1|1|1");`
- Em `putDaysWritesOverwritesAndDropsOldDays`, trocar a última linha por:

```java
        assertEquals(0, vm.putDays(today - 92, rows, today), "92 dias atrás já está fora da 92d");
        assertEquals(2, vm.putDays(today - 91, rows, today), "91 dias atrás é o primeiro dia da 92d");
```

- Trocar `sliceWriterRebuildsReadableHashes` por:

```java
    @Test
    void sliceWriterRebuildsReadableStore() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant ts = now.minusSeconds(10);
        long today = WindowCatalog.epochDay(now, env.zone());
        WindowAgg closed = new WindowAgg(3, 900, 100L, 500L);
        try (ValkeyMaintenance.SliceWriter w = vm.sliceWriter()) {
            for (long acc = 1; acc <= 2_500; acc++) {
                w.writeTx(new Transaction(acc, acc, ts, 100));
                w.writeDay(new KeyedSlice(acc, new DaySlice(today - 1, closed)));
            }
            w.close();
            assertEquals(5_000, w.written());
        }
        FeatureVector v = store.read(2_500, now);
        assertEquals(WindowAgg.of(100), v.agg(0));
        assertEquals(WindowAgg.of(100), v.agg(6));
        WindowAgg expected7d = WindowCatalog.epochDay(ts, env.zone()) == today ? closed.plus(WindowAgg.of(100)) : closed;
        assertEquals(expected7d, v.agg(15));
        assertEquals(5_000, raw.dbsize(), "duas chaves por conta");
    }
```

  Imports: tirar `Slice`, `Unit`, `ArrayList`, `List` se não forem mais usados (o `List` continua em `putDays`); acrescentar `sentinela.domain.DaySlice`, `sentinela.domain.Transaction`.

Em `adapter/oracle/OracleMaintenanceIT.java`, trocar `bulkInsertsAndStreamsSlices` por:

```java
    @Test
    void bulkInsertsAndStreamsWhatTheValkeyNeeds() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        long today = WindowCatalog.epochDay(now, env.zone());
        List<Transaction> txs = new ArrayList<>();
        LongStream.rangeClosed(1, 12_000).forEach(i -> txs.add(new Transaction(i, 1 + i % 3, now.minusSeconds(i % 50), 100)));
        txs.add(new Transaction(20_000, 1, now.minus(80, ChronoUnit.HOURS), 100));
        txs.add(new Transaction(20_001, 1, now.plusSeconds(5), 100));
        assertEquals(12_002, m.bulkInsertTx(txs.iterator()));
        m.bulkInsertDaily(today - 5, List.of(new AccountAgg(1, new WindowAgg(4, 1000, 100L, 400L))));
        m.bulkInsertDaily(today - 95, List.of(new AccountAgg(1, WindowAgg.of(1))));

        List<Transaction> recent = new ArrayList<>();
        m.streamRecent(now, recent::add);
        assertEquals(12_000, recent.size(), "só (now − 72h, now]");
        assertTrue(recent.containsAll(txs.subList(0, 12_000)), "tx_id, conta, ts e valor intactos");

        List<KeyedSlice> days = new ArrayList<>();
        m.streamDays(now, days::add);
        assertEquals(List.of(new KeyedSlice(1, new DaySlice(today - 5, new WindowAgg(4, 1000, 100L, 400L)))), days);
    }
```

  Imports: tirar `Unit` do teste só na Tarefa 5 (o teste de `closeDay` ainda usa `sliceStart`); acrescentar `sentinela.domain.DaySlice`.

- [ ] **Step 2: Rodar e ver falhar**

Run: `pwsh -NoProfile scripts/mvn.ps1 -q test '-Dtest=SliceCodecTest'`
Expected: falha de compilação (`txKey`, `decodeDay`, `DaySlice` em `KeyedSlice` não existem).

- [ ] **Step 3: Implementar**

`domain/KeyedSlice.java`:

```java
package sentinela.domain;

/** Fatia de dia de uma conta específica, usada para reconstruir o Valkey a partir do Oracle. */
public record KeyedSlice(long accountId, DaySlice slice) {}
```

`adapter/valkey/SliceCodec.java`:

```java
package sentinela.adapter.valkey;

import java.time.Instant;
import sentinela.domain.DaySlice;
import sentinela.domain.Transaction;
import sentinela.domain.WindowAgg;

/**
 * Formato no Valkey (spec técnica 6.1): sorted set fs:{conta}:tx com score = ts em µs e membro
 * tx_id|centavos; hash fs:{conta} com campos d:<epoch dia> e valor cnt|sum|min|max em centavos.
 */
public final class SliceCodec {
    private SliceCodec() {}

    public static String key(long accountId) {
        return "fs:{" + accountId + "}";
    }

    public static String txKey(long accountId) {
        return key(accountId) + ":tx";
    }

    public static String dayField(long epochDay) {
        return "d:" + epochDay;
    }

    public static long micros(Instant t) {
        return Math.addExact(Math.multiplyExact(t.getEpochSecond(), 1_000_000L), t.getNano() / 1_000);
    }

    public static Instant instant(long micros) {
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L);
    }

    public static String member(Transaction tx) {
        return tx.txId() + "|" + tx.amountCents();
    }

    public static Transaction decodeMember(long accountId, String member, double score) {
        int bar = member.indexOf('|');
        if (bar <= 0 || bar != member.lastIndexOf('|')) {
            throw new IllegalArgumentException("membro inválido: " + member);
        }
        long micros = (long) score;
        if (micros != score) {
            throw new IllegalArgumentException("score não é um inteiro de microssegundos: " + score);
        }
        try {
            return new Transaction(Long.parseLong(member, 0, bar, 10), accountId, instant(micros),
                    Long.parseLong(member, bar + 1, member.length(), 10));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("membro inválido: " + member, e);
        }
    }

    public static String encode(WindowAgg a) {
        if (a.cnt() == 0) {
            throw new IllegalArgumentException("fatia vazia não é gravada");
        }
        return a.cnt() + "|" + a.sumCents() + "|" + a.minCents() + "|" + a.maxCents();
    }

    public static DaySlice decodeDay(String field, String value) {
        if (field.length() < 3 || !field.startsWith("d:")) {
            throw new IllegalArgumentException("campo inválido (só há fatias d: no hash): " + field);
        }
        long epochDay = Long.parseLong(field, 2, field.length(), 10);
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
        return new DaySlice(epochDay, new WindowAgg(v[0], v[1], v[2], v[3]));
    }
}
```

`NumberFormatException` é subclasse de `IllegalArgumentException`, então os `parseLong` de `decodeDay` já satisfazem o teste.

`resources/lua/ingest.lua`:

```lua
-- Grava uma transação no sorted set da conta e descarta as que saíram da retenção.
-- KEYS[1] = fs:{conta}:tx
-- ARGV[1] = ts em µs, ARGV[2] = '<tx_id>|<centavos>', ARGV[3] = maior score descartável (ts − 73h, em µs)
-- NX mantém o primeiro ts de um tx_id repetido, como a PK do Oracle.
redis.call('ZADD', KEYS[1], 'NX', ARGV[1], ARGV[2])
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[3])
return 0
```

`resources/lua/put_day.lua`: só o comentário do ARGV[3] muda para `menor dia retido (T-91)`.

Em `adapter/valkey/LuaScripts.java`, trocar `ingest`:

```java
    public void ingest(RedisCommands<String, String> sync, String txKey, long tsMicros, String member,
            long dropUpToMicros) {
        String[] keys = {txKey};
        String[] args = {Long.toString(tsMicros), member, Long.toString(dropUpToMicros)};
        try {
            sync.evalsha(ingestSha, ScriptOutputType.INTEGER, keys, args);
        } catch (RedisNoScriptException e) {
            load(sync);
            sync.evalsha(ingestSha, ScriptOutputType.INTEGER, keys, args);
        }
    }
```

`adapter/valkey/ValkeyFeatureStore.java`:

```java
package sentinela.adapter.valkey;

import io.lettuce.core.Range;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import sentinela.domain.DaySlice;
import sentinela.domain.FeatureStorePort;
import sentinela.domain.FeatureVector;
import sentinela.domain.Limits;
import sentinela.domain.Transaction;
import sentinela.domain.WindowAggregator;
import sentinela.domain.WindowCatalog;
import sentinela.infra.StoreException;

/** Valkey como store: transações de 73h num sorted set por conta e dias fechados num hash (spec técnica 6). */
public final class ValkeyFeatureStore implements FeatureStorePort {
    /** As 72h da maior janela e 1h de folga para leituras concorrentes e relógios um pouco adiantados. */
    public static final Duration TX_RETENTION = Duration.ofHours(73);
    /** Dias fechados retidos: de T−91 (primeiro dia da 92d) a T−1. */
    public static final int DAY_SLICES = 91;

    private static final long TX_RETENTION_MICROS = TX_RETENTION.toNanos() / 1_000;
    private static final long READ_WAIT_SECONDS = 5;

    private final RedisCommands<String, String> sync;
    private final RedisAsyncCommands<String, String> async;
    private final LuaScripts scripts;
    private final ZoneId zone;

    public ValkeyFeatureStore(StatefulRedisConnection<String, String> conn, LuaScripts scripts, ZoneId zone) {
        this.sync = conn.sync();
        this.async = conn.async();
        this.scripts = scripts;
        this.zone = zone;
        scripts.load(sync);
    }

    @Override
    public void ingest(Transaction tx) {
        long ts = SliceCodec.micros(tx.ts());
        try {
            scripts.ingest(sync, SliceCodec.txKey(tx.accountId()), ts, SliceCodec.member(tx), ts - TX_RETENTION_MICROS);
        } catch (RedisException e) {
            throw new StoreException("ingest no Valkey, tx_id " + tx.txId(), e);
        }
    }

    @Override
    public FeatureVector read(long accountId, Instant now) {
        Limits l = WindowCatalog.limits(now, zone);
        List<ScoredValue<String>> recent;
        Map<String, String> days;
        try {
            // Os dois comandos saem juntos pela conexão multiplexada: uma ida ao servidor.
            RedisFuture<List<ScoredValue<String>>> fr = async.zrangebyscoreWithScores(SliceCodec.txKey(accountId),
                    Range.from(Range.Boundary.excluding(SliceCodec.micros(l.oldestRolling())),
                            Range.Boundary.including(SliceCodec.micros(l.now()))));
            RedisFuture<Map<String, String>> fd = async.hgetall(SliceCodec.key(accountId));
            recent = fr.get(READ_WAIT_SECONDS, TimeUnit.SECONDS);
            days = fd.get(READ_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (RedisException | ExecutionException | TimeoutException e) {
            throw new StoreException("read no Valkey, conta " + accountId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException("read no Valkey interrompido, conta " + accountId, e);
        }
        List<Transaction> txs = new ArrayList<>(recent.size());
        for (ScoredValue<String> sv : recent) {
            txs.add(SliceCodec.decodeMember(accountId, sv.getValue(), sv.getScore()));
        }
        List<DaySlice> slices = new ArrayList<>(days.size());
        days.forEach((field, value) -> slices.add(SliceCodec.decodeDay(field, value)));
        return WindowAggregator.aggregate(txs, slices, l);
    }
}
```

Em `adapter/valkey/ValkeyMaintenance.java`:
- Javadoc da classe: "reset, fatias de dia do Job e reconstrução a partir do Oracle (sorted set e hash)".
- `putDays` não muda o código (usa `ValkeyFeatureStore.DAY_SLICES`, agora 91).
- Trocar `SliceWriter`:

```java
    /** Grava transações (ZADD) e fatias de dia (HSET) sem Lua: usado logo depois de um reset. */
    public static final class SliceWriter implements AutoCloseable {
        private final AsyncBatch batch;

        private SliceWriter(AsyncBatch batch) {
            this.batch = batch;
        }

        public void writeTx(Transaction tx) {
            batch.add(batch.commands().zadd(SliceCodec.txKey(tx.accountId()), (double) SliceCodec.micros(tx.ts()),
                    SliceCodec.member(tx)));
        }

        public void writeDay(KeyedSlice s) {
            batch.add(batch.commands().hset(SliceCodec.key(s.accountId()), SliceCodec.dayField(s.slice().epochDay()),
                    SliceCodec.encode(s.slice().agg())));
        }

        public long written() {
            return batch.completed();
        }

        @Override
        public void close() {
            batch.close();
        }
    }
```

  Import: `sentinela.domain.Transaction`.

Em `adapter/oracle/OracleMaintenance.java`, remover `streamSlices`, `streamTxSlices` e a constante `DAY_SLICES`, e acrescentar:

```java
    /** Transações de (now − 72h, now], para o sorted set do Valkey. */
    public void streamRecent(Instant now, Consumer<Transaction> sink) {
        Limits l = WindowCatalog.limits(now, zone);
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT account_id, tx_id, ts, amount FROM tx WHERE ts > ? AND ts <= ?")) {
            ps.setObject(1, OracleTime.utc(l.oldestRolling()));
            ps.setObject(2, OracleTime.utc(l.now()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sink.accept(new Transaction(rs.getLong(2), rs.getLong(1),
                            OracleTime.instant(rs.getObject(3, LocalDateTime.class)), OracleTime.cents(rs.getBigDecimal(4))));
                }
            }
        } catch (SQLException e) {
            throw new StoreException("leitura de transações recentes para o Valkey", e);
        }
    }

    /** Dias fechados das janelas de dias (do primeiro dia da 92d a ontem), para o hash do Valkey. */
    public void streamDays(Instant now, Consumer<KeyedSlice> sink) {
        Limits l = WindowCatalog.limits(now, zone);
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT account_id, dia, cnt, sum_amount, min_amount, max_amount FROM daily_agg WHERE dia >= ? AND dia < ?")) {
            ps.setObject(1, OracleTime.day(l.oldestClosed()));
            ps.setObject(2, OracleTime.day(l.today()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long day = rs.getObject(2, LocalDate.class).toEpochDay();
                    sink.accept(new KeyedSlice(rs.getLong(1), new DaySlice(day, agg(rs, 3))));
                }
            }
        } catch (SQLException e) {
            throw new StoreException("leitura de dias fechados para o Valkey", e);
        }
    }
```

  Imports: tirar `Slice` e `Unit`; acrescentar `sentinela.domain.DaySlice` e `sentinela.domain.Limits`.

Em `app/BackfillApp.java`, trocar o bloco final do Valkey:

```java
            if (vm != null) {
                try (ValkeyMaintenance.SliceWriter w = vm.sliceWriter()) {
                    om.streamRecent(now, w::writeTx);
                    om.streamDays(now, w::writeDay);
                    w.close();
                    step(t0, "Valkey reconstruído: " + w.written() + " comandos (transações de 72h e dias fechados)");
                }
            }
```

- [ ] **Step 4: Rodar e ver passar**

Run: `pwsh -NoProfile scripts/mvn.ps1 -q test`
Expected: PASS.
Run: `pwsh -NoProfile scripts/mvn.ps1 -q verify -Pintegration '-Dit.test=ValkeyFeatureStoreIT,ValkeyMaintenanceIT,OracleMaintenanceIT' '-Dtest=NoSuchTest' '-Dsurefire.failIfNoSpecifiedTests=false'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test
git commit -m "feat(valkey): transações de 73h em sorted set e leitura deslizante em uma ida"
```

---

### Task 5: Limpeza do modelo de fatias e verificação completa

**Files:**
- Delete: `domain/Unit.java`, `domain/Window.java`, `domain/Slice.java`, `domain/SliceAggregator.java`, `domain/Bounds.java`, `test/.../domain/BoundsTest.java`, `test/.../domain/SliceAggregatorTest.java`
- Modify: `domain/WindowCatalog.java`, `adapter/oracle/OracleTime.java`, `workload/BackfillGenerator.java`, `README.md`
- Test: `domain/WindowCatalogTest.java`, `workload/BackfillGeneratorTest.java`, `adapter/oracle/OracleMaintenanceIT.java`

**Interfaces:**
- Consumes: `WindowCatalog.dayStart`.
- Produces: `WindowCatalog` só com `ROLLING`, `DAYS`, `SUFFIXES`, `SIZE`, `FIRST_DAY_WINDOW`, `epochDay`, `dayStart`, `limits`.

- [ ] **Step 1: Remover o modelo antigo**

- Apagar os sete arquivos acima (`git rm`).
- Em `WindowCatalog`, remover `WINDOWS`, `epochMinute`, `epochHour`, `sliceStart` e `bounds`, e os imports estáticos de `Unit`. O javadoc da classe fica:

```java
/**
 * As 19 janelas da seção 3 da spec funcional e a regra única dos limites. Janelas deslizantes:
 * now − span < ts ≤ now, ao microssegundo. Janelas de dias: n − 1 dias civis fechados em FS_ZONE mais o
 * dia corrente até now.
 */
```

- Em `WindowCatalogTest`, remover `hasNineteenWindowsInSpecOrder`, `oneHourUsesSixtyMinuteSlices`, `minuteAndHourBoundsAlignToCurrentSlice`, `dayBoundsExcludeTodayInZone`, `exactSliceBoundaryBelongsToNewSlice`, `negativeEpochUsesFloor` e `sliceStartInvertsIndex` (cobertos pelos testes novos da Tarefa 1); manter `civilDayFollowsZoneNotUtc`.
- Em `OracleTime`, remover `sliceStart(Unit, long)` e o import de `Unit`; `dayStartUtc` passa a `return utc(WindowCatalog.dayStart(epochDay, zone));`.
- Em `BackfillGenerator`, trocar `WindowCatalog.sliceStart(Unit.DAY, day, zone)` e `(… day + 1 …)` por `WindowCatalog.dayStart(day, zone)` e `WindowCatalog.dayStart(day + 1, zone)`; tirar o import de `Unit`.
- Em `BackfillGeneratorTest` e `OracleMaintenanceIT` (teste de `closeDay`), mesma troca para `WindowCatalog.dayStart(...)`; tirar imports de `Unit`.
- Em `README.md`, na seção do programa `equivalencia`, trocar "janelas vazias, dias fechados, idempotência e Job diário" por "janelas vazias, dia corrente nas janelas de dias, limites ao microssegundo, idempotência e Job diário".

- [ ] **Step 2: Conferir que nada do modelo antigo sobrou**

Run (Git Bash): `grep -rnE "\bUnit\b|sliceStart|epochMinute|epochHour|WindowCatalog\.WINDOWS|\.bounds\(|SliceAggregator|\bSlice\b" src`
Expected: nenhuma linha.

- [ ] **Step 3: Testes unitários e de integração completos**

Run: `pwsh -NoProfile scripts/mvn.ps1 -q verify -Pintegration`
Expected: BUILD SUCCESS, todos os unitários e ITs passando, inclusive `EquivalenceIT` (1.000 contas).

- [ ] **Step 4: Programa `equivalencia` na imagem**

Run: `podman build -t sentinela-poc .` e depois `pwsh -NoProfile scripts/poc.ps1 equivalencia --reset`
Expected: 7 linhas `PASS`, exit code 0.

- [ ] **Step 5: Commit**

```bash
git add -A src README.md
git commit -m "refactor: remove o modelo de fatias de minuto e hora"
```

---

### Task 6: Benchmark comparativo

**Files:**
- Create: `docs/benchmark/2026-10-29-janelas-deslizantes.md` (resultado e leitura dos números)

- [ ] **Step 1: Rodada `oracle`**

Run: `pwsh -NoProfile scripts/rodada.ps1 -Mode oracle`
Expected: backfill com "plano de leitura ok", ingestor e leitor com exit 0, `logs/report/report.md` gerado.

- [ ] **Step 2: Rodada `oracle+valkey`**

Run: `pwsh -NoProfile scripts/rodada.ps1 -Mode oracle+valkey`
Expected: idem; o relatório passa a ter a tabela lado a lado dos dois modos.

- [ ] **Step 3: Conferir validade das rodadas**

Em `logs/report/report.md`, as colunas `tps_ok`, `erro_ok` e `lag_ok` precisam estar ok nas duas rodadas. Se alguma não estiver, registrar no documento de resultado como rodada inválida e o motivo, sem esconder.

- [ ] **Step 4: Documento de resultado**

`docs/benchmark/2026-10-29-janelas-deslizantes.md` com: ambiente (CPU/RAM da VM, versões), parâmetros (100 mil contas, 600 TPS, 300 s, warmup 30 s, 8 threads, `skewed`), a tabela lado a lado copiada do `report.md` (TPS alcançado, latência min/avg/p95/p99/max por operação), os critérios de carga, e a leitura: custo da leitura em cada modo, custo da dupla ingestão, e o que muda em relação à semântica antiga (o Valkey agora lê as transações de 72h, como o Oracle).

- [ ] **Step 5: Commit**

```bash
git add docs/benchmark
git commit -m "docs: benchmark comparativo com janelas deslizantes"
```
