# Mapeo de Modelos y Separación por Capas

Este documento define la jerarquía de modelos del sistema y las reglas de transformación para preservar el desacoplamiento entre la persistencia, la lógica de negocio y la interfaz de usuario.

---

## 1. Niveles de Modelos

| Nivel | Ubicación | Propósito | Características |
|---|---|---|---|
| **Database Row** | Generado por SQLDelight en `:core:database` | Representación 1:1 de tablas y proyecciones SQL | Tipos primitivos (`Long`, `String`, `Int`), sin lógica de negocio |
| **Domain Model** | `:features:*:domain` y `:core:model` | Agregados ricos, Entidades y Value Objects | Inmutables, con invariantes validadas (`Money`, `BasisPoints`, IDs tipados) |
| **UiModel** | `:features:*:ui` | Contenedores para pantallas Compose | Inmutables (`@Immutable`), solo cadenas formateadas, íconos y banderas booleanas |
| **Published DTO** | `:features:*:domain` (Published Language) | Intercambio de lectura inter-contextos (CQRS) | Objetos planos sin comportamiento, accesibles para reportes y sincronización |

---

## 2. Reglas Estrictas de Mapeo

1. **Funciones de Extensión Puras:**
   - Los mappers se implementan como extension functions de Kotlin (ej. `CycleRow.toDomain(): Cycle`, `Transaction.toUiModel(currency): TransactionUiModel`).
   - Deben ser funciones deterministas sin efectos secundarios ni llamadas a I/O.
2. **Prohibición de Fugas Hacia Compose:**
   - Queda terminantemente prohibido pasar entidades de base de datos o agregados de dominio a funciones `@Composable`.
   - La pantalla solo interactúa con `UiModel` preparados por el ViewModel.
3. **Conversión Segura de Dinero (`Money` a Texto):**
   - El formateo de `Money` a cadena de texto para visualización o para exportación a hojas de cálculo se realiza **sin punto flotante**:
     ```kotlin
     fun Money.toFormattedDecimal(): String {
         val exp = currency.minorUnitExponent
         if (exp == 0) return minor.toString()
         val divisor = when (exp) {
             2 -> 100L
             3 -> 1000L
             else -> error("Unsupported exponent: $exp")
         }
         val integerPart = minor / divisor
         val fractionPart = (minor % divisor).toString().padStart(exp, '0')
         return "$integerPart.$fractionPart"
     }
     ```

---

## 3. Ejemplos Canónicos de Mapeadores

### 3.1 Mapeador Base de Datos $\to$ Dominio (`features:cycle:data`)

```kotlin
package com.presupuesto.features.cycle.data.mapper

import com.presupuesto.core.model.*
import com.presupuesto.database.Cycles
import com.presupuesto.features.cycle.domain.model.Cycle
import kotlinx.datetime.LocalDate

fun Cycles.toDomain(): Cycle {
    return Cycle(
        id = CycleId(id),
        name = name,
        dateRange = DateRange(
            start = LocalDate.parse(start_date),
            end = LocalDate.parse(end_date)
        ),
        isManualOverride = is_manual_override == 1L,
        state = CycleState.valueOf(state),
        status = EntityStatus.valueOf(status),
        version = version.toInt(),
        createdAt = kotlinx.datetime.Instant.parse(created_at),
        updatedAt = kotlinx.datetime.Instant.parse(updated_at)
    )
}
```

### 3.2 Mapeador Dominio $\to$ Modelo de UI (`features:accounting:ui`)

```kotlin
package com.presupuesto.features.accounting.ui.mapper

import com.presupuesto.features.accounting.domain.model.Transaction
import com.presupuesto.features.accounting.ui.model.TransactionItemUiModel

fun Transaction.toUiModel(): TransactionItemUiModel {
    return TransactionItemUiModel(
        id = id.value,
        amountFormatted = amount.toFormattedCurrencyString(), // "S/. 150.00"
        categoryName = categoryName,
        categoryColorHex = categoryColorHex,
        dateFormatted = localDate.toString(), // "2026-11-18"
        notes = notes.orEmpty(),
        isExpense = type == TransactionType.EXPENSE,
        isAdjustment = adjustsTransactionId != null
    )
}
```

### 3.3 Mapeador para Publicación / CQRS (`Published Language`)

```kotlin
package com.presupuesto.features.budgeting.domain.model

import com.presupuesto.core.model.BasisPoints
import com.presupuesto.core.model.Money

// DTO publicado para que :features:reporting-local dibuje la pestaña "Presupuestos vs. Ejecución"
data class BudgetExecutionSummaryDto(
    val categoryName: String,
    val targetType: String,
    val limitAmount: Money,
    val executedAmount: Money,
    val differenceAmount: Money,
    val consumption: BasisPoints,
    val isExceeded: Boolean
)
```
