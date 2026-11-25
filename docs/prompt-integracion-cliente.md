# Prompt: integración del diseño de base de datos del *sentinela* en el proyecto de scoring de fraude

> **Cómo usar este prompt.** Descomprime `sentinela.zip` en una carpeta **fuera** del repositorio del proyecto
> (por ejemplo, `../referencia/sentinela/`) y entrega este texto al asistente de código, indicándole esa ruta.
> El zip es solo una **implementación de referencia**: no se copia entero ni se ejecuta en producción.

---

## 1. Tu rol y objetivo

Eres un ingeniero de software que trabaja en este repositorio, el proyecto de scoring de fraude. Tu tarea es
**integrar el diseño de base de datos validado en el proyecto *sentinela*** (modelo de datos, consultas y reglas de
cálculo de las 10 variables) en el flujo real de este proyecto, que recibe la transacción, calcula las variables y
se las entrega al modelo XGBoost para obtener el score.

El *sentinela* se construyó **solo para validar el diseño en la base de datos** (modelo de datos, consultas y
rendimiento), y ese diseño fue **aprobado**. Lo que se reutiliza de él es:

- el modelo de datos (tablas, particionamiento, índices);
- la consulta única que lee los agregados de las 10 variables;
- la clasificación de cada transacción que se calcula al grabar;
- las reglas de cálculo y de NULL de cada variable;
- los casos de prueba que verifican que el resultado es correcto.

**No** se reutilizan el simulador de carga, los scripts de benchmark, los contenedores de podman ni los
endpoints de ingesta del *sentinela*, salvo que este proyecto los necesite.

## 2. Descripción del proyecto

Detección de fraude en compras con tarjeta CMR (Banco Falabella). Para cada compra en autorización (llamada
**T0**), el sistema consulta la historia reciente del cliente en tres fuentes de datos, calcula 10 variables y las
entrega a un modelo XGBoost, que devuelve un score de fraude. La consulta está en el camino crítico de la
autorización, así que su latencia es un requisito.

## 3. Componentes de la arquitectura

| Componente | Tecnología | Papel |
| --- | --- | --- |
| API de scoring | FastAPI, desplegada en **OKE** | Recibe la transacción, consulta la base, calcula las 10 variables, llama al modelo y devuelve el score |
| Base de datos | **Autonomous Database** (Oracle 26ai) | Guarda la historia de las tres fuentes: transacciones (TRX), notificaciones de dispositivo (ABR) y eventos de la plataforma de seguridad (VU) |
| Modelo | XGBoost en **OCI Data Science** (model deployment) | Recibe las 10 variables y devuelve el score de fraude |

## 4. Camino de la transacción

```
[a] Llega la transacción (T0) a la API en OKE
 │    datos mínimos: N_ID_CUSTOMER, D_TRANSMISSION_DATE_AND_TIME, F_AMOUNT_TRANSACTION,
 │                   N_MERCHANT_TYPE (MCC), S_CARD_ACCEPT_EXT_ID_CODE (comercio)
 ▼
[b] La API consulta el Autonomous Database: 1 SELECT, 3 subconsultas (TRX, ABR, VU)
 ▼
[c] La API calcula las 10 variables a partir de los agregados (razones y reglas de NULL)
 ▼
[d] La API envía las 10 variables al model deployment de OCI Data Science
 ▼
[e] El modelo devuelve el score de fraude
```

Puntos importantes del camino:

- **El `user_id` solo no basta.** Además del cliente, la consulta necesita el instante, el monto, el MCC y el
  comercio de la T0: anclan las ventanas de tiempo, definen los filtros "mismo MCC" (V2) y "mismo comercio" (V4)
  y forman el numerador de V10. Verifica que la llamada [a] los reciba.
- **La propia T0 no entra en las agregaciones de TRX**: las ventanas de TRX empiezan 1 segundo antes de la T0.
  Por eso no importa si la compra ya está grabada o no al momento de la consulta. En el *sentinela* esto
  corresponde a `POST /v1/features` (la T0 llega en el cuerpo), que es el caso de este proyecto.
- Los eventos anteriores a la T0 (TRX, ABR y VU) tienen que estar grabados en la base **antes** de la consulta.
  Identifica en este proyecto quién y cómo los graba, porque la clasificación de cada TRX se calcula al grabar
  (sección 5.2).

## 5. Cálculo de las reglas (como se implementó en el *sentinela*)

> **Definición provisoria, versión `excel-2026-11-13`.** Sale de las fórmulas del Excel de validación
> (`Validacion_variables_POC_Fraude_ML_casos_sinteticos.xlsx`), no del SQL que generó las variables del
> entrenamiento. **Revisa cada regla contra el entrenamiento del modelo** y ajústala si hace falta (ver 5.5).
> La definición completa está en `docs/specs/variaveis-xgboost.md` del zip.

### 5.1 Instantes y ventanas

- Todos los instantes se toman en **hora local** de `America/Santiago`, sin zona horaria, **redondeados al
  segundo**. Un instante con zona se convierte a esa hora local.
- `delta = T0 − instante del evento`, en segundos enteros.

| Ventana | Condición |
| --- | --- |
| TRX 5 min / 3 h / 24 h / 92 d | `1 ≤ delta ≤ 300 / 10.800 / 86.400 / 7.948.800` |
| ABR 1 h / 92 d | `1 ≤ delta ≤ 3.600 / 7.948.800` |
| VU 92 d | `0 ≤ delta ≤ 7.948.800` (incluye el segundo de la T0) |

Los 92 días son **móviles** (T0 − 92 × 86.400 s), no desde la medianoche. Eventos posteriores a la T0 nunca entran.

### 5.2 Clasificación de cada TRX (se calcula al grabar)

| Atributo | Regla |
| --- | --- |
| base | `ORIGEN = 'atmpos_trxs'` y `N_MTI = 200`. **Toda ventana de TRX considera solo filas base** |
| global | `N_PCODE ∈ {3000, 2000, 6000}` |
| aprobada | base, global y `S_RESPONSE_CODE = '00'` |
| rechazada | base, global y `S_RESPONSE_CODE ≠ '00'`, **incluido nulo** |
| presente | `N_ENTRY_MODE ∈ {9, 20–23, 50–53, 70–73, 800–803, 900–903, 910–913}`; nulo o fuera de la lista = ausente |
| nacional | país adquirente `152` **o** `S_CARD_ACCEPTOR_NAME` contiene `WEBPAY` (sensible a mayúsculas) |
| franja horaria | 00–05 madrugada, 06–14 diurno, 15–23 tarde-noche (hora local de la TRX) |
| mismo MCC / mismo comercio | igual al de la T0, con **nulo = nulo**. Depende de la T0: se evalúa en la lectura |

En la tabla `TRX` se guardan como columnas 0/1: `base`, `ap` (aprobada), `re` (rechazada), `ap_aus` (aprobada
con tarjeta ausente, V7), `re_npd` (rechazada nacional, presente y diurna, V8) y `ap_mad` (aprobada de madrugada,
V9).

### 5.3 Las 10 variables

Notación, siempre sobre TRX base del cliente:

- `N(v)`: filas de la ventana v (cualquier pcode y respuesta).
- **Suma protegida** `gate(N, f)`: NULL si `N` menos las filas del filtro f con monto nulo es 0; si no, la suma de
  los montos no nulos de f (0 si ninguna fila pasa el filtro). Distingue "no hubo transacciones" (NULL) de "hubo,
  pero ninguna cumple" (0).
- `ratio(a, b)`: NULL si a o b es NULL o si b = 0; si no, a / b.
- `MAX0`: `MAX(0, máximo de los montos no nulos)`; 0 si no hay ninguno.

| # | Variable | Regla (resumen) | NULL / valor por defecto |
| --- | --- | --- | --- |
| V1 | `CLI_GL_LAST_24_HOUR_E_92_DAY_AMOUNT_TRX_APPROVED` | Monto aprobado en 24 h ÷ monto aprobado en 92 d | `ratio(gate(N24), gate(N92))` |
| V2 | `CLI_MCC_GL_LAST_24_HOUR_E_92_DAY_AMOUNT_TRX_APPROVED` | Igual que V1, solo con TRX del mismo MCC de la T0 | `N24`/`N92` cuentan solo filas del mismo MCC |
| V3 | `CLI_GL_LAST_24_HOUR_PROM_AMOUNT_TRX_NOT_APPROVED` | Promedio del monto de las rechazadas en 24 h | Denominador: rechazadas en 24 h, **incluidos montos nulos**; NULL si `N24 = 0` |
| V4 | `CLI_COM_GL_LAST_5_MIN_N_TRX_APPROVED` | Cantidad de aprobadas en 5 min en el mismo comercio de la T0 | 0 si no hay TRX de 5 min en el comercio. **Nunca NULL** |
| V5 | `CLI_ABR_LAST_1_HOUR_E_92_DAY_DISTINCT_CLIENT` | Segundos distintos con notificación ABR válida en 1 h ÷ en 92 d. Válida: algún atributo del dispositivo informado | NULL si no hay ABR en 92 d |
| V6 | `CLI_VU_LAST_92_DAY_R_N_CPASS` | Eventos CPASS (`N_TRANSACTION_TYPE ∈ {999901, 999903}`) ÷ total de eventos VU en 92 d | NULL si no hay VU **o si no hay CPASS** |
| V7 | `CLI_A_LAST_3_HOUR_AMOUNT_TRX_APPROVED_MAX` | Mayor monto aprobado con tarjeta ausente en 3 h (`MAX0`) | 0 si la ventana de 3 h está vacía. **Nunca NULL** |
| V8 | `CLI_NP_LAST_92_DAY_PROM_AMOUNT_TRX_D_NOT_APPROVED` | Promedio del monto de las rechazadas nacionales, presentes y diurnas en 92 d | Como V3, sobre `N92` |
| V9 | `CLI_GL_LAST_24_HOUR_E_92_DAY_AMOUNT_TRX_M_APPROVED_MAX` | Mayor aprobada de madrugada en 24 h ÷ mayor aprobada de madrugada en 92 d (`MAX0`) | Protegida por `N24`/`N92`, como V1 |
| V10 | `R_AMOUNT_TRX_CLI_GL_92_DAY_AMOUNT_TRX_APPROVED` | Monto de la T0 ÷ monto aprobado en 92 d (denominador de V1) | NULL si el monto de la T0 es nulo |

Ejemplo completo con números, de la historia del cliente hasta las 10 variables: `docs/leitura-oracle.md`,
secciones 2.1 a 2.5.

### 5.4 Salida hacia el modelo

El orden es el de la tabla (V1 a V10). V4 es entero; las demás, numéricas; NULL en el *sentinela* sale como
`null` en el JSON. **Confirma contra el artefacto entrenado** el orden y los nombres de las features
(`feature_names` del booster), los tipos y cómo representa el modelo un valor ausente (en XGBoost, normalmente
`NaN`, no 0).

### 5.5 Puntos a revisar con el entrenamiento del modelo

El Word de especificación y el Excel no coinciden en estos puntos. El *sentinela* implementó la columna Excel.
Cada fila es una decisión pendiente:

| # | Tema | Word | Excel (implementado) |
| --- | --- | --- | --- |
| 1 | V5 | clientes distintos por identificador ABR (dispositivo, si no, semilla) | notificaciones distintas del cliente, por segundo |
| 2 | Inicio de los 92 d | 00:00 del día de hace 92 días | T0 − 92 × 86.400 s |
| 3 | Fin de la ventana VU | hasta T0 − 1 s | incluye el segundo de la T0 |
| 4 | Madrugada (V9) | 22:00–05:59 | 00:00–05:59 |
| 5 | V6 con VU sin CPASS | 0 | NULL |
| 6 | Nacionalidad en V1 | exige país 152 | sin filtro de país |
| 7 | Nacionalidad en V8 | país 152 | país 152 o nombre con WEBPAY |

También quedan abiertos: comparación de texto sensible o no a mayúsculas, el filtro de terminales que cita el
Word, `ID_TRX` duplicados, montos negativos (el `MAX0` de V7/V9 los anula; las sumas de V1/V3/V8 no) y eventos
que llegan atrasados.

## 6. Integración de la base de datos (referencia: `sentinela.zip`)

### 6.1 Dónde está cada cosa en el zip

| Qué | Archivo |
| --- | --- |
| Definición de las variables, NULL y discrepancias | `docs/specs/variaveis-xgboost.md` |
| Recorrido completo de escritura y lectura, con ejemplo numérico, índices, plan y costos | `docs/leitura-oracle.md` |
| DDL: tablas `TRX`, `ABR`, `VU`, particiones e índices | `db/01_schema.sql` |
| Clasificación de cada TRX al grabar (`TrxFlags`) | `src/sentinela/domain/events.py` |
| La consulta única de lectura (generada) | `src/sentinela/db/read_sql.py` |
| Razones y reglas de NULL sobre los agregados | `src/sentinela/domain/variables.py` |
| Validación de la entrada y conversión a hora local | `src/sentinela/api/models.py` |
| Verificación del plan de ejecución | `src/sentinela/db/plan.py` |
| Retención (drop de particiones de más de 93 días) | `src/sentinela/db/maintenance.py` |
| Referencia independiente: transcripción literal de las fórmulas del Excel | `src/sentinela/verification/reference.py` |
| Los 6 casos del Excel (entradas y 60 valores esperados) | `src/sentinela/verification/excel_casos.json` |
| Escenarios adicionales (bordes de ventana, nulos) | `src/sentinela/verification/scenarios.py` |
| Pruebas | `tests/unit/test_reference_excel.py`, `tests/unit/test_equivalencia.py`, `tests/integration/test_oracle.py` |
| Despliegue y resultados en ATP | `docs/oci.md` |

La documentación y los comentarios del zip están en portugués.

### 6.2 Decisiones de diseño que hay que conservar

Son las que explican el rendimiento aprobado. Si alguna no se puede mantener, explica por qué y propone una
alternativa antes de cambiarla.

1. **Una sola ida a la base por scoring.** Un `SELECT` con tres subconsultas de agregación (TRX, ABR, VU) unidas
   por `CROSS JOIN`. Cada subconsulta, sin `GROUP BY`, devuelve exactamente una fila, así que el resultado es
   siempre 1 fila con ~50 agregados.
2. **Agregación condicional.** El `WHERE` filtra cliente, 92 días y `base = 1`; cada ventana y filtro es un
   `CASE WHEN … END` dentro de `COUNT`/`SUM`/`MAX`. Una sola pasada por las filas del cliente calcula todo.
3. **La SQL devuelve agregados brutos y la aplicación calcula las razones.** Para cada filtro, la consulta
   devuelve el conteo, cantidad con monto nulo, suma y máximo; para cada ventana, la cantidad de filas base. Con
   eso se aplican las reglas de NULL sin otra consulta.
4. **Clasificación precalculada al grabar.** Las columnas `base`, `ap`, `re`, `ap_aus`, `re_npd`, `ap_mad` (TRX),
   `valido` (ABR) y `cpass` (VU) se calculan una vez, en la escritura. Lo que depende de la T0 (ventanas, mismo
   MCC, mismo comercio) queda para la lectura.
5. **Índices globales cubiertos**, ordenados por `(cliente, ts)`: la lectura es un `INDEX RANGE SCAN` por tabla,
   sin `TABLE ACCESS`.
   - `TRX (cliente, ts, base, ap, re, ap_aus, re_npd, ap_mad, mcc, comercio, monto)`
   - `ABR (cliente, ts, valido)`
   - `VU (cliente, ts, cpass)`
6. **Hint `INDEX` en cada subconsulta**, en el bloque donde se define el alias. En una prueba en ATP, un hard parse
   eligió `TABLE ACCESS STORAGE FULL` y la lectura pasó de < 3 ms a ~80 ms de CPU; la hint mantiene el plan
   estable.
7. **Particiones diarias por `ts` (`INTERVAL`) solo para la retención**: un job diario elimina las particiones de
   más de 93 días con `DROP PARTITION … UPDATE GLOBAL INDEXES`, sin `DELETE`.
8. **`ts` como `DATE`, hora local al segundo**, que es la escala en que se miden las ventanas. Los límites de cada
   ventana se calculan en la aplicación y se pasan como binds (`:lo5`, `:lo1h`, `:lo3h`, `:lo24`, `:lo92`, `:hi`
   = T0 − 1 s, `:t0`).
9. **Nulo = nulo en MCC y comercio**, con `DECODE(x.mcc, :mcc, 1, 0) = 1` (un `=` en SQL no lo cumple).
10. **Idempotencia al grabar**: TRX por `ID_TRX` (PK; un duplicado no se cuenta dos veces). ABR y VU no tienen clave
    natural y el Excel cuenta dos veces un evento repetido; un `ID_EVENTO` opcional con índice único permite
    deduplicar cuando la fuente lo trae.

### 6.3 Rendimiento obtenido (referencia para no perder)

ATP con **8 ECPUs**, 100 mil clientes y 92 días de historia, carga simultánea de 900 escrituras/s (600 TRX + 200
ABR + 100 VU) y 600 lecturas/s:

| Operación | Tiempo en la base (`V$SQL`) |
| --- | ---: |
| Lectura de las 10 variables | ~6 ms (5,3 ms de CPU) |
| INSERT con commit (TRX) | ~1,1 ms |

La CPU de la lectura es el recurso que limita: con 3 ECPUs la misma carga no pasó. Al tiempo de base hay que sumar
la red entre OKE y el ATP (una ida y vuelta por scoring) y la llamada al modelo.

### 6.4 Cómo trabajar

**Fase 1: análisis (no cambies código todavía).** Entrega:

1. Cómo recibe hoy la API la transacción y qué campos de la T0 tiene disponibles (sección 4).
2. Qué tablas de TRX, ABR y VU existen en el Autonomous Database del proyecto, con un mapeo columna a columna
   contra `db/01_schema.sql`. El *sentinela* nombra las columnas de entrada como en las fuentes
   (`N_ID_CUSTOMER`, `D_TRANSMISSION_DATE_AND_TIME`, `F_AMOUNT_TRANSACTION`…); los nombres de cada columna están en
   los comentarios del DDL.
3. Quién graba los eventos en esas tablas (la propia API, un ETL, una replicación) y, por lo tanto, dónde se pueden
   calcular las columnas de clasificación. Si la escritura no pasa por código propio, evalúa columnas virtuales
   de Oracle (son indexables) o una columna calculada en la carga, siempre que el índice cubierto se mantenga.
4. El tipo y la zona horaria de las columnas de fecha, y cómo llegar a "hora local de Santiago al segundo".
5. Las diferencias de reglas que encuentres frente al entrenamiento del modelo (sección 5.5), si este repositorio
   tiene el código o SQL de entrenamiento.
6. Un plan de cambios y la lista de preguntas abiertas. **Espera aprobación antes de implementar.**

**Fase 2: implementación.** Adapta (no copies a ciegas) el DDL, la clasificación al grabar, la consulta de
`read_sql.py` y las reglas de `variables.py` a la estructura de este proyecto. Mantén las decisiones de 6.2.
Guarda la versión de la definición (`excel-2026-11-13`, o una nueva si cambias alguna regla) junto con el score,
para saber con qué reglas se calculó.

**Fase 3: verificación.**

1. Los **6 casos del Excel** (`excel_casos.json`): los 60 valores tienen que coincidir.
2. Los escenarios de borde de `scenarios.py`: eventos exactamente en el límite de cada ventana, montos nulos, MCC y
   comercio nulos, cliente sin historia.
3. El plan de ejecución real de la consulta: tres `INDEX RANGE SCAN`, ningún `TABLE ACCESS` (ver `db/plan.py`).
4. Si cambias una regla, cámbiala también, de forma independiente, en la referencia de prueba, y confirma que las
   dos versiones coinciden.
5. Latencia de la consulta en el ATP del proyecto, comparada con la sección 6.3.

### 6.5 Restricciones

- No ejecutes nada del zip contra bases compartidas: las pruebas de integración del *sentinela* **vacían** las
  tablas `TRX`, `ABR` y `VU`.
- No cambies una regla de cálculo sin registrarla como decisión, porque altera lo que recibe el modelo.
- No incluyas credenciales en el código ni en los commits.
