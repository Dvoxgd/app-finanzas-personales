# Reglas de Dominio y Estándares de Ingeniería Bancaria

Este documento condensa las reglas de negocio e invariantes contables que rigen todo el sistema. Son de obligado cumplimiento y deben validarse tanto a nivel de Agregados/UseCases de Kotlin como mediante Triggers de SQLite (defensa en profundidad).

---

## 1. Prohibición Absoluta de Punto Flotante

1. **Montos (`Money`):**
   - Se representan y almacenan como un entero de 64 bits (`Long`) en **Unidades Menores** (por ejemplo, `S/. 850.50` se modela como `85050L` con exponente 2).
   - El exponente decimal **no es fijo** (no asume 2 decimales): proviene estrictamente de `CurrencyCode.minorUnitExponent` (PEN=2, USD=2, JPY=0, KWD=3).
   - **Rango Seguro:** `Money.MAX_MINOR = 100_000_000_000_000L` ($10^{14}$). Esto garantiza que cualquier multiplicación `monto × 10000` (puntos base) no desborde el límite de un `Long` con signo ($9.22 \times 10^{18}$).
   - **Aritmética Verificada:** Toda operación de suma, resta y multiplicación debe usar funciones verificadas (`Math.addExact`, `Math.multiplyExact`). Un desborde retorna `AppResult.Failure(DomainError.MoneyOverflow)`, jamás un valor desbordado o truncado.
2. **Porcentajes (`BasisPoints`):**
   - Se almacenan como enteros (`Int`) en **Puntos Base** (`100.00% = 10000 bps`, `85.50% = 8550 bps`, `0.01% = 1 bps`).
   - Los presupuestos porcentuales admiten un rango de configuración de `1` a `10000` bps. El umbral de alerta admite de `100` a `10000` bps. El consumo calculado puede superar los 10000 bps en caso de sobregiro.
3. **Parseo y Formateo:**
   - La conversión de texto de entrada a `Money` se realiza con manipulación de cadenas y aritmética de enteros; queda terminantemente prohibido hacer `text.toDouble()`.

---

## 2. Redondeo Bancario (Half-Even)

Para cualquier división financiera de enteros $n \ge 0$, $d > 0$, el redondeo bancario se calcula mediante la función pura `RoundingHalfEven.divide(n, d)`:

```kotlin
fun divide(n: Long, d: Long): Long {
    require(d > 0) { "Divisor must be strictly positive" }
    require(n >= 0) { "Dividend must be non-negative" }
    val q = n / d
    val r = n % d
    val diff = d - r
    return when {
        r < diff -> q
        r > diff -> q + 1L
        else -> if (q % 2L == 0L) q else q + 1L // Empate exacto (r == d - r): redondear al par más cercano
    }
}
```

*Nota técnica:* Se evalúa `r` contra `d - r` para evitar el riesgo de desbordamiento que provocaría calcular `2 * r`.

---

## 3. Ciclos Financieros Personalizados (`features:cycle`)

### 3.1 Cálculo del Día de Corte (`CutoffCalculator`)
- El usuario fija un `billing_cutoff_day` entre 1 y 31 (por ejemplo, 25).
- El inicio del ciclo para un mes $M$ del año $Y$ se calcula como:
  $$\text{startDate} = \text{LocalDate}(Y, M, \min(\text{cutoffDay}, \text{lengthOfMonth}(Y, M)))$$
  *Ejemplo con corte 31 en febrero no bisiesto:* se ajusta (*clamp*) a `2027-02-28`. En año bisiesto, a `2028-02-29`.
- La fecha de fin es el día anterior al inicio del siguiente ciclo: `endDate = nextCycle.startDate - 1 day`.

### 3.2 Invariante de Continuidad sin Huecos ni Solapamientos
Para todos los ciclos ordenados por `startDate` donde `status != 'DELETED'`:
$$\forall i: \quad \text{Cycle}[i].\text{endDate} + 1\text{ día} = \text{Cycle}[i+1].\text{startDate}$$

- **Regla Estricta:** Ninguna entidad `Cycle` puede modificar sus fechas de forma aislada. Todo cambio pasa exclusivamente por el Domain Service `CycleContinuityService`.
- **Modificación de Fechas:**
  - Acortar o extender el fin de un ciclo reajusta automáticamente el inicio del ciclo siguiente de forma atómica dentro de `TransactionRunner`.
  - Si el ciclo siguiente está en estado `CLOSED`, la operación se rechaza con `CycleError.AdjacentCycleClosed`.
  - La eliminación lógica (`DELETED`) solo está permitida para el **último ciclo** en la línea de tiempo, en estado `OPEN` y sin transacciones ni presupuestos activos. Eliminar ciclos intermedios está prohibido para evitar huecos.

### 3.3 Cierre de Ciclos (`CloseCycleUseCase`)
- Un ciclo solo puede cerrarse (`OPEN → CLOSED`) si la fecha actual es posterior a su `endDate` o si el usuario confirma forzar el cierre (`force = true`).
- **El cierre es irreversible:** Un ciclo `CLOSED` jamás vuelve a `OPEN`.

---

## 4. Inmutabilidad de Periodos Cerrados y Ajustes

1. **Bloqueo Contable Total:**
   - En un ciclo con `state = 'CLOSED'` está prohibido: insertar, editar o eliminar lógicamente transacciones; insertar, editar o eliminar presupuestos; modificar fechas o nombres; eliminar lógicamente el ciclo.
   - Esta regla está protegida en los Agregados de dominio y reforzada por los triggers `trg_cycles_closed_immutable`, `trg_tx_update_guard` y `trg_budgets_update_guard`.
2. **Transacciones de Ajuste (D-13):**
   - Cualquier corrección contable que afecte a un ciclo cerrado debe realizarse registrando una **Transacción de Ajuste** en un ciclo que se encuentre actualmente `OPEN`.
   - La transacción de ajuste contiene el campo `adjusts_transaction_id` apuntando a la transacción original (que puede residir en un ciclo cerrado).
   - **Regla para Presupuestos:** Los ingresos que sean de ajuste (`adjusts_transaction_id IS NOT NULL`) **se excluyen de la base de ingresos del ciclo** al calcular los límites de presupuestos porcentuales.

---

## 5. Categorías y Subcategorías (`features:accounting`)

- **Profundidad Máxima:** Exactamente 2 niveles (Categoría Padre $\to$ Subcategoría Hija). Una categoría que ya tiene `parent_id` no puede tener hijas. Una categoría con hijas no puede convertirse en hija.
- **Invariante de Tipo (LSP):** `subcategoria.type == categoriaPadre.type`. El tipo de una categoría (`INCOME` o `EXPENSE`) es inmutable una vez que tiene transacciones asociadas o hijas creadas.
- **Borrado Lógico en Cascada Transaccional:**
  - Marcar un padre como `DELETED` marca automáticamente como `DELETED` a todas sus hijas y a los presupuestos asociados en ciclos `OPEN`.
  - Las transacciones históricas asociadas permanecen intactas (conservando la integridad contable).
  - Los presupuestos en ciclos `CLOSED` no se alteran.

---

## 6. Motor de Evaluación Presupuestal (`features:budgeting`)

El motor reside en `BudgetEvaluationEngine` y se ejecuta como una **función pura**:

### 6.1 Modalidades de Presupuesto (`BudgetTargetType`)
1. **`FIXED_AMOUNT`:** `target_value_raw` contiene el límite en unidades menores (`Long`).
2. **`PERCENTAGE`:** `target_value_raw` contiene los puntos base (1..10000). El límite se calcula como:
   $$\text{limite} = \text{RoundingHalfEven.divide}(\text{ingresosActivosDelCiclo} \times \text{targetValueBps}, 10000)$$
   donde `ingresosActivosDelCiclo` es la suma de transacciones `INCOME` activas del ciclo con `adjusts_transaction_id IS NULL`.

### 6.2 Roll-up Jerárquico de Gastos
- Para una categoría padre, el gasto ejecutado es la suma de sus transacciones directas `EXPENSE` activas **más** las de todas sus subcategorías hijas.
- Para una subcategoría, solo se suman sus transacciones directas.

### 6.3 Evaluación de Estados y Alertas
- La proyección de gasto se evalúa mediante aritmética de enteros: $\text{proyectado} = \text{gastoAcumulado} + \text{montoNuevo}$.
- Si $\text{limite} = 0$: si $\text{proyectado} > 0 \to \text{BudgetExceeded}$; si $\text{proyectado} = 0 \to \text{WithinSafeLimits}$.
- **Comparación exacta en enteros (sin división por punto flotante):**
  - **`BudgetExceeded`:** $\text{proyectado} > \text{limite}$.
  - **`ThresholdWarning`:** $\text{proyectado} \times 10000 \ge \text{alertThresholdBps} \times \text{limite}$ y $\text{proyectado} \le \text{limite}$.
  - **`WithinSafeLimits`:** resto de casos.
- El porcentaje de consumo (`consumptionBps`) solo se calcula para propósitos de visualización (mediante Half-Even); **jamás decide el estado de la alerta**.

### 6.4 Precedencia con Presupuestos Concurrentes (Padre e Hija)
Si una transacción pertenece a una subcategoría que tiene presupuesto propio, y su categoría padre también tiene presupuesto, se evalúan ambos y el veredicto final adopta el **peor estado**:
$$\text{BudgetExceeded} > \text{ThresholdWarning} > \text{WithinSafeLimits} > \text{NoBudgetConfigured}$$

### 6.5 Prevención del Doble Conteo en Edición
Cuando se edita una transacción existente, la verificación excluye su propio monto previo enviando `replacingTransactionId`:
$$\text{gastoAcumulado} = \text{SUM}(amount\_minor) \quad \text{donde } t.id \ne :excludeTransactionId$$

### 6.6 Comportamiento no Bloqueante de la UI
El veredicto de presupuesto (`SpendingVerdict`) es meramente **informativo**. La UI muestra advertencias visuales y el monto de sobregiro, pero la habilitación del guardado (`isSaveEnabled`) depende **exclusivamente** de que los campos del formulario sean sintácticamente válidos.
