# Estrategia de Pruebas y Calidad

Este documento define la arquitectura de pruebas, los tipos de test requeridos y las herramientas de verificación para garantizar la exactitud bancaria del sistema.

---

## 1. Herramientas y Frameworks

- **Pruebas Unitarias y Aserciones:** `kotlin-test`.
- **Flujos Reactivos Coroutines / StateFlow:** **Turbine**.
- **Pruebas Basadas en Propiedades (Property-Based):** **Kotest Property**.
- **Pruebas de Arquitectura y Dependencias:** **Konsist**.
- **Determinismo:** Fakes puros en memoria (`FakeClockProvider`, `FakeTimeZoneProvider`, `FakeIdGenerator`).

---

## 2. Tipos de Pruebas Obligatorias por Capa

### 2.1 Pruebas de Dominio y Servicios Puros
- **Cobertura Exigida:** $\ge 90\%$ en paquetes `domain`; **100% de cobertura de ramas** en:
  - `BudgetEvaluationEngine`
  - `CycleContinuityService`
  - `RoundingHalfEven`
  - `CutoffCalculator`
- **Inyección de Tiempo Determinista:**
  ```kotlin
  class FakeClockProvider(private var current: Instant) : ClockProvider {
      override fun now(): Instant = current
      fun advanceBy(duration: Duration) { current += duration }
  }
  ```
- **Escenarios de Calendario Obligatorios:** Pruebas explícitas para: 29 de febrero (años bisiestos), cortes en días 28, 29, 30 y 31, ciclos que cruzan fin de año y transiciones de horario de verano (DST).

### 2.2 Pruebas Basadas en Propiedades (Property-Based Testing)
Se implementan con `checkAll` de Kotest Property:
- **`Money`:**
  - Clausura y conmutatividad en sumas de la misma moneda.
  - Rechazo invariable ante sumas de monedas distintas (`CurrencyMismatch`).
  - Detección determinista de desbordamiento en operaciones mayores a $10^{14}$ (`MoneyOverflow`).
- **`RoundingHalfEven`:**
  - Verificación exhaustiva de empates exactos ($x.5$ debe redondear siempre al entero par más cercano).
  - Monotonicidad: si $a \le b$, entonces $\text{divide}(a, d) \le \text{divide}(b, d)$.
- **`CutoffCalculator` y Continuidad de Ciclos:**
  - Para cualquier día de corte entre 1 y 31, y cualquier año entre 2000 y 2100, la secuencia de ciclos generada jamás presenta días huérfanos ni solapamientos.

### 2.3 Tests de Contrato: Dominio vs. Triggers SQLite
Para garantizar la defensa en profundidad, **cada regla crítica debe tener un test de contrato simétrico**:

```kotlin
@Test
fun `contract - adding transaction to closed cycle must be rejected by both domain and trigger`() = runTest {
    val closedCycle = createClosedCycle()

    // 1. Verificación del Dominio (UseCase)
    val domainResult = createTransactionUseCase(cycleId = closedCycle.id, /* ... */)
    assertTrue(domainResult is AppResult.Failure)
    assertEquals(DomainError.CycleClosed, domainResult.error)

    // 2. Verificación de Triggers en SQLite
    val dbException = assertFailsWith<Exception> {
        rawDatabaseQueries.insertTransactionDirectly(cycle_id = closedCycle.id.value, /* ... */)
    }
    assertTrue(dbException.message!!.contains("ERR_CYCLE_CLOSED"))
}
```

### 2.4 Pruebas de Migración de Base de Datos
- Por cada archivo `N.sqm`, debe existir una prueba que:
  1. Cree la base de datos en la versión $N-1$.
  2. Inserte registros de prueba reales.
  3. Ejecute la migración a la versión $N$.
  4. Valide que los datos sobrevivieron íntegros y que los nuevos triggers e índices están operativos.

### 2.5 Pruebas de Presentación MVI con Turbine
- Verifican que los Intents transforman el estado `UiState` de forma esperada.
- **Test Invariante del Formulario:**
  ```kotlin
  @Test
  fun `isSaveEnabled must remain true even when spending verdict is BudgetExceeded`() = runTest {
      viewModel.uiState.test {
          assertEquals(false, awaitItem().isSaveEnabled) // formulario vacío

          viewModel.onIntent(TransactionFormIntent.AmountChanged("999999.00"))
          viewModel.onIntent(TransactionFormIntent.CategorySelected(CategoryId("cat-1")))

          val state = awaitItem()
          assertTrue(state.verdict is SpendingBannerUiModel.BudgetExceeded)
          assertTrue(state.isSaveEnabled) // REGLA: el guardado no se bloquea
      }
  }
  ```

### 2.6 Pruebas de Arquitectura con Konsist
Verifican en el pipeline de CI las reglas R1 a R13 descritas en `architecture.md`:
- Que ningún módulo `:domain` contenga importaciones de Android, Compose, SQLite o Ktor.
- Que ningún módulo `:features:X` dependa directamente de `:features:Y`.
- Que no existan tipos `Double` ni `Float` en los paquetes de dominio o datos.
