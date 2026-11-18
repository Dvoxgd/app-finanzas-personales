# Casos de Uso y Servicios de Dominio

Este documento establece las pautas de diseño para la capa de Dominio y Aplicación en cada Bounded Context.

---

## 1. Casos de Uso (Application UseCases)

Los casos de uso representan operaciones de negocio atómicas. Orquestan entidades de dominio, servicios de dominio, puertos de repositorio, transacciones y auditoría.

### 1.1 Estructura Canónica de un UseCase Mutante

```kotlin
package com.presupuesto.features.accounting.domain.usecase

import com.presupuesto.core.common.*
import com.presupuesto.core.model.*
import com.presupuesto.features.accounting.domain.model.Transaction
import com.presupuesto.features.accounting.domain.port.CycleLookupPort
import com.presupuesto.features.accounting.domain.repository.CategoryRepository
import com.presupuesto.features.accounting.domain.repository.TransactionRepository
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

class CreateTransactionUseCase(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val cycleLookupPort: CycleLookupPort,
    private val clockProvider: ClockProvider,
    private val timeZoneProvider: TimeZoneProvider,
    private val idGenerator: IdGenerator,
    private val transactionRunner: TransactionRunner,
    private val auditRecorder: AuditRecorder
) {

    suspend operator fun invoke(
        amount: Money,
        categoryId: CategoryId,
        paymentMethodId: PaymentMethodId,
        type: TransactionType,
        notes: String?,
        explicitCycleId: CycleId? = null
    ): AppResult<TransactionId, DomainError> = transactionRunner.run {
        // 1. Obtención de instantes no deterministas inyectados
        val now: Instant = clockProvider.now()
        val timeZone = timeZoneProvider.current()
        val localDate: LocalDate = now.toLocalDateTime(timeZone).date

        // 2. Validación de Category e invariantes
        val category = categoryRepository.findById(categoryId)
            ?: return@run AppResult.Failure(DomainError.Validation("categoryId", "Category does not exist"))

        if (category.status != EntityStatus.ACTIVE) {
            return@run AppResult.Failure(DomainError.CategoryNotActive)
        }
        if (category.type != type) {
            return@run AppResult.Failure(DomainError.CategoryTypeMismatch)
        }

        // 3. Resolución de ciclo (explícito o inferido) y validación de estado OPEN
        val cycleId = explicitCycleId ?: cycleLookupPort.findCycleIdByDate(localDate)
            ?: return@run AppResult.Failure(DomainError.Validation("cycleId", "No cycle found for date $localDate"))

        val isCycleOpen = cycleLookupPort.isCycleOpen(cycleId)
        if (!isCycleOpen) {
            return@run AppResult.Failure(DomainError.CycleNotOpen)
        }

        // 4. Construcción del Agregado
        val transactionId = TransactionId(idGenerator.newId())
        val transaction = Transaction.create(
            id = transactionId,
            cycleId = cycleId,
            categoryId = categoryId,
            paymentMethodId = paymentMethodId,
            type = type,
            amount = amount,
            occurredAt = now,
            timeZoneId = timeZone.id,
            localDate = localDate,
            notes = notes
        )

        // 5. Persistencia
        transactionRepository.insert(transaction)

        // 6. Registro de Auditoría OBLIGATORIO dentro de la misma transacción
        auditRecorder.record(
            AuditEntry(
                id = idGenerator.newId(),
                entityType = "TRANSACTION",
                entityId = transactionId.value,
                action = AuditAction.CREATE,
                oldValueJson = null,
                newValueJson = transaction.toJson(),
                occurredAt = now
            )
        )

        AppResult.Success(transactionId)
    }
}
```

---

## 2. Reglas Estrictas para UseCases

1. **Retorno Obligatorio con `AppResult<T, DomainError>`:**
   - Queda terminantemente prohibido lanzar excepciones de negocio para control de flujo. Toda condición esperada o error de validación debe devolverse como `AppResult.Failure`.
2. **Atomicidad con `TransactionRunner`:**
   - Todo UseCase que modifique datos debe envolver su ejecución en `transactionRunner.run { ... }`.
   - Si ocurre un retorno temprano de error o se aborta por un trigger de SQLite, el runner efectúa rollback automático de cualquier cambio previo.
3. **Inyección de Abstracciones No Deterministas:**
   - Prohibido invocar directamente: `Clock.System.now()`, `TimeZone.currentSystemDefault()` o `UUID.randomUUID()`.
   - Se inyectan forzosamente `ClockProvider`, `TimeZoneProvider` e `IdGenerator`. Esto permite pruebas 100% deterministas de años bisiestos, transiciones horarias y generación de UUIDv7 predecibles.

---

## 3. Servicios de Dominio (Domain Services)

Los servicios de dominio contienen lógica o cálculos de negocio que involucran a múltiples entidades o que no pertenecen conceptualmente a un solo agregado.

### 3.1 `CycleContinuityService` (Continuidad del Calendario Financiero)
- **Propósito:** Ningún ciclo individual puede garantizar la continuidad temporal por sí mismo. Toda modificación de fechas (`startDate`, `endDate`), adición de override o eliminación pasa forzosamente por este servicio.
- **Responsabilidad:** Verifica que la fecha de fin de un ciclo enlace exactamente con el inicio del ciclo siguiente (`Cycle[i].endDate + 1 == Cycle[i+1].startDate`).
- **Verificación Diagnóstica:** Expone `verifyContinuity(): List<ContinuityViolation>`, ejecutable en el arranque de la app, en diagnósticos y en suites de test.

### 3.2 `BudgetEvaluationEngine` (Función Pura de Evaluación Presupuestal)
- **Propósito:** Calcular el impacto de un nuevo gasto sobre los presupuestos aplicables y emitir un veredicto.
- **Pureza Funcional:** No depende de base de datos ni de interfaces de I/O. Recibe datos primitivos puros (`gastoAcumulado`, `montoNuevo`, `ingresosCiclo`, `presupuestos`) y devuelve un resultado estructurado con aritmética entera verificada.
- **Cobertura:** Debe mantener 100% de cobertura de ramas (empates exactos, límites cero, umbrales y sobregiros).

---

## 4. CQRS Ligero (Command-Query Responsibility Segregation)

Para mantener desacoplados los Bounded Contexts y optimizar el rendimiento:
- **Lado de Escritura (Commands):** Se ejecuta siempre a través de Agregados y Repositorios del contexto dueño.
- **Lado de Lectura Transversal (Queries / Published Language):**
  - Módulos transversales como `:features:reporting-local` (exportación) y `:features:cloud-sync` (sincronización) **no** usan agregados ni repositorios mutantes ajenos.
  - Consumen interfaces de consulta de solo lectura publicadas por cada contexto (ej. `CycleSnapshotProvider`, `TransactionExportQuery`, `BudgetExecutionQuery`).
  - Las consultas leen DTOs de lectura directamente del Shared Kernel de base de datos sin pasar por la sobrecarga del ciclo de vida de los agregados.
