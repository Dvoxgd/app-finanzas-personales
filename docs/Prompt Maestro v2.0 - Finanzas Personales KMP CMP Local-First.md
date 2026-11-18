# PROMPT MAESTRO v2.0: Gestor de Finanzas Personales Móvil «Local-First» (KMP + CMP)

*Versión 2.0. Reemplaza íntegramente a la v1. Incorpora todos los hallazgos P0/P1/P2 de la revisión de arquitectura, las decisiones de diseño resueltas, el plan de acción (ADRs, Context Map, DDL real, criterios de aceptación por fase) y conserva todo el alcance funcional original.*

---

## 1. Cómo leer y usar este documento

**Normativa.** DEBE / NO DEBE = obligatorio y verificable. DEBERÍA = recomendado salvo ADR que lo justifique. Ante ambigüedad prevalece §4 (estándares bancarios) y luego §6 (reglas de negocio). Si detectas una contradicción, DEBES detenerte y listarla en lugar de improvisar.

**Contrato de entrega.** Entrega por fases (§16). Cada fase: código 100% compilable, sin `TODO`, sin placeholders, sin fragmentos omitidos, con tests y criterios de aceptación cumplidos, y con la lista de archivos creados o modificados. No simplificar ni omitir ninguna regla.

**Organización recomendada de documentación en el repositorio** (este prompt se divide en artefactos verificables):

- `docs/domain.md` (§5 y §6): reglas e invariantes de dominio.
- `docs/adr/*.md` (§17): decisiones de infraestructura.
- `docs/context-map.md` (§5.3): puertos y responsables.
- DDL real en archivos `.sq` / `.sqm` de SQLDelight (§8). Nunca pegar DDL en prompts sin saltos de línea: un comentario `--` se comería la sentencia siguiente.

### 1.1 Registro de decisiones ya resueltas (no reabrir sin ADR)

| ID | Decisión |
| --- | --- |
| D-01 | **SQLDelight** es el único motor de acceso a datos. Se elimina la alternativa Room KMP. |
| D-02 | **Monomoneda por instalación** (MVP): la moneda se fija en `app_settings.default_currency_code` y toda transacción/presupuesto debe coincidir. `Money` conserva la moneda por seguridad de tipos y su exponente decimal sale de `CurrencyCode` (JPY=0, PEN/USD=2, KWD=3). Multimoneda es *no objetivo* con ganchos documentados (§18). |
| D-03 | Los IDs son **UUIDv7** (ordenables en el tiempo) generados por la abstracción `IdGenerator`. |
| D-04 | Fechas de transacción: `occurred_at` (Instant UTC) + `time_zone_id` + `local_date` (`YYYY-MM-DD`, derivada con la zona vigente al registrar). La asignación a ciclo usa `local_date`. |
| D-05 | Se separan **estado de negocio** (`state`: OPEN/CLOSED en ciclos) y **ciclo de vida del registro** (`status`: ACTIVE/INACTIVE/DELETED). Existe matriz de transiciones reforzada por triggers (§8.4). |
| D-06 | La **interfaz** `TransactionRunner` vive en `:core:common`; la implementación SQLDelight en `:core:database`. |
| D-07 | Los adaptadores Anti-Corruption Layer viven en un módulo dedicado **`:integration`** (depende solo de `:domain` de los contextos), cableados en `:shared-app`. |
| D-08 | **CQRS ligero**: escritura por Agregados; lectura transversal (reportes, sync) por *Query Services* con DTOs publicados por cada contexto (Published Language). |
| D-09 | El backup usa un **envelope versionado** con AEAD y claves envueltas (§13.3). KDF obligatorio: **Argon2id**. |
| D-10 | La sincronización con Google Sheets es una **exportación unidireccional** (dispositivo → Sheets), no bidireccional. |
| D-11 | **Optimistic locking** con columna `version` en tablas mutables. |
| D-12 | **Auditoría append-only** (`audit_log`) con triggers que impiden UPDATE/DELETE sobre ella. |
| D-13 | Las correcciones a periodos cerrados se hacen con **transacciones de ajuste** en el ciclo abierto; no se reabren ciclos. |
| D-14 | `GoogleAuthProvider` y el interceptor Bearer pertenecen a `cloud-sync:data` (no a `core`). |
| D-15 | ZIP del `.xlsx`: método **STORE** (sin compresión) en `commonMain`; compresión Deflate solo vía `expect/actual` si un ADR lo aprueba. |

---

## 2. Rol y directiva principal

Actúa como **Arquitecto de Software Principal y Desarrollador Móvil Senior** especializado en **Kotlin Multiplatform (KMP)**, **Compose Multiplatform (CMP)**, **Clean Architecture**, **Domain-Driven Design (DDD)**, principios **SOLID** y **Estándares de Ingeniería Bancaria**.

Tu objetivo es diseñar e implementar de punta a punta un Gestor de Finanzas Personales Móvil «Local-First» para Android e iOS en un único repositorio multimodular, siguiendo estrictamente todas las especificaciones funcionales, reglas contables, esquema relacional, contratos de dominio y restricciones arquitectónicas de este documento, sin omitir ni simplificar ninguna regla.

---

## 3. Propósito, filosofía y paradigmas

1. **Naturaleza:** cliente móvil autónomo (*Standalone / Local-First*). Sin servidor central propio (costo de infraestructura $0, privacidad del usuario, 100% operativo offline).
2. **Plataformas:** Android e iOS en un único proyecto. **KMP** para toda la lógica (dominio, datos, criptografía, exportación, red) y **CMP** para la UI unificada.
3. **Estilo arquitectónico:** *monolito modular con base de datos compartida*. A nivel físico, `:core:database` es el **Shared Kernel** (dueño del esquema y las migraciones); a nivel lógico, los Bounded Contexts están desacoplados por puertos. Esto se declara explícitamente y se hace cumplir con Konsist (§12).
4. **Clean Architecture estricta:** separación física por módulos Gradle `:domain`, `:data`, `:ui`.
5. **DDD:** Bounded Contexts, Agregados ricos, Value Objects inmutables (`Money`, `BasisPoints`, `CurrencyCode`, `DateRange`, IDs tipados), Domain Services puros, UseCases atómicos.
6. **SOLID:** segregación de interfaces, inversión de dependencias entre contextos, responsabilidad única (exportación local ≠ sincronización en la nube), abierto/cerrado en modalidades de presupuesto.
7. **Presentación MVI:** `StateFlow<UiState>` inmutable por pantalla, `Intent` sellados y `Channel`/`SharedFlow` para `SideEffect` efímeros.
8. **Contrato de seguridad:** privacidad por defecto; todo dato que salga del dispositivo en claro (xlsx, sql, Sheets) requiere advertencia explícita al usuario (§13.5).

---

## 4. Estándares de ingeniería bancaria (reglas inquebrantables)

### 4.1 Prohibición absoluta de punto flotante

- Todo monto se almacena como `INTEGER` de 64 bits (`Long`) en **Unidades Menores** (ej. `S/. 800.50` = `80050`).
- Todo porcentaje se almacena como `INTEGER` en **Puntos Base** (`100.00% = 10000 bps`, `85.50% = 8550 bps`).
- Prohibido `Double`/`Float`/`REAL` en dominio, datos, mapeadores, exportadores y UI de entrada (el parseo de texto del usuario a `Money` se hace con aritmética decimal sobre cadenas/enteros).
- **Rango seguro:** `Money.MAX_MINOR = 100_000_000_000_000` (10^14). Esto garantiza que `monto × 10000` nunca desborde `Long`. Toda suma y multiplicación usa **aritmética verificada** (checked); un desborde devuelve `AppResult.Failure(MoneyError.Overflow)`, jamás un valor envuelto.
- `Money` no asume 2 decimales: el exponente proviene de `CurrencyCode`. Formateo y parseo dependen de él.

### 4.2 Redondeo bancario (Half-Even) con especificación exacta

Para `n >= 0`, `d > 0`: `q = n / d`, `r = n % d`. Si `r < d - r` → `q`; si `r > d - r` → `q + 1`; si `r == d - r` (empate exacto) → `q` si `q` es par, `q + 1` si es impar. (Se compara `r` contra `d - r` para evitar desborde de `2r`.) Se aplica a: cálculo del límite porcentual y cálculo de los bps de consumo. DEBE existir test con empates exactos (x.5) y *property-based tests*.

### 4.3 Borrado lógico universal e inmutabilidad histórica

- Ninguna tabla de negocio hace `DELETE` físico en operación normal. **Todas** las tablas tienen `status` y un trigger que prohíbe el `DELETE` físico (§8.5).
- Toda lectura operativa y cálculo filtra `status = 'ACTIVE'`. Los ciclos usan `status = 'ACTIVE'` y su `state IN ('OPEN','CLOSED')`.
- La **matriz de transiciones permitidas** por tabla está en §8.4 y se refuerza por trigger y por Agregado.

### 4.4 Inmutabilidad de ciclos cerrados

Un ciclo con `state = 'CLOSED'` queda bloqueado contablemente: no se insertan, editan ni eliminan lógicamente transacciones **ni presupuestos** de ese ciclo; no se modifican sus fechas; no se reabre (`CLOSED → OPEN` prohibido); no se elimina lógicamente. Se protege en el Agregado **y** con triggers. **Correcciones**: mediante transacciones de **ajuste** en un ciclo OPEN (campo `adjusts_transaction_id`), nunca reabriendo (D-13).

### 4.5 Inyección de abstracciones no deterministas

Prohibido en UseCases, Domain Services y Agregados: `Clock.System.now()`, `TimeZone.currentSystemDefault()`, generación directa de UUID/random. Se inyectan `ClockProvider`, `TimeZoneProvider` e `IdGenerator` (UUIDv7). Pruebas deterministas obligatorias: 29 de febrero, cierres de mes (28/29/30/31), cambio de zona horaria y cambio de DST.

### 4.6 Trazabilidad y auditoría

- Tabla `audit_log` **append-only**: entidad, id, acción, valor anterior/nuevo (JSON), `occurred_at`, `device_id`. Triggers impiden `UPDATE` y `DELETE` sobre ella.
- Todo cambio de estado y toda edición de transacción/presupuesto/ciclo/categoría genera un registro de auditoría dentro de la **misma** transacción de BD.

### 4.7 Concurrencia

Hay UI, sync en segundo plano y backup concurrentes. Las tablas mutables tienen `version INTEGER NOT NULL` (optimistic locking): todo `UPDATE` exige `WHERE id = :id AND version = :expected` y suma 1. Toda secuencia *check-then-act* ocurre dentro de **un mismo** `TransactionRunner.run { }`. Conflicto → `DomainError.ConcurrentModification`.

### 4.8 Defensa en profundidad con errores tipados

Las reglas críticas se duplican en dominio y triggers. Los triggers abortan con códigos `ERR_*` estables; el módulo `:core:database` mapea el código (extraído por prefijo `ERR_[A-Z_]+`, nunca comparando el mensaje completo) a un `DomainError` tipado. DEBEN existir **tests de contrato** que ejecutan cada escenario inválido contra el dominio y contra los triggers y verifican que ambos rechazan.

### 4.9 Retención y derecho al olvido

- Los *tombstones* (`DELETED`) se conservan hasta confirmar backup/exportación; una política de purga opcional (`PurgeTombstonesUseCase`) puede eliminarlos físicamente **solo** desde una ruta administrativa que deshabilita temporalmente el trigger de borrado dentro de una transacción auditada, y solo para registros sin referencias.
- «Borrado total de datos» = **crypto-erase**: destruir la clave de la BD en Keystore/Keychain, eliminar archivos y revocar tokens.

---

## 5. Diseño estratégico DDD

### 5.1 Lenguaje ubicuo

**Ciclo** (periodo financiero personalizado), **Día de corte**, **Transacción**, **Ajuste** (transacción correctiva que referencia otra), **Categoría / Subcategoría**, **Medio de pago**, **Presupuesto** (meta temporal por ciclo+categoría), **Roll-up** (acumulado de padre+hijas), **Límite**, **Umbral de alerta**, **Veredicto de gasto** (`SpendingVerdict`), **Tombstone** (registro `DELETED`).

### 5.2 Clasificación de subdominios

| Contexto | Tipo | Relación |
| --- | --- | --- |
| `accounting` | **Core** | Downstream de `cycle` |
| `budgeting` | **Core** | Cliente/Proveedor con `accounting` |
| `cycle` | Soporte (calendario financiero) | Upstream de `accounting` y `budgeting` |
| `reporting-local` | Soporte | Conformista; consume Published Language |
| `cloud-sync`, `security` | Genérico | Sin lógica de negocio propia |

### 5.3 Context Map y puertos (documento `docs/context-map.md`)

| Puerto | Definido en | Implementado por (ACL) | Propósito |
| --- | --- | --- | --- |
| `CycleLookupPort` | `accounting:domain` | `:integration` sobre `CycleRepository` | Inferir ciclo por `local_date`, validar `OPEN` |
| `TransactionSpendingPolicy` | `accounting:domain` | `:integration` sobre `BudgetEvaluationEngine` | Veredicto de gasto no bloqueante |
| `BudgetCycleLookupPort` | `budgeting:domain` | `:integration` sobre `CycleRepository` | Validar que el ciclo del presupuesto existe y está OPEN |
| `CategoryLookupPort` | `budgeting:domain` | `:integration` sobre repositorio de categorías | Validar categoría EXPENSE/ACTIVE y obtener ancestros |
| `CategorySpendingReader` | `budgeting:domain` | `budgeting:data` (lectura SQL del Shared Kernel) | Roll-up de gasto e ingresos del ciclo |
| `CycleSnapshotProvider` | `cycle:domain` (Published Language) | `cycle:data` | Reportes y sync leen ciclos |
| `TransactionExportQuery` | `accounting:domain` (Published Language) | `accounting:data` | Reportes y sync leen transacciones y catálogos |
| `BudgetExecutionQuery` | `budgeting:domain` (Published Language) | `budgeting:data` | «Presupuestos vs. Ejecución» (dueño: budgeting) |

Regla: el ACL **traduce**. `SpendingVerdict` es un tipo propio de `accounting:domain`; `:integration` lo construye desde los resultados del engine de budgeting. Ningún tipo de `budgeting:domain` cruza hacia `accounting`.

### 5.4 Agregados e invariantes clave

- **`Transaction`** (raíz): `amount > 0`, `type == category.type`, categoría y medio de pago ACTIVE al insertar, ciclo OPEN, moneda == moneda de la instalación.
- **`Category`** (raíz, con subcategorías referenciadas por `parent_id`): profundidad ≤ 2, `child.type == parent.type`, no cambia de `type` si tiene transacciones, borrado lógico en cascada transaccional.
- **`Budget`** (raíz): solo categorías EXPENSE, único por `(cycle, category)` no eliminado, ciclo OPEN.
- **`Cycle` + `CycleContinuityService`** (Domain Service): la continuidad involucra varios ciclos, por lo que ningún `Cycle` individual puede garantizarla; **toda** modificación de fechas pasa por el servicio (prohibido `cycle.copy(endDate = ...)` fuera de él) y existe `verifyContinuity()` ejecutable en tests y al arrancar la app.

---

## 6. Requisitos funcionales y reglas de negocio por Bounded Context

### A. Ciclos financieros personalizados (`:features:cycle`)

**A.1 Desacoplamiento del mes calendario.** El usuario configura un día de corte mensual (`billing_cutoff_day`, 1..31, ej. 25).

**A.2 Cálculo del corte (`CutoffCalculator`, puro y testeable).**

- Inicio de ciclo del mes M = `LocalDate(Y, M, min(cutoffDay, diasDelMes(Y, M)))` (*clamp*: corte 31 en febrero → 28 o 29).
- Un ciclo va desde su inicio hasta el día anterior al inicio del siguiente. Ej. corte 25: `2026-10-25 → 2026-11-24`. Ej. corte 31: `2027-01-31 → 2027-02-27`, `2027-02-28 → 2027-03-30`; en año bisiesto el inicio de febrero es el 29.

**A.3 Primer ciclo.** No tiene predecesor. En el onboarding se propone el ciclo que contiene la fecha local de hoy; el usuario puede elegir libremente su `start_date` (el primer ciclo es el único cuyo inicio es libre).

**A.4 Generación recurrente (`EnsureCyclesUseCase`).** Al iniciar y al volver a primer plano garantiza que existan el ciclo que contiene hoy y el siguiente. El nuevo ciclo inicia en `ultimo.endDate + 1 día` y termina el día anterior al siguiente corte estrictamente posterior a su inicio. Es idempotente.

**A.5 Cambio del día de corte global.** Solo afecta a ciclos futuros (`start_date > hoy`), `is_manual_override = 0` y `state = OPEN`, reajustándolos mediante `CycleContinuityService`. Los ciclos iniciados o con override manual no se tocan.

**A.6 Sobrescritura manual (`is_manual_override = 1`).** Si el corte cae en feriado o fin de semana, el usuario puede cambiar `start_date`/`end_date` de un ciclo sin alterar la regla global.

**A.7 Invariante de continuidad.** Para los ciclos con `status != 'DELETED'` ordenados por `start_date`:

`para todo i: Cycle[i].endDate + 1 día = Cycle[i+1].startDate` (sin solapamientos ni días huérfanos).

`CycleContinuityService` (Domain Service) es el **único** punto autorizado para modificar fechas. Operaciones y reglas:

- **Ajustar fin de un ciclo:** nuevo `end >= start`; si existe siguiente, debe estar `ACTIVE` y `OPEN`, y `nuevoEnd + 1 <= siguiente.end` (no puede quedar vacío); el servicio fija `siguiente.start = nuevoEnd + 1` y marca ambos como override, **atomicamente** dentro de `TransactionRunner`. Sin siguiente: se permite extender/acortar libremente.
- **Ajustar inicio de un ciclo:** simetría con el anterior (`anterior.end = nuevoStart - 1`); si no hay anterior (primer ciclo) el inicio es libre.
- **Vecino o ciclo `CLOSED`:** se rechaza con `CycleError.AdjacentCycleClosed` / `CycleError.CycleClosed`.
- **Acortar un ciclo con transacciones que quedan fuera de rango:** se permite (la asignación a ciclo es explícita); el resultado incluye `OutOfRangeTransactionsWarning(count)` y la UI ofrece reasignarlas. No es bloqueante.
- `verifyContinuity(): List<ContinuityViolation>` se ejecuta en tests, en el arranque (modo diagnóstico) y antes de exportar.

**A.8 Cierre (`CloseCycleUseCase`).** Solo desde `OPEN`. Permitido si `hoy > endDate` o con confirmación explícita (`force = true`). Es irreversible (§4.4), atómico y auditado.

**A.9 Eliminación lógica de ciclo.** Solo el **último** ciclo (por `start_date`), en `OPEN`, sin transacciones ni presupuestos `ACTIVE`. Los ciclos `DELETED` se ignoran en validaciones de continuidad. Eliminar un ciclo intermedio queda prohibido (dejaría un hueco).

**A.10 Asignación contable.** Toda transacción pertenece a un `cycle_id`. Por defecto se infiere de `local_date` mediante `CycleLookupPort`. El usuario puede reasignar manualmente a otro ciclo con `state = OPEN` y `status = ACTIVE`; si la fecha cae fuera del rango destino la UI advierte sin bloquear.

### B. Contabilidad, categorías y medios de pago (`:features:accounting`)

**B.1 Transacciones.**

- Tipos `INCOME` / `EXPENSE`; `amount_minor` en `(0, MAX_MINOR]`; moneda ISO-4217 igual a la de la instalación (D-02); `occurred_at` (Instant UTC), `time_zone_id` y `local_date` derivada con `TimeZoneProvider` (D-04); notas opcionales (máx. 1000 caracteres, normalizadas); referencias a `cycle_id`, `category_id`, `payment_method_id`.
- **Invariante de coherencia de tipo (LSP):** `transaction.type == category.type`. Una categoría no puede cambiar de `type` si tiene transacciones (§5.4).
- Al **crear**, categoría y medio de pago deben estar `ACTIVE`. Al **editar**, solo se revalida lo que cambia (una categoría pasada a `INACTIVE` no impide editar monto/notas de transacciones antiguas).
- Editar o eliminar lógicamente (`status = 'DELETED'`) solo en ciclos `OPEN`.
- Todo `UPDATE` usa `version` (§4.7) y genera auditoría (§4.6).

**B.2 Ajustes (D-13).** Una transacción con `adjusts_transaction_id` es una transacción normal en un ciclo `OPEN` que referencia a la original (posiblemente de un ciclo `CLOSED`). El vínculo es informativo y auditable. Los ingresos de ajuste se **excluyen** de la base de ingresos para presupuestos porcentuales (`adjusts_transaction_id IS NULL`). Se siembran dos categorías de sistema: «Ajuste (Ingreso)» y «Ajuste (Gasto)».

**B.3 Categorías y subcategorías.**

- Jerarquía autorreferencial por `parent_id`, **máximo 2 niveles** (Padre → Hija); `child.type == parent.type`; una categoría con hijas no puede convertirse en hija; mover una subcategoría revalida profundidad y tipo.
- Atributos: nombre, `type`, `icon`, `color_hex` (`#RRGGBB` o `#AARRGGBB`, validado en dominio y por `CHECK`), `status` (`ACTIVE`/`INACTIVE`/`DELETED`).
- Permanentes entre ciclos. Eliminar lógicamente un padre elimina lógicamente a sus hijas de forma transaccional; las transacciones históricas permanecen intactas. Los presupuestos de esas categorías en ciclos `OPEN` pasan a `DELETED` en la misma transacción; los de ciclos `CLOSED` no se modifican.
- `INACTIVE`: oculta para nuevas transacciones, visible en filtros históricos.

**B.4 Medios de pago.** `CASH`, `DEBIT`, `CREDIT`, `TRANSFER`, `OTHER`; `status` `ACTIVE`/`INACTIVE`/`DELETED`. Se siembran `Efectivo` y `Otro` al primer arranque.

### C. Control presupuestal y alertas no bloqueantes (`:features:budgeting`)

**C.1 Desacoplamiento.** La categoría clasifica; el `Budget` es una meta temporal para `(cycle_id, category_id)`. Solo categorías `EXPENSE`; solo ciclos `OPEN`; único por par entre registros no `DELETED`.

**C.2 Roll-up jerárquico.** Gasto ejecutado de una categoría padre = transacciones `ACTIVE` `EXPENSE` del padre **más** las de todas sus subcategorías (CTE recursiva de §8.7). Para una subcategoría, solo las propias.

**C.3 Modalidades (`BudgetTargetType`, Open/Closed).** Cada modalidad es una estrategia `LimitCalculator` registrada; agregar una modalidad no modifica el engine.

- `FIXED_AMOUNT`: `target_value_raw` en céntimos.
- `PERCENTAGE`: `target_value_raw` en bps (1..10000). `limite = HalfEven(ingresosActivosDelCiclo * target_value_bps / 10000)`, donde `ingresosActivosDelCiclo` suma `INCOME` + `ACTIVE` con `adjusts_transaction_id IS NULL`.
- Informativo (no bloqueante): si la suma de presupuestos porcentuales supera 10000 bps, la UI lo advierte.

**C.4 Precedencia con presupuestos en padre e hija.** Se evalúan **todos** los presupuestos aplicables (el de la propia categoría y el de sus ancestros) y el veredicto final es el **peor estado** (`BudgetExceeded > ThresholdWarning > WithinSafeLimits`), conservando el impacto del presupuesto más restrictivo. Sin presupuestos aplicables: `NoBudgetConfigured`.

**C.5 Motor (`BudgetEvaluationEngine`, función pura).**

Entradas: `gastoAcumulado` (ya **excluyendo** la transacción que se edita), `montoNuevo`, `ingresosCiclo`, presupuestos aplicables.

- `proyectado = gastoAcumulado + montoNuevo` (aritmética verificada).
- Si `limite == 0` (ej. presupuesto porcentual con ingresos 0): `proyectado > 0` → `BudgetExceeded`; `proyectado == 0` → `WithinSafeLimits`. Nunca hay división por cero.
- Estados con **comparación exacta en enteros** (sin redondeo):
  - `BudgetExceeded`: `proyectado > limite`.
  - `ThresholdWarning`: `proyectado * 10000 >= alert_threshold_bps * limite` y `proyectado <= limite`.
  - `WithinSafeLimits`: resto.
- `consumptionBps = HalfEven(proyectado * 10000 / limite)` se calcula **solo para mostrar**; nunca decide el estado.
- Sobregiro: `overBy = max(0, proyectado - limite)`.

**C.6 Contrato de edición (corrección del doble conteo).** El puerto del lado de Accounting es:

```kotlin
data class SpendingCheck(
    val cycleId: CycleId,
    val categoryId: CategoryId,
    val newAmount: Money,
    val replacingTransactionId: TransactionId? = null
)

data class SpendingImpact(
    val limit: Money,
    val projectedSpent: Money,
    val overBy: Money,
    val consumption: BasisPoints
)

sealed interface SpendingVerdict { // tipo propio de accounting:domain
    data object NoBudgetConfigured : SpendingVerdict
    data class WithinSafeLimits(val impact: SpendingImpact) : SpendingVerdict
    data class ThresholdWarning(val impact: SpendingImpact) : SpendingVerdict
    data class BudgetExceeded(val impact: SpendingImpact) : SpendingVerdict
    data class Unavailable(val reason: DomainError) : SpendingVerdict
}

interface TransactionSpendingPolicy {
    fun evaluate(check: SpendingCheck): Flow<SpendingVerdict>
}
```

El roll-up excluye `replacingTransactionId` (`AND t.id != :excludeTransactionId`). Si el gasto cambia de categoría o de ciclo, el alcance evaluado es el destino y la exclusión es inocua.

**C.7 Comportamiento no bloqueante y reactivo.** El veredicto se recalcula en tiempo real combinando: el flujo reactivo de gasto/ingresos/presupuestos (`asFlow()` de SQLDelight) y el monto tecleado (con *debounce* de 150-300 ms). Ante `ThresholdWarning` o `BudgetExceeded` la UI muestra un banner con el monto y porcentaje de sobregiro, pero `isSaveEnabled` depende **solo** de la validez del formulario, **nunca** del veredicto. Ante `Unavailable` se muestra un aviso discreto y el guardado sigue habilitado.

### D. Exportación local (`:features:reporting-local`)

**D.1 `.sql` (`SqlDumpGenerator`).**

- Cabecera con versión de esquema, versión de app y `generated_at`. Incluye DDL de tablas, **datos completos** (también `DELETED`/`INACTIVE`) y, al final, los triggers e índices, para que el script sea restaurable.
- Estructura: `BEGIN TRANSACTION; ... COMMIT;`. Orden de carga por dependencias: `app_settings`, `cycles` (todos insertados con `state = 'OPEN'`), categorías (padres antes que hijas), `payment_methods`, `budgets`, `transactions`, `audit_log`; después `UPDATE cycles SET state = 'CLOSED' WHERE id IN (...)` y finalmente la creación de triggers (así los triggers de inmutabilidad no bloquean la restauración).
- Escapado: `'` → `''`; `NULL` literal; saltos de línea y Unicode (UTF-8) conservados dentro del literal; enteros sin comillas; no hay BLOBs en el esquema. Todo valor pasa por una única función `SqlLiteral.of(value)` con tests de inyección.
- Lectura paginada por *keyset* (500 filas) y escritura en *streaming* con `okio.BufferedSink`; memoria O(1).
- Entrega al sistema de archivos: Storage Access Framework (Android) / `UIDocumentPickerViewController` (iOS) mediante `FileExporter`.

**D.2 `.xlsx` (`OpenXmlExcelGenerator`, `commonMain`).**

- Contenedor ZIP escrito por un `ZipWriter` propio con método **STORE** y *data descriptor* (CRC32 calculado en streaming) (D-15); sin Apache POI; sobre `okio`; en `Dispatchers.Default`/`IO`; emite progreso por `Flow`.
- Memoria O(1): sin tabla de *shared strings*; todas las cadenas como `inlineStr`. Los montos se escriben como número decimal generado **por enteros** (`80050` con exponente 2 → `800.50`), con formato de celda `#,##0.00`, nunca vía `Double`.
- Seguridad: las cadenas nunca se escriben como fórmulas; se escapa XML y se eliminan caracteres inválidos de XML 1.0; nombres de hoja ≤ 31 caracteres sin `[]:*?/\`.
- 4 pestañas formateadas:
  1. **Resumen de Ciclo:** Ingresos, Gastos, Ahorro Neto, Estado del Ciclo.
  2. **Transacciones Detalladas:** Fecha, Ciclo, Categoría Padre, Subcategoría, Medio de Pago, Tipo, Monto decimal, `status`, Notas.
  3. **Presupuestos vs. Ejecución:** Categoría, Modalidad, Límite, Ejecutado, Diferencia, % de Consumo, Estado (origen: `BudgetExecutionQuery`).
  4. **Configuración y Catálogos:** Categorías, Medios de Pago y Reglas de Corte con su `status`.

**D.3 Origen de datos (CQRS ligero, D-08).** `reporting-local:domain` depende solo de puertos de lectura publicados: `CycleSnapshotProvider`, `TransactionExportQuery`, `BudgetExecutionQuery`. Nunca de repositorios de escritura ni de tablas ajenas.

**D.4 Advertencia de privacidad.** Antes de exportar se informa que el archivo sale **sin cifrar**; se ofrece «Proteger con contraseña» (envelope de §13.3) como opción.

### E. Sincronización cloud y backups (`:features:cloud-sync`)

**E.1 OAuth 2.0 con PKCE (sin backend intermedio).** Alcance mínimo `https://www.googleapis.com/auth/drive.file` (y el alcance mínimo de Sheets estrictamente necesario, justificado en un ADR). `GoogleAuthProvider` (`expect/actual`, en `cloud-sync:data`): Google Identity Services `AuthorizationClient` en Android; `ASWebAuthenticationSession` con PKCE (`code_verifier` de 43-128 caracteres, `S256`, parámetro `state` verificado) en iOS. Tokens en `AndroidKeyStore`/iOS `Keychain`; refresco automático; revocación al cerrar sesión. No existen secretos de cliente embebidos.

**E.2 Backup cifrado a Google Drive y restauración.** Flujo completo en §13.3 y §13.4 (incluye restore, que es parte del alcance).

**E.3 Google Sheets v4 (`GoogleSheetsApi`) como exportación unidireccional (D-10).**

- Ktor Client + `kotlinx.serialization`. Se escribe con `valueInputOption = RAW` (nunca `USER_ENTERED`) para impedir inyección de fórmulas.
- Una pestaña por tabla; columna A = `id`; el mapa `id → número de fila` se persiste en `sync_state`. Registros `status = 'DELETED'` se **marcan** (columna `status`), no se borran de la hoja.
- **Cursor monotónico:** el estado a sincronizar no depende de `updated_at` (sujeto a *clock skew*), sino de la tabla `sync_outbox` (revisión autoincremental poblada por triggers, §8.6). `sync_state` guarda el último `outbox_id` confirmado.
- Envíos por lotes con `values:batchUpdate` (cuotas por minuto de la API); *backoff* exponencial con *jitter* ante 429/5xx; idempotencia por `id`; reanudación tras errores parciales sin duplicar filas.
- Advertencia de privacidad previa: los datos quedan en claro en la cuenta de Google del usuario.

---

## 7. Núcleo compartido (Shared Kernel de tipos): `:core:common` y `:core:model`

### 7.1 `:core:model` (Value Objects puros, sin dependencias de plataforma)

- `CurrencyCode`: código ISO-4217 de 3 letras mayúsculas; expone `minorUnitExponent` (0, 2 o 3 según la moneda).
- `Money(minor: Long, currency: CurrencyCode)`: `0 <= minor <= MAX_MINOR` para montos de transacción (los resultados intermedios pueden ser mayores pero siempre dentro de `Long` mediante operaciones verificadas); operaciones `plus`, `minus`, `compareTo` que **rechazan** monedas distintas; `parse(text)` y `format(locale)` sin `Double`.
- `BasisPoints(value: Int)`: `>= 0`, sin tope superior (el consumo puede superar 10000); los presupuestos validan su propio rango (porcentaje 1..10000, umbral 100..10000).
- `RoundingHalfEven.divide(n: Long, d: Long): Long` (especificación exacta en §4.2).
- `DateRange(start: LocalDate, end: LocalDate)` inclusivo, con `contains`, `overlaps`, `lengthInDays`.
- `EntityStatus` (`ACTIVE`, `INACTIVE`, `DELETED`), `CycleState` (`OPEN`, `CLOSED`), `TransactionType` (`INCOME`, `EXPENSE`), `BudgetTargetType`, `PaymentMethodType`.
- IDs tipados `@JvmInline value class` (`CycleId`, `CategoryId`, `PaymentMethodId`, `TransactionId`, `BudgetId`) respaldados por UUIDv7 en texto.

### 7.2 `:core:common` (abstracciones inyectables y resultados)

```kotlin
interface ClockProvider { fun now(): Instant }
interface TimeZoneProvider { fun current(): TimeZone }
interface IdGenerator { fun newId(): String } // UUIDv7

interface TransactionRunner {
    // Los bloques anidados se unen a la transaccion exterior.
    // Cualquier excepcion o AppResult.Failure marcado como rollback revierte todo.
    suspend fun <T> run(block: suspend () -> T): T
}

interface AuditRecorder {
    suspend fun record(entry: AuditEntry) // se invoca DENTRO de TransactionRunner.run
}

sealed interface AppResult<out T, out E> {
    data class Success<T>(val value: T) : AppResult<T, Nothing>
    data class Failure<E>(val error: E) : AppResult<Nothing, E>
}
```

- `CoroutineDispatchers` (`io`, `default`, `main`) inyectable.
- Jerarquía `DomainError` sellada: `ConcurrentModification`, `CycleClosed`, `CycleNotOpen`, `AdjacentCycleClosed`, `ContinuityViolation`, `CategoryDepthExceeded`, `CategoryTypeMismatch`, `CategoryNotActive`, `PaymentMethodNotActive`, `CurrencyMismatch`, `InvalidTransition`, `PhysicalDeleteForbidden`, `MoneyOverflow`, `Validation(field, reason)`, `Unexpected(cause)`.
- La **interfaz** `TransactionRunner` está aquí (D-06) para que los Domain Services (`CycleContinuityService`) la usen sin romper la regla de dependencias; `:core:database` aporta la implementación.

---

## 8. Esquema de base de datos (SQLDelight + SQLCipher): entregable como archivos `.sq` / `.sqm`

**Responsabilidad.** `:core:database` es dueño del esquema, las consultas y las migraciones (Shared Kernel físico). Las consultas se organizan por contexto (`accounting/*.sq`, `cycle/*.sq`, `budgeting/*.sq`). Un módulo `:data` solo usa las consultas de su contexto más las lecturas cruzadas **explícitamente** listadas en `docs/context-map.md`; Konsist lo verifica.

### 8.1 Inicialización obligatoria de cada conexión

Orden estricto: primero `PRAGMA key` (SQLCipher), luego el resto. Estos PRAGMAs se aplican **al abrir la conexión**, nunca dentro de una transacción:

```sql
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA secure_delete = ON;
PRAGMA busy_timeout = 5000;
```

### 8.2 Convenciones

- Dinero `INTEGER` en unidades menores; porcentajes `INTEGER` en bps; fechas locales `TEXT` `YYYY-MM-DD`; instantes `TEXT` ISO-8601 UTC.
- Todas las tablas de negocio tienen `status`, `version`, `created_at`, `updated_at`.
- **Excepción documentada:** las tablas técnicas `sync_outbox`, `sync_state` y `sync_row_map` no son de negocio; permiten borrado físico para compactación tras confirmación de sincronización.
- Los errores de triggers usan el prefijo estable `ERR_*` (mapeo tipado, §4.8).

### 8.3 DDL completo (`1.sq` / baseline, versión de esquema 1)

```sql
-- 1. CONFIGURACION GLOBAL (una sola fila)
CREATE TABLE app_settings (
    id TEXT PRIMARY KEY NOT NULL CHECK(id = 'APP_SETTINGS'),
    billing_cutoff_day INTEGER NOT NULL DEFAULT 1 CHECK(billing_cutoff_day BETWEEN 1 AND 31),
    default_currency_code TEXT NOT NULL DEFAULT 'PEN' CHECK(length(default_currency_code) = 3 AND default_currency_code = upper(default_currency_code)),
    device_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE', 'INACTIVE', 'DELETED')),
    version INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- 2. CICLOS FINANCIEROS
-- state:  'OPEN' abierto a imputaciones; 'CLOSED' cerrado e inmutable.
-- status: 'ACTIVE' vigente; 'DELETED' anulado logicamente (ignorado en continuidad).
CREATE TABLE cycles (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    start_date TEXT NOT NULL CHECK(start_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
    end_date TEXT NOT NULL CHECK(end_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
    is_manual_override INTEGER NOT NULL DEFAULT 0 CHECK(is_manual_override IN (0, 1)),
    state TEXT NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN', 'CLOSED')),
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE', 'DELETED')),
    version INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CHECK(start_date <= end_date)
);
CREATE INDEX idx_cycles_dates_state_status ON cycles(start_date, end_date, state, status);
CREATE UNIQUE INDEX idx_cycles_unique_start ON cycles(start_date) WHERE status != 'DELETED';

-- 3. CATEGORIAS Y SUBCATEGORIAS (jerarquia maxima de 2 niveles)
CREATE TABLE categories (
    id TEXT PRIMARY KEY NOT NULL,
    parent_id TEXT,
    name TEXT NOT NULL,
    type TEXT NOT NULL CHECK(type IN ('INCOME', 'EXPENSE')),
    icon TEXT NOT NULL,
    color_hex TEXT NOT NULL CHECK(length(color_hex) IN (7, 9) AND substr(color_hex, 1, 1) = '#' AND substr(color_hex, 2) NOT GLOB '*[^0-9A-Fa-f]*'),
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE', 'INACTIVE', 'DELETED')),
    version INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (parent_id) REFERENCES categories(id) ON DELETE RESTRICT,
    CHECK(id != parent_id)
);
CREATE INDEX idx_categories_parent_status ON categories(parent_id, status);
CREATE INDEX idx_categories_type_status ON categories(type, status);

-- 4. MEDIOS DE PAGO
CREATE TABLE payment_methods (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    type TEXT NOT NULL CHECK(type IN ('CASH', 'DEBIT', 'CREDIT', 'TRANSFER', 'OTHER')),
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE', 'INACTIVE', 'DELETED')),
    version INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX idx_payment_methods_status ON payment_methods(status);

-- 5. PRESUPUESTOS POR CICLO Y CATEGORIA
-- target_value_raw: FIXED_AMOUNT -> centimos; PERCENTAGE -> puntos base (1..10000)
-- alert_threshold_bps: umbral de alerta (85.00% = 8500)
CREATE TABLE budgets (
    id TEXT PRIMARY KEY NOT NULL,
    cycle_id TEXT NOT NULL,
    category_id TEXT NOT NULL,
    target_type TEXT NOT NULL CHECK(target_type IN ('FIXED_AMOUNT', 'PERCENTAGE')),
    target_value_raw INTEGER NOT NULL CHECK(target_value_raw > 0),
    alert_threshold_bps INTEGER NOT NULL DEFAULT 8500 CHECK(alert_threshold_bps BETWEEN 100 AND 10000),
    description TEXT,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE', 'INACTIVE', 'DELETED')),
    version INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (cycle_id) REFERENCES cycles(id) ON DELETE RESTRICT,
    FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE RESTRICT,
    CHECK(target_type != 'PERCENTAGE' OR target_value_raw <= 10000)
);
CREATE UNIQUE INDEX idx_budgets_unique_active_cycle_category ON budgets(cycle_id, category_id) WHERE status != 'DELETED';
CREATE INDEX idx_budgets_cycle_status ON budgets(cycle_id, status);

-- 6. TRANSACCIONES CONTABLES
-- amount_minor: positivo, en centimos, maximo 10^14
-- occurred_at: Instant UTC; time_zone_id: zona vigente; local_date: fecha local derivada
-- adjusts_transaction_id: vinculo informativo de ajuste (D-13)
CREATE TABLE transactions (
    id TEXT PRIMARY KEY NOT NULL,
    cycle_id TEXT NOT NULL,
    category_id TEXT NOT NULL,
    payment_method_id TEXT NOT NULL,
    type TEXT NOT NULL CHECK(type IN ('INCOME', 'EXPENSE')),
    amount_minor INTEGER NOT NULL CHECK(amount_minor > 0 AND amount_minor <= 100000000000000),
    currency_code TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    time_zone_id TEXT NOT NULL,
    local_date TEXT NOT NULL CHECK(local_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
    notes TEXT CHECK(notes IS NULL OR length(notes) <= 1000),
    adjusts_transaction_id TEXT,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE', 'DELETED')),
    version INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (cycle_id) REFERENCES cycles(id) ON DELETE RESTRICT,
    FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE RESTRICT,
    FOREIGN KEY (payment_method_id) REFERENCES payment_methods(id) ON DELETE RESTRICT,
    FOREIGN KEY (adjusts_transaction_id) REFERENCES transactions(id) ON DELETE RESTRICT
);
CREATE INDEX idx_tx_cycle_type_status ON transactions(cycle_id, type, status);
CREATE INDEX idx_tx_cycle_category_status ON transactions(cycle_id, category_id, status);
CREATE INDEX idx_tx_date_status ON transactions(local_date DESC, status);
CREATE INDEX idx_tx_category ON transactions(category_id);

-- 7. AUDITORIA APPEND-ONLY (D-12)
CREATE TABLE audit_log (
    id TEXT PRIMARY KEY NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    action TEXT NOT NULL CHECK(action IN ('CREATE', 'UPDATE', 'STATUS_CHANGE', 'CLOSE', 'ADJUST', 'RESTORE', 'PURGE')),
    old_value TEXT,
    new_value TEXT,
    device_id TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status = 'ACTIVE'),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX idx_audit_entity ON audit_log(entity_type, entity_id, occurred_at);

-- 8. TABLAS TECNICAS DE SINCRONIZACION (exentas de status, ver 8.2)
CREATE TABLE sync_outbox (
    outbox_id INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    op TEXT NOT NULL CHECK(op IN ('INSERT', 'UPDATE')),
    occurred_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
CREATE TABLE sync_state (
    target TEXT PRIMARY KEY NOT NULL,
    remote_id TEXT,
    last_outbox_id INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL
);
CREATE TABLE sync_row_map (
    target TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    row_number INTEGER NOT NULL,
    PRIMARY KEY (target, entity_type, entity_id)
);
```

### 8.4 Matriz de transiciones permitidas (reforzada por Agregado y triggers)

| Tabla | Campo | Transiciones permitidas |
| --- | --- | --- |
| `cycles` | `state` | `OPEN → CLOSED` (irreversible) |
| `cycles` | `status` | `ACTIVE → DELETED` (solo último ciclo, `OPEN`, sin registros activos) |
| `categories`, `payment_methods`, `budgets` | `status` | `ACTIVE ↔ INACTIVE`; `ACTIVE/INACTIVE → DELETED` |
| `transactions` | `status` | `ACTIVE → DELETED` |
| todas | `status` | `DELETED` es **terminal** (no se reactiva; se crea un registro nuevo) |

### 8.5 Triggers de integridad e inmutabilidad (`ERR_*` estables)

```sql
-- ===== CICLOS =====
CREATE TRIGGER trg_cycles_forbid_physical_delete BEFORE DELETE ON cycles
BEGIN SELECT RAISE(ABORT, 'ERR_PHYSICAL_DELETE_FORBIDDEN: use status = DELETED'); END;

CREATE TRIGGER trg_cycles_closed_immutable BEFORE UPDATE ON cycles
WHEN OLD.state = 'CLOSED'
BEGIN SELECT RAISE(ABORT, 'ERR_CYCLE_CLOSED: un ciclo CLOSED no puede modificarse, reabrirse ni eliminarse'); END;

CREATE TRIGGER trg_cycles_no_delete_with_active_records BEFORE UPDATE OF status ON cycles
WHEN NEW.status = 'DELETED' AND OLD.status != 'DELETED'
BEGIN
  SELECT RAISE(ABORT, 'ERR_CYCLE_HAS_ACTIVE_RECORDS: el ciclo tiene transacciones o presupuestos activos')
  WHERE EXISTS (SELECT 1 FROM transactions WHERE cycle_id = OLD.id AND status = 'ACTIVE')
     OR EXISTS (SELECT 1 FROM budgets WHERE cycle_id = OLD.id AND status != 'DELETED');
END;

-- Solapamiento (la continuidad sin huecos la verifica CycleContinuityService al final de su transaccion).
-- El servicio DEBE ordenar sus UPDATE: al acortar un fin, primero el ciclo propio y luego el siguiente; al extender, primero el siguiente.
CREATE TRIGGER trg_cycles_no_overlap_insert BEFORE INSERT ON cycles
WHEN NEW.status != 'DELETED'
BEGIN
  SELECT RAISE(ABORT, 'ERR_CYCLE_OVERLAP: el ciclo se solapa con otro')
  WHERE EXISTS (SELECT 1 FROM cycles c WHERE c.status != 'DELETED' AND c.id != NEW.id AND c.start_date <= NEW.end_date AND c.end_date >= NEW.start_date);
END;

CREATE TRIGGER trg_cycles_no_overlap_update BEFORE UPDATE OF start_date, end_date, status ON cycles
WHEN NEW.status != 'DELETED'
BEGIN
  SELECT RAISE(ABORT, 'ERR_CYCLE_OVERLAP: el ciclo se solapa con otro')
  WHERE EXISTS (SELECT 1 FROM cycles c WHERE c.status != 'DELETED' AND c.id != NEW.id AND c.start_date <= NEW.end_date AND c.end_date >= NEW.start_date);
END;

-- ===== CATEGORIAS =====
CREATE TRIGGER trg_categories_forbid_physical_delete BEFORE DELETE ON categories
BEGIN SELECT RAISE(ABORT, 'ERR_PHYSICAL_DELETE_FORBIDDEN: use status = DELETED'); END;

CREATE TRIGGER trg_category_depth_and_type_insert BEFORE INSERT ON categories
WHEN NEW.parent_id IS NOT NULL
BEGIN
  SELECT RAISE(ABORT, 'ERR_CATEGORY_DEPTH: maximo 2 niveles')
  WHERE (SELECT parent_id FROM categories WHERE id = NEW.parent_id) IS NOT NULL;
  SELECT RAISE(ABORT, 'ERR_CATEGORY_TYPE_MISMATCH: la subcategoria debe tener el tipo del padre')
  WHERE (SELECT type FROM categories WHERE id = NEW.parent_id) != NEW.type;
END;

CREATE TRIGGER trg_category_depth_and_type_update BEFORE UPDATE OF parent_id, type ON categories
WHEN NEW.parent_id IS NOT NULL
BEGIN
  SELECT RAISE(ABORT, 'ERR_CATEGORY_DEPTH: maximo 2 niveles')
  WHERE (SELECT parent_id FROM categories WHERE id = NEW.parent_id) IS NOT NULL
     OR EXISTS (SELECT 1 FROM categories WHERE parent_id = NEW.id AND status != 'DELETED');
  SELECT RAISE(ABORT, 'ERR_CATEGORY_TYPE_MISMATCH: la subcategoria debe tener el tipo del padre')
  WHERE (SELECT type FROM categories WHERE id = NEW.parent_id) != NEW.type;
END;

CREATE TRIGGER trg_category_type_immutable BEFORE UPDATE OF type ON categories
WHEN NEW.type != OLD.type
BEGIN
  SELECT RAISE(ABORT, 'ERR_CATEGORY_TYPE_LOCKED: la categoria tiene transacciones o hijas')
  WHERE EXISTS (SELECT 1 FROM transactions WHERE category_id = OLD.id)
     OR EXISTS (SELECT 1 FROM categories WHERE parent_id = OLD.id);
END;

-- Cascada logica padre -> hijas (incrementa version)
CREATE TRIGGER trg_cascade_soft_delete_subcategories AFTER UPDATE OF status ON categories
WHEN NEW.status = 'DELETED' AND OLD.status != 'DELETED'
BEGIN
  UPDATE categories SET status = 'DELETED', version = version + 1, updated_at = NEW.updated_at
  WHERE parent_id = NEW.id AND status != 'DELETED';
END;

-- ===== MEDIOS DE PAGO =====
CREATE TRIGGER trg_payment_methods_forbid_physical_delete BEFORE DELETE ON payment_methods
BEGIN SELECT RAISE(ABORT, 'ERR_PHYSICAL_DELETE_FORBIDDEN: use status = DELETED'); END;

-- ===== PRESUPUESTOS =====
CREATE TRIGGER trg_budgets_forbid_physical_delete BEFORE DELETE ON budgets
BEGIN SELECT RAISE(ABORT, 'ERR_PHYSICAL_DELETE_FORBIDDEN: use status = DELETED'); END;

CREATE TRIGGER trg_budgets_insert_guard BEFORE INSERT ON budgets
BEGIN
  SELECT RAISE(ABORT, 'ERR_CYCLE_NOT_OPEN: solo se registran presupuestos en ciclos OPEN')
  WHERE (SELECT state FROM cycles WHERE id = NEW.cycle_id) != 'OPEN' OR (SELECT status FROM cycles WHERE id = NEW.cycle_id) != 'ACTIVE';
  SELECT RAISE(ABORT, 'ERR_BUDGET_CATEGORY_INVALID: el presupuesto exige categoria EXPENSE y ACTIVE')
  WHERE (SELECT type FROM categories WHERE id = NEW.category_id) != 'EXPENSE' OR (SELECT status FROM categories WHERE id = NEW.category_id) != 'ACTIVE';
END;

CREATE TRIGGER trg_budgets_update_guard BEFORE UPDATE ON budgets
BEGIN
  SELECT RAISE(ABORT, 'ERR_CYCLE_CLOSED: no se modifican presupuestos de un ciclo CLOSED')
  WHERE (SELECT state FROM cycles WHERE id = OLD.cycle_id) = 'CLOSED' OR (SELECT state FROM cycles WHERE id = NEW.cycle_id) != 'OPEN';
END;

-- ===== TRANSACCIONES =====
CREATE TRIGGER trg_tx_forbid_physical_delete BEFORE DELETE ON transactions
BEGIN SELECT RAISE(ABORT, 'ERR_PHYSICAL_DELETE_FORBIDDEN: use status = DELETED'); END;

CREATE TRIGGER trg_tx_insert_guard BEFORE INSERT ON transactions
BEGIN
  SELECT RAISE(ABORT, 'ERR_CYCLE_NOT_OPEN: solo se registran transacciones en un ciclo OPEN')
  WHERE (SELECT state FROM cycles WHERE id = NEW.cycle_id) != 'OPEN' OR (SELECT status FROM cycles WHERE id = NEW.cycle_id) != 'ACTIVE';
  SELECT RAISE(ABORT, 'ERR_TYPE_MISMATCH: transaction.type debe igualar category.type')
  WHERE (SELECT type FROM categories WHERE id = NEW.category_id) != NEW.type;
  SELECT RAISE(ABORT, 'ERR_CATEGORY_NOT_ACTIVE: la categoria no esta ACTIVE')
  WHERE (SELECT status FROM categories WHERE id = NEW.category_id) != 'ACTIVE';
  SELECT RAISE(ABORT, 'ERR_PAYMENT_METHOD_NOT_ACTIVE: el medio de pago no esta ACTIVE')
  WHERE (SELECT status FROM payment_methods WHERE id = NEW.payment_method_id) != 'ACTIVE';
  SELECT RAISE(ABORT, 'ERR_CURRENCY_MISMATCH: moneda distinta a la de la instalacion')
  WHERE NEW.currency_code != (SELECT default_currency_code FROM app_settings LIMIT 1);
END;

CREATE TRIGGER trg_tx_update_guard BEFORE UPDATE ON transactions
BEGIN
  SELECT RAISE(ABORT, 'ERR_CYCLE_CLOSED: no se modifican ni eliminan transacciones de un ciclo CLOSED')
  WHERE (SELECT state FROM cycles WHERE id = OLD.cycle_id) = 'CLOSED' OR (SELECT state FROM cycles WHERE id = NEW.cycle_id) != 'OPEN';
  SELECT RAISE(ABORT, 'ERR_TYPE_MISMATCH: transaction.type debe igualar category.type')
  WHERE (SELECT type FROM categories WHERE id = NEW.category_id) != NEW.type;
  SELECT RAISE(ABORT, 'ERR_CATEGORY_NOT_ACTIVE: la categoria no esta ACTIVE')
  WHERE NEW.category_id != OLD.category_id AND (SELECT status FROM categories WHERE id = NEW.category_id) != 'ACTIVE';
  SELECT RAISE(ABORT, 'ERR_PAYMENT_METHOD_NOT_ACTIVE: el medio de pago no esta ACTIVE')
  WHERE NEW.payment_method_id != OLD.payment_method_id AND (SELECT status FROM payment_methods WHERE id = NEW.payment_method_id) != 'ACTIVE';
  SELECT RAISE(ABORT, 'ERR_CURRENCY_MISMATCH: moneda distinta a la de la instalacion')
  WHERE NEW.currency_code != (SELECT default_currency_code FROM app_settings LIMIT 1);
END;

-- ===== CONFIGURACION =====
CREATE TRIGGER trg_settings_currency_locked BEFORE UPDATE OF default_currency_code ON app_settings
WHEN NEW.default_currency_code != OLD.default_currency_code
BEGIN
  SELECT RAISE(ABORT, 'ERR_CURRENCY_LOCKED: no se cambia la moneda con transacciones existentes')
  WHERE EXISTS (SELECT 1 FROM transactions);
END;

-- ===== AUDITORIA APPEND-ONLY =====
CREATE TRIGGER trg_audit_no_update BEFORE UPDATE ON audit_log
BEGIN SELECT RAISE(ABORT, 'ERR_AUDIT_IMMUTABLE: audit_log es append-only'); END;
CREATE TRIGGER trg_audit_no_delete BEFORE DELETE ON audit_log
BEGIN SELECT RAISE(ABORT, 'ERR_AUDIT_IMMUTABLE: audit_log es append-only'); END;

-- ===== TRANSICIONES TERMINALES Y VERSIONADO (cycles, categories, payment_methods, budgets, transactions) =====
-- Repetir para cada tabla T de la lista:
CREATE TRIGGER trg_T_terminal_deleted BEFORE UPDATE ON T
WHEN OLD.status = 'DELETED' AND NEW.status != 'DELETED'
BEGIN SELECT RAISE(ABORT, 'ERR_INVALID_TRANSITION: DELETED es terminal'); END;
CREATE TRIGGER trg_T_version_guard BEFORE UPDATE ON T
WHEN NEW.version != OLD.version + 1
BEGIN SELECT RAISE(ABORT, 'ERR_VERSION_CONFLICT: version obsoleta (optimistic locking)'); END;

-- ===== OUTBOX DE SINCRONIZACION (repetir para cada tabla T de la lista anterior; E = nombre de entidad) =====
CREATE TRIGGER trg_T_outbox_insert AFTER INSERT ON T
BEGIN INSERT INTO sync_outbox(entity_type, entity_id, op) VALUES ('E', NEW.id, 'INSERT'); END;
CREATE TRIGGER trg_T_outbox_update AFTER UPDATE ON T
BEGIN INSERT INTO sync_outbox(entity_type, entity_id, op) VALUES ('E', NEW.id, 'UPDATE'); END;
```

**Instrucción de implementación:** los bloques marcados «repetir para cada tabla T» se materializan explícitamente (sin plantillas ni macros) para las cinco tablas de negocio en el archivo `.sq`; el LLM NO debe dejar `T` ni `E` literales.

### 8.6 Consultas de referencia

```sql
-- Roll-up de gasto de una categoria + subcategorias en un ciclo, excluyendo la transaccion en edicion.
-- Si SUM desborda, SQLite lanza error: mapear a MoneyOverflow.
WITH RECURSIVE category_tree(id) AS (
    SELECT id FROM categories WHERE id = :targetCategoryId AND status != 'DELETED'
    UNION ALL
    SELECT c.id FROM categories c
    INNER JOIN category_tree ct ON c.parent_id = ct.id
    WHERE c.status != 'DELETED'
)
SELECT COALESCE(SUM(t.amount_minor), 0) AS total_spent_minor
FROM transactions t
WHERE t.cycle_id = :cycleId
  AND t.type = 'EXPENSE'
  AND t.status = 'ACTIVE'
  AND (:excludeTransactionId IS NULL OR t.id != :excludeTransactionId)
  AND t.category_id IN (SELECT id FROM category_tree);

-- Base de ingresos del ciclo (excluye ajustes)
SELECT COALESCE(SUM(amount_minor), 0) AS total_income_minor
FROM transactions
WHERE cycle_id = :cycleId AND type = 'INCOME' AND status = 'ACTIVE' AND adjusts_transaction_id IS NULL;

-- Presupuestos aplicables a una categoria (propia y ancestro directo, profundidad maxima 2)
SELECT b.* FROM budgets b
WHERE b.cycle_id = :cycleId AND b.status = 'ACTIVE'
  AND b.category_id IN (:categoryId, (SELECT parent_id FROM categories WHERE id = :categoryId));
```

### 8.7 Migraciones y versionado

- Esquema baseline = versión 1 (`1.sq`); cada cambio es un `N.sqm` versionado con **test de migración** (BD vieja → nueva con datos de ejemplo, verificando invariantes tras migrar).
- `PRAGMA user_version` y la versión de esquema viajan en backups y en la cabecera del `.sql`.
- Siembra inicial (`SeedDefaultsUseCase`, idempotente): `app_settings`, medios `Efectivo`/`Otro`, categorías de sistema de ajuste y un set básico de categorías.
- `:core:database` mapea códigos `ERR_*` → `DomainError` (§4.8).

---

## 9. Arquitectura multimódulo y estructura de directorios

Vertical Slices por feature combinados con capas horizontales de Clean Architecture.

```text
root/
├── build-logic/                     # Convention Plugins (Gradle Kotlin DSL) para KMP unificado + libs.versions.toml con versiones fijadas
├── docs/                            # domain.md, context-map.md, adr/*.md
├── core/
│   ├── common/                      # AppResult, DomainError, CoroutineDispatchers, ClockProvider, TimeZoneProvider, IdGenerator (UUIDv7), TransactionRunner (INTERFAZ), AuditRecorder (interfaz)
│   ├── model/                       # Money, BasisPoints, CurrencyCode, DateRange, RoundingHalfEven, EntityStatus, CycleState, Typed IDs (@JvmInline)
│   ├── database/                    # Esquema .sq/.sqm SQLDelight, SQLCipher DriverFactory, TransactionRunnerImpl, SqlErrorMapper (ERR_* -> DomainError), AuditRecorderImpl, VacuumBackupExecutor, migraciones
│   ├── network/                     # Ktor HttpClientFactory generico, serializadores, politica de reintentos (SIN logica de Google)
│   ├── security/                    # Vault (Keystore/Keychain), DatabaseKeyManager, KDF Argon2id (expect/actual), AEAD, BackupEnvelope (formato) (SIN logica de Google)
│   └── designsystem/                # Tokens UI, tema Material3/Cupertino adaptativo, MoneyTextField, AlertBanner (generico, severidad INFO/WARNING/ERROR)
│
├── features/
│   ├── cycle/
│   │   ├── domain/                  # Cycle (Aggregate), CutoffCalculator, CycleContinuityService, CycleRepository, CycleSnapshotProvider (Published Language), UseCases (EnsureCycles, AdjustCycleEnd/Start, CloseCycle, DeleteLastCycle, ChangeCutoffDay)
│   │   ├── data/                    # CycleRepositoryImpl, CycleMapper, CycleSnapshotProviderImpl
│   │   └── ui/                      # CycleContract (State, Intent, SideEffect), CycleViewModel, CycleScreen, cycleGraph()
│   ├── accounting/
│   │   ├── domain/                  # Transaction (Aggregate), Category, PaymentMethod, SpendingCheck/SpendingVerdict, CycleLookupPort, TransactionSpendingPolicy (puertos), TransactionExportQuery, UseCases
│   │   ├── data/                    # Repositories, Mappers, queries jerarquicas
│   │   └── ui/                      # TransactionFormViewModel (MVI reactivo con alerta no bloqueante), CategoryScreen, accountingGraph()
│   ├── budgeting/
│   │   ├── domain/                  # Budget (Aggregate), BudgetEvaluationEngine, LimitCalculator (estrategias), BudgetRepository, BudgetCycleLookupPort, CategoryLookupPort, CategorySpendingReader, BudgetExecutionQuery, UseCases
│   │   ├── data/                    # BudgetRepositoryImpl, CategorySpendingReaderImpl, BudgetExecutionQueryImpl
│   │   └── ui/                      # BudgetListViewModel, BudgetDetailScreen, budgetingGraph()
│   ├── reporting-local/
│   │   ├── domain/                  # LocalReportGeneratorPort, ExportLocalReportUseCase, ReportModel
│   │   ├── data/                    # SqlDumpGenerator, SqlLiteral, OpenXmlExcelGenerator, ZipWriter (STORE), FileExporter (SAF / UIDocumentPicker)
│   │   └── ui/                      # LocalReportViewModel, ExportReportBottomSheet
│   └── cloud-sync/
│       ├── domain/                  # CloudBackupGateway, SheetsSyncGateway, PerformCloudBackupUseCase, RestoreCloudBackupUseCase, SyncGoogleSheetsUseCase
│       ├── data/                    # GoogleAuthProvider (expect/actual), BearerAuth, GoogleDriveClient, GoogleSheetsClient, EncryptedBackupService, RestoreService
│       └── ui/                      # CloudSyncViewModel, CloudSyncSettingsScreen
│
├── integration/                     # ACL (D-07): CycleLookupAdapter, SpendingPolicyAdapter, BudgetCycleLookupAdapter, CategoryLookupAdapter. Depende SOLO de los :domain de los contextos
├── shared-app/                      # Raiz Koin DI, grafo de navegacion tipado (compone los sub-grafos de cada feature), MainAppScaffold, bloqueo biometrico
├── app-android/                     # Application, MainActivity, AndroidManifest (allowBackup=false)
└── app-ios/                         # Wrapper Xcode, entrypoint ComposeUIViewController
```

**Variante permitida de build (ADR-009):** `reporting-local` y `cloud-sync` pueden materializarse inicialmente como dos módulos (`domain` + `impl` con paquetes `data`/`ui`) para reducir tiempo de compilación, **manteniendo los mismos límites de paquete** que Konsist verifica. `cycle`, `accounting` y `budgeting` conservan los tres módulos.

---

## 10. Reglas de dependencia arquitectónica (verificables con Konsist en CI)

| # | Regla |
| --- | --- |
| R1 | `:features:*:domain` solo depende de `:core:common`, `:core:model`, `kotlin-stdlib`, `kotlinx-coroutines-core` y `kotlinx-datetime`. Prohibido importar UI, SQLDelight/SQLite, Ktor, okio, `androidx.*`, `platform.*` o payloads de red. |
| R2 | Ningún módulo de `features:X` depende de `features:Y` (ni `domain`, ni `data`, ni `ui`). La comunicación entre contextos ocurre por puertos definidos en el `domain` **consumidor** e implementados en `:integration`. |
| R3 | `:integration` depende solo de los `:domain` de los contextos y de `:core:common`/`:core:model`. |
| R4 | `:features:*:ui` depende de su propio `:domain`, `:core:designsystem`, `:core:common`, `:core:model`. No depende de `:data`. Expone **un único** `StateFlow<UiState>` inmutable por pantalla. |
| R5 | `:features:*:data` depende de su `:domain`, `:core:database`, `:core:common`, `:core:model` y, según caso, `:core:network`/`:core:security`. No depende de `:ui`. |
| R6 | `:core:*` nunca depende de `:features:*`. `:core:model` no depende de coroutines ni de plataforma. |
| R7 | Solo `:shared-app` conoce todos los módulos (cableado Koin y navegación). |
| R8 | Prohibido en `domain`/`data`/`model`: `Double`, `Float`, `Clock.System`, `TimeZone.currentSystemDefault`, `Random`/UUID directo. |
| R9 | Prohibido `DELETE FROM` y `DROP` en consultas `.sq` de negocio (verificación por análisis de archivos). |
| R10 | Toda consulta de lectura operativa filtra `status` explícitamente (lista de excepciones documentada, p. ej. exportación total). |
| R11 | Todo `UseCase` mutante invoca `AuditRecorder` dentro de `TransactionRunner.run` (test de contrato). |
| R12 | `:ui` no importa Agregados ni Value Objects de dominio en `@Composable`; usa `UiModel` por pantalla. |
| R13 | Cada dependencia externa tiene versión fija en `libs.versions.toml` (sin rangos ni `+`). |

---

## 11. Presentación reactiva MVI (CMP)

- Por pantalla: `Contract` con `UiState` (inmutable, `@Immutable`, solo `UiModel` formateados), `Intent` sellado, `SideEffect` sellado entregado por `Channel(BUFFERED).receiveAsFlow()`.
- El `ViewModel` (multiplataforma, `lifecycle-viewmodel`) recibe `Intent`, ejecuta `UseCases`, **mapea** dominio → `UiModel` y reduce el estado; el estado se recolecta con `collectAsStateWithLifecycle`.
- **Navegación:** cada feature expone su sub-grafo (`NavGraphBuilder.cycleGraph(...)`, `accountingGraph(...)`, `budgetingGraph(...)`) con rutas `@Serializable` tipadas; `:shared-app` las compone. Un `ui` nunca conoce rutas de otro feature; las navegaciones cruzadas llegan como lambdas o `SideEffect`.
- **Formulario de transacción (referencia):**

```kotlin
@Immutable
data class TransactionFormUiState(
    val amountText: String = '',
    val selectedCategory: CategoryUiModel? = null,
    val verdict: SpendingBannerUiModel? = null, // derivado de SpendingVerdict en el ViewModel
    val isSaveEnabled: Boolean = false           // SOLO depende de validez del formulario, NUNCA del veredicto
)

sealed interface TransactionFormIntent {
    data class AmountChanged(val text: String) : TransactionFormIntent
    data class CategorySelected(val id: CategoryId) : TransactionFormIntent
    data object SaveClicked : TransactionFormIntent
}

sealed interface TransactionFormSideEffect {
    data object Saved : TransactionFormSideEffect
    data class ShowError(val message: String) : TransactionFormSideEffect
}
```

- El veredicto se obtiene con `combine(amountFlow.debounce(...), categoryFlow, policy.evaluate(check))` y se cancela/reinicia al cambiar categoría, monto o ciclo (`flatMapLatest`).
- `AlertBanner` (design system) es genérico; `accounting:ui` lo alimenta desde `SpendingVerdict`. El color no es el único canal de información (icono + texto), con soporte de accesibilidad (lectores de pantalla, tamaños de fuente).
- `MoneyTextField` convierte texto → `Money` con aritmética de enteros/cadenas respetando el exponente de la moneda.

---

## 12. Stack tecnológico

1. **Lenguaje:** Kotlin 2.x (100% Kotlin, todas las capas y plataformas).
2. **UI:** Compose Multiplatform + navegación tipada + `androidx.lifecycle:lifecycle-viewmodel-compose`.
3. **DI:** Koin Multiplatform (`koin-core`, `koin-compose-viewmodel`).
4. **Persistencia:** **SQLDelight** (D-01) sobre SQLite cifrado con **SQLCipher** (`sqlcipher-android` en Android; SQLCipher XCFramework + driver nativo propio en iOS). Riesgo técnico mitigado con un *spike* en la Fase 0 (ADR-002).
5. **Concurrencia:** Coroutines + `StateFlow`/`SharedFlow` + `kotlinx-datetime`.
6. **Red y serialización:** Ktor Client (`okhttp` en Android, `darwin` en iOS) + `kotlinx.serialization` + `okio` (archivos y *streaming* de ZIP/XML).
7. **Criptografía:** AEAD y Argon2id vía `expect/actual` o biblioteca multiplataforma auditada (ADR-003); nunca algoritmos propios.
8. **Calidad:** Konsist, Turbine, `kotlin-test`, property-based testing (p. ej. Kotest Property), detekt + ktlint, *golden files*.
9. **Build:** Convention plugins en `build-logic/`, `libs.versions.toml` con versiones fijadas, plataformas mínimas definidas por ADR (propuesta inicial: Android minSdk 26, iOS 15).
10. **Hardening de plataforma:** `android:allowBackup="false"` y reglas de extracción; `isExcludedFromBackup` en iOS para archivos de datos; bloqueo biométrico opcional al abrir; `FLAG_SECURE` en pantallas sensibles; sin datos financieros en logs ni en capturas del *app switcher*.

---

## 13. Seguridad, backup cifrado y restauración

### 13.1 Gestión de claves locales

- `DatabaseKeyManager` genera una **clave maestra de BD de 256 bits** con CSPRNG la primera vez. Se protege con el Keystore (Android, clave AES no exportable que envuelve la maestra almacenada en espacio excluido de backups) o el Keychain (iOS, `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`). SQLCipher recibe la clave en formato crudo (`x'hex'`), sin KDF adicional.
- La clave de la BD **no** se deriva de la frase de recuperación: son dos secretos distintos (ver 13.3).
- Bloqueo biométrico opcional al abrir la app; la clave nunca se escribe en logs.

### 13.2 Instantánea consistente (`VacuumBackupExecutor`)

- Nunca se copia el archivo `.db` en uso (modo WAL). Se ejecuta `VACUUM INTO` hacia un archivo temporal en directorio privado y excluido de backups del sistema.
- **Spike obligatorio (ADR-002):** verificar bajo SQLCipher que el archivo resultante conserva el cifrado y la misma clave; si no, alternativa documentada: `ATTACH DATABASE ... KEY` + `sqlcipher_export`.
- Sobre la instantánea se ejecuta `PRAGMA integrity_check` y `PRAGMA foreign_key_check` antes de empaquetar. El temporal se sobrescribe con ceros y se elimina en un bloque `finally`, y se limpia en el siguiente arranque si quedó huérfano.

### 13.3 Formato del backup: envelope versionado (`.fpbk`) (D-09)

**Cabecera (en claro, autenticada como AAD):**

- magic `FPBK`; `formatVersion` (u8); `schemaVersion` (u32); `kdfId` (1 = Argon2id); parámetros KDF (`memoryKiB`, `iterations`, `parallelism`); `salt` (16 bytes); `aeadId` (1 = XChaCha20-Poly1305, 2 = AES-256-GCM); `chunkSize` (por defecto 1 MiB); `createdAt`.
- Bloque `wrappedKeys`: nonce + AEAD de `(dataKey[32] || dbMasterKey[32])` sellado con la **KEK** derivada de la frase de recuperación.

**KDF:** Argon2id obligatorio (D-14), valores por defecto `memoryKiB = 65536`, `iterations = 3`, `parallelism = 1`; mínimos aceptados `memoryKiB >= 19456`, `iterations >= 2`; en restauración se validan **topes máximos** (p. ej. `memoryKiB <= 262144`) para evitar agotamiento de recursos con archivos maliciosos. PBKDF2 solo como *fallback* aprobado por ADR, con ≥ 600000 iteraciones de HMAC-SHA256.

**Cuerpo:** el archivo de instantánea se parte en *chunks* cifrados con AEAD y `dataKey`. El nonce se deriva de un prefijo aleatorio y un contador de chunk; el AAD incluye el hash de la cabecera, el índice del chunk y la marca `isFinal`, de modo que **reordenar, truncar o alterar** cualquier byte hace fallar la autenticación.

**Frase de recuperación:** definida por el usuario con medidor de fortaleza (mínimo recomendado: 12 caracteres o 6 palabras) o generada. La app informa claramente que **no existe recuperación si se pierde la frase**.

**Consecuencia clave:** un dispositivo nuevo puede abrir el backup solo con la frase, porque la clave maestra de la BD viaja envuelta dentro del envelope y luego se registra en el Keystore/Keychain del nuevo dispositivo.

### 13.4 Subida a Google Drive

`GoogleDriveClient` (Ktor): *resumable upload* por tramos múltiplos de 256 KiB, a la carpeta de la app (alcance `drive.file`); nombre `fp-backup-<fechaUTC>-s<schemaVersion>.fpbk`; verificación posterior por tamaño y checksum del metadato de Drive; retención configurable (por defecto últimos 5); reintentos con *backoff*; progreso por `Flow`.

### 13.5 Restauración (`RestoreCloudBackupUseCase`, alcance obligatorio)

1. Listar y elegir backup; descargar a temporal privado.
2. Solicitar frase; derivar KEK; **validar límites de parámetros**; desenvolver claves; descifrar en *streaming* a un archivo temporal.
3. Abrir con `dbMasterKey` en modo lectura; ejecutar `PRAGMA integrity_check`, `PRAGMA foreign_key_check`.
4. Comparar `schemaVersion`: si es **mayor** que la soportada → rechazar con mensaje «actualiza la app»; si es menor → migrar la copia temporal.
5. Ejecutar `CycleContinuityService.verifyContinuity()` y las verificaciones de invariantes sobre la copia.
6. Conservar la BD actual como `*.pre-restore`; cerrar conexiones; **reemplazo atómico** (renombrado); reabrir; registrar la clave vía `DatabaseKeyManager.adopt(...)`.
7. Ante cualquier fallo: rollback a `*.pre-restore` y error tipado. Registrar auditoría `RESTORE`.
8. Test de contrato de ida y vuelta: backup en un «dispositivo A» y restore en un «dispositivo B» simulado con almacén de claves vacío.

### 13.6 Privacidad de lo que sale en claro

Exportaciones `.xlsx`/`.sql` y la hoja de Google Sheets contienen datos sin cifrar. La app: (a) advierte antes de cada exportación/sincronización; (b) ofrece cifrar la exportación con el envelope de 13.3; (c) deja claro que Sheets es una copia en claro en la cuenta de Google del usuario.

### 13.7 Otras reglas de seguridad

- Cero montos, notas o tokens en logs, reportes de fallos o analytics. Sin telemetría de red.
- Tokens OAuth en Keystore/Keychain; revocación al cerrar sesión; `state` y `code_verifier` de un solo uso.
- «Borrado total» = crypto-erase (§4.9).

---

## 14. Estrategia de pruebas y calidad (obligatoria por fase)

- **Dominio:** pruebas unitarias con `FakeClockProvider`, `FakeTimeZoneProvider`, `FakeIdGenerator`. Cobertura de línea ≥ 90% en `domain`; 100% de ramas en `BudgetEvaluationEngine`, `CycleContinuityService` y `RoundingHalfEven`.
- **Property-based:** `Money` (suma/resta, rechazo de moneda distinta, desborde), `RoundingHalfEven` (empates exactos, monotonicidad), `CutoffCalculator` y continuidad de ciclos (para cualquier día de corte 1..31 y cualquier año, no hay huecos ni solapes).
- **Escenarios de reloj:** 29 de febrero, cortes 28/30/31, cambio de zona horaria, cambio de horario de verano, ciclo que cruza fin de año.
- **Motor de presupuestos:** límite 0, empates `x.5`, umbral exacto, edición sin doble conteo (`replacingTransactionId`), presupuesto en padre e hija (peor estado), ingresos 0 con porcentaje, desborde.
- **Triggers:** pruebas en SQLite en memoria (con SQLCipher donde aplique) para **cada** código `ERR_*`; **tests de contrato** dominio-vs-trigger (§4.8).
- **Migraciones:** BD vieja → nueva con datos y verificación de invariantes tras migrar.
- **Exportación:** *golden files* deterministas para `.sql` y `.xlsx` (reloj/IDs falsos); el `.sql` se restaura en una BD limpia y se compara con el original (ida y vuelta); el `.xlsx` se valida descomprimiendo y verificando XML bien formado, CRC y contenido; fuzz de `SqlLiteral` y del escapado XML; verificación de memoria constante con datasets grandes.
- **Backup:** ida y vuelta con envelope, más pruebas de manipulación (bit alterado, truncado, chunks reordenados, parámetros KDF maliciosos).
- **UI/MVI:** pruebas de `ViewModel` con Turbine; verifica que `isSaveEnabled` nunca depende del veredicto.
- **Arquitectura:** Konsist (R1-R13) en CI; el build falla ante violaciones.
- **Rendimiento:** con 1 000 000 de transacciones, roll-up y listados paginados (*keyset*) dentro de presupuestos de latencia definidos en la Fase 6.

---

## 15. Errores, internacionalización y rendimiento

- Los UseCases devuelven `AppResult<T, DomainError>`; no se propagan excepciones entre capas salvo `Unexpected`. La UI mapea `DomainError` a mensajes localizables.
- Idioma por defecto `es-PE`; cadenas en recursos de Compose Multiplatform; formateo de moneda/fecha por *locale*; código e identificadores en inglés.
- Acceso a BD en `Dispatchers.IO`; `Flow` con conflación donde proceda; listas largas con paginación *keyset*.
- Pantalla de **diagnóstico local** (sin red): `verifyContinuity()`, `integrity_check`, versión de esquema y tamaño de BD.

---

## 16. Plan de entrega por fases (con criterios de aceptación)

Cada fase termina solo si todos sus criterios se cumplen y el CI (build, tests, Konsist) está en verde.

**Fase 0: Cimientos de proyecto y spikes.** `build-logic`, `libs.versions.toml`, CI, esqueleto de reglas Konsist, ADR-001 a ADR-011 en borrador y `docs/context-map.md`. Spikes con decisión *go/no-go* documentada: (1) SQLCipher + SQLDelight en iOS, (2) `VACUUM INTO` bajo SQLCipher, (3) Argon2id y AEAD en ambas plataformas, (4) escritor ZIP STORE en streaming. *Aceptación:* app vacía compila y arranca en Android e iOS; los 4 spikes tienen veredicto escrito.

**Fase 1: Núcleo.** `:core:common`, `:core:model` (`Money`, `BasisPoints`, `CurrencyCode`, `RoundingHalfEven`, estados, IDs) y `:core:database` (`.sq` completo §8, triggers, `SqlErrorMapper`, `TransactionRunnerImpl`, `AuditRecorderImpl`, `VacuumBackupExecutor`, migraciones). *Aceptación:* tests de todos los `ERR_*`; property tests de `Money`/redondeo; test de migración; test de `TransactionRunner` (rollback y anidamiento).

**Fase 2: Core financiero.** `:features:cycle` (`CutoffCalculator`, `CycleContinuityService`, UseCases) y `:features:accounting` (Agregados, invariantes de tipo, jerarquía de categorías, borrado lógico en cascada, ajustes, `CycleLookupPort`) con sus `data` y adaptador en `:integration`. *Aceptación:* tests de continuidad con reloj falso (bisiesto, 28/30/31, zona horaria); contrato dominio-vs-trigger; auditoría en todos los UseCases mutantes.

**Fase 3: Motor presupuestal y UI reactiva.** `:features:budgeting` (`BudgetEvaluationEngine`, `LimitCalculator`, `CategorySpendingReader`, `BudgetExecutionQuery`), `SpendingPolicyAdapter` en `:integration`, cableado en `:shared-app` y formulario MVI en CMP con alerta no bloqueante. *Aceptación:* tabla de casos del engine (§14) al 100% de ramas; test de edición sin doble conteo; `isSaveEnabled` independiente del veredicto; navegación por sub-grafos.

**Fase 4: Exportación local.** `:features:reporting-local` (`.sql` y `.xlsx` en `commonMain` con `okio`, `ZipWriter` STORE, `FileExporter` SAF/UIDocumentPicker) y UI de exportación con advertencia de privacidad. *Aceptación:* *golden files*, ida y vuelta del `.sql`, validación del `.xlsx`, memoria constante.

**Fase 5: Seguridad y nube.** `:core:security` (Vault, `DatabaseKeyManager`, KDF, AEAD, `BackupEnvelope`), `:features:cloud-sync` (OAuth PKCE, backup a Drive, **restauración**, Sheets unidireccional con outbox). *Aceptación:* ida y vuelta A→B, pruebas de manipulación del envelope, sincronización idempotente con cuotas y reintentos simulados.

**Fase 6: Endurecimiento y entrega.** Konsist completo en CI, presupuestos de rendimiento con 1M de filas, accesibilidad, i18n, diagnóstico local, revisión de privacidad para tiendas, documentación final. *Aceptación:* checklist de §19 completo.

---

## 17. ADRs requeridos (`docs/adr/`, plantilla: Contexto, Decisión, Alternativas, Consecuencias, Estado, Fecha)

| ADR | Tema | Estado inicial |
| --- | --- | --- |
| 001 | SQLDelight vs Room KMP | Decidido: SQLDelight (D-01) |
| 002 | SQLCipher en iOS y `VACUUM INTO` bajo cifrado | Requiere spike (F0) |
| 003 | Formato y claves del backup (Argon2id, AEAD, librerías) | Propuesto en §13.3 |
| 004 | Estrategia de exportación a Sheets (outbox, `RAW`, cuotas) | Propuesto en §6.E.3 |
| 005 | Representación de fechas y zonas horarias | Decidido (D-04) |
| 006 | Monomoneda y exponente por moneda | Decidido (D-02) |
| 007 | IDs UUIDv7 | Decidido (D-03) |
| 008 | Separación `state` vs `status` | Decidido (D-05) |
| 009 | Granularidad de módulos y variante de build | Propuesto en §9 |
| 010 | ZIP STORE vs Deflate | Decidido (D-15), revisable por spike |
| 011 | Alcance OAuth mínimo para Drive y Sheets | Requiere análisis |

---

## 18. No objetivos y ganchos de evolución

**No objetivos de esta versión (declarados explícitamente):** contabilidad de partida doble, cuentas con saldos, transferencias entre medios de pago, multimoneda con tipos de cambio, sincronización multi-dispositivo/bidireccional, transacciones recurrentes, adjuntos, backend propio. Toda mención a «saldos» en la v1 se interpreta como **totales por ciclo**, no como saldo de cuenta.

**Ganchos ya previstos:** UUIDv7 y `version` por registro (base para sync/conflictos), `device_id` en configuración y auditoría, `sync_outbox`, exponente por moneda en `CurrencyCode`, estrategia `LimitCalculator` para nuevas modalidades de presupuesto, y un futuro modelo `Account`/`LedgerEntry` sin romper `Transaction`.

---

## 19. Formato de salida esperado y Definition of Done global

**Por cada fase entrega:** (1) árbol de archivos creados/modificados; (2) código completo y compilable de cada archivo; (3) tests; (4) comandos exactos de verificación; (5) checklist de aceptación marcada con evidencia. Si el entregable es demasiado grande para una respuesta, di exactamente qué módulo entregas ahora y cuál sigue; **nunca** uses `...`, `TODO` ni comentarios del tipo «resto igual».

**Definition of Done global:**

- Compila para Android e iOS; tests y Konsist en verde.
- Cero `Double`/`Float` en dinero y porcentajes; cero `Clock.System` en dominio.
- Cada regla de §4 y §6 tiene al menos un test que falla si la regla se rompe.
- Toda mutación es transaccional, versionada y auditada.
- Los triggers y el dominio rechazan los mismos escenarios (tests de contrato).
- Backup y restauración probados de punta a punta; exportaciones con advertencia de privacidad.
- Documentación (`domain.md`, `context-map.md`, ADRs) actualizada y coherente con el código.

**Si detectas una contradicción entre secciones, detente y repórtala antes de codificar.**
