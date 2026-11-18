# Manejo de Errores Tipados y Privacidad de Logs

Este documento describe la arquitectura de errores sellados, la captura de fallos de triggers SQLite (defensa en profundidad) y la política de privacidad estricta para logs.

---

## 1. Jerarquía Sellada de Errores (`DomainError`)

En `:core:common`, todos los fallos del sistema se modelan como una jerarquía sellada exhaustiva:

```kotlin
package com.presupuesto.core.common

sealed interface DomainError {
    // Concurrencia y Versión
    data object ConcurrentModification : DomainError

    // Ciclos e Inmutabilidad
    data object CycleClosed : DomainError
    data object CycleNotOpen : DomainError
    data object AdjacentCycleClosed : DomainError
    data class ContinuityViolation(val details: String) : DomainError

    // Categorías y Estructura
    data object CategoryDepthExceeded : DomainError
    data object CategoryTypeMismatch : DomainError
    data object CategoryTypeLocked : DomainError
    data object CategoryNotActive : DomainError

    // Medios de Pago y Transacciones
    data object PaymentMethodNotActive : DomainError
    data object CurrencyMismatch : DomainError
    data object CurrencyLocked : DomainError

    // Reglas Contables y Físicas
    data object InvalidTransition : DomainError
    data object PhysicalDeleteForbidden : DomainError
    data object MoneyOverflow : DomainError

    // Validación Sintáctica
    data class Validation(val field: String, val reason: String) : DomainError

    // Errores Inesperados / Infraestructura
    data class Unexpected(val cause: Throwable) : DomainError
}
```

---

## 2. Mapeo de Triggers SQLite a Errores de Dominio (`SqlErrorMapper`)

Los triggers de SQLite abortan con un mensaje que contiene un prefijo estable `ERR_*`. La clase `SqlErrorMapper` en `:core:database` intercepta las excepciones de SQLite, extrae el código y lo convierte en el `DomainError` correspondiente:

```kotlin
package com.presupuesto.core.database

import com.presupuesto.core.common.DomainError

class SqlErrorMapper {

    private val errorCodeRegex = Regex("ERR_[A-Z_]+")

    fun map(throwable: Throwable): DomainError {
        val message = throwable.message.orEmpty()
        val match = errorCodeRegex.find(message)?.value

        return when (match) {
            "ERR_VERSION_CONFLICT" -> DomainError.ConcurrentModification
            "ERR_CYCLE_CLOSED" -> DomainError.CycleClosed
            "ERR_CYCLE_NOT_OPEN" -> DomainError.CycleNotOpen
            "ERR_CYCLE_OVERLAP" -> DomainError.ContinuityViolation("Cycle dates overlap")
            "ERR_CATEGORY_DEPTH" -> DomainError.CategoryDepthExceeded
            "ERR_CATEGORY_TYPE_MISMATCH" -> DomainError.CategoryTypeMismatch
            "ERR_CATEGORY_TYPE_LOCKED" -> DomainError.CategoryTypeLocked
            "ERR_CATEGORY_NOT_ACTIVE" -> DomainError.CategoryNotActive
            "ERR_PAYMENT_METHOD_NOT_ACTIVE" -> DomainError.PaymentMethodNotActive
            "ERR_CURRENCY_MISMATCH" -> DomainError.CurrencyMismatch
            "ERR_CURRENCY_LOCKED" -> DomainError.CurrencyLocked
            "ERR_PHYSICAL_DELETE_FORBIDDEN" -> DomainError.PhysicalDeleteForbidden
            "ERR_INVALID_TRANSITION" -> DomainError.InvalidTransition
            else -> DomainError.Unexpected(throwable)
        }
    }

    inline fun <T> safeExecute(block: () -> T): T {
        try {
            return block()
        } catch (t: Throwable) {
            throw map(t).asException()
        }
    }
}
```

---

## 3. Manejo de Errores en la Capa de Presentación

- Los ViewModels reciben `AppResult<T, DomainError>`.
- **Nunca se muestra el nombre de la clase de error en crudo al usuario.**
- La UI mapea `DomainError` a un mensaje localizado en `es-PE` a través de un resolvedor de recursos de Compose Multiplatform:
  - `DomainError.CycleClosed` $\to$ `"Este ciclo financiero ya se encuentra cerrado y no admite modificaciones."`
  - `DomainError.CategoryDepthExceeded` $\to$ `"Solo se permite un nivel de subcategorías."`
  - `DomainError.ConcurrentModification` $\to$ `"El registro fue actualizado en otro proceso. Por favor recarga la información."`

---

## 4. Política de Privacidad de Logs (Zero Data Leak)

Dado el estándar de ingeniería bancaria y la naturaleza de privacidad por defecto del proyecto:

1. **Cero Datos Financieros en Logs:**
   - Queda estrictamente prohibido imprimir en consola o logs del sistema:
     - Montos (`Money.minor`, valores formateados).
     - Notas o descripciones de transacciones (`notes`).
     - Nombres de presupuestos o categorías personalizadas.
     - Tokens de autenticación OAuth, claves maestras o frases de recuperación.
2. **Logs Autorizados:**
   - Solo se permite registrar eventos de ciclo de vida del sistema sin datos sensibles:
     - `INFO: Database connection initialized successfully (WAL mode enabled).`
     - `INFO: Continuity check passed for 12 cycles.`
     - `WARN: SQL operation aborted due to trigger constraint: ERR_CYCLE_CLOSED.`
3. **Protección en la Plataforma:**
   - Android: `FLAG_SECURE` en la ventana para impedir capturas de pantalla en vistas financieras sensibles y ocultar contenido en el visor de tareas recientes (*app switcher*).
   - iOS: Pantalla de bloqueo al pasar a segundo plano para prevenir capturas en el selector de aplicaciones.
