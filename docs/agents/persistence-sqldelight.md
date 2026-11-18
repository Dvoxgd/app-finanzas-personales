# Persistencia con SQLDelight y SQLCipher

Este documento establece las directrices para el acceso a datos y la gestión del esquema relacional en el módulo **`:core:database`**, el cual actúa como el **Shared Kernel físico** de la aplicación.

---

## 1. Configuración Obligatoria de Conexión

Al abrir cada conexión a la base de datos (mediante `DriverFactory` en Android e iOS), se deben ejecutar los siguientes comandos en orden estricto antes de cualquier otra interacción:

```sql
-- 1. Primero, apertura con la clave maestra de SQLCipher (256 bits en hexadecimal crudo)
PRAGMA key = "x'...'";

-- 2. PRAGMAs obligatorios de integridad y rendimiento (fuera de transacciones)
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA secure_delete = ON;
PRAGMA busy_timeout = 5000;
```

---

## 2. Organización del Esquema y Consultas `.sq`

El código SQL se divide por contexto bajo el directorio `core/database/src/commonMain/sqldelight/com/presupuesto/database/`:

```text
com/presupuesto/database/
├── 1.sq                        # Esquema baseline DDL completo con tablas y triggers
├── migrations/
│   └── 1.sqm                   # Migraciones futuras versionadas (2.sqm, 3.sqm, etc.)
├── cycle/
│   └── cycle.sq                # Queries propias de ciclos financieros
├── accounting/
│   ├── transaction.sq          # Queries de transacciones contables
│   ├── category.sq             # Queries de categorías y árbol jerárquico
│   └── payment_method.sq       # Queries de medios de pago
├── budgeting/
│   └── budget.sq               # Queries de presupuestos y roll-up recursivo
├── audit/
│   └── audit.sq                # Inserción append-only de auditoría
└── sync/
    └── sync.sq                 # Consultas del outbox y estado de sincronización
```

---

## 3. Reglas Estrictas de Persistencia

1. **Prohibición de Borrado Físico (`DELETE FROM`):**
   - Prohibido ejecutar `DELETE FROM` o `DROP TABLE` sobre tablas de negocio (`cycles`, `categories`, `payment_methods`, `budgets`, `transactions`, `audit_log`, `app_settings`).
   - Los triggers `trg_*_forbid_physical_delete` rechazan cualquier intento con `ERR_PHYSICAL_DELETE_FORBIDDEN`.
   - Única excepción documentada: las tablas técnicas de sincronización (`sync_outbox`, `sync_state`, `sync_row_map`) permiten purga tras confirmación de sincronización.
2. **Filtrado Operativo Obligatorio:**
   - Toda consulta de lectura operativa y cálculo debe incluir explícitamente `WHERE status = 'ACTIVE'`.
   - Para ciclos: `status = 'ACTIVE'` y `state IN ('OPEN', 'CLOSED')`.
3. **Optimistic Locking:**
   - Toda mutación debe verificar y aumentar el contador de versión:
     ```sql
     UPDATE transactions
     SET amount_minor = :newAmount,
         version = version + 1,
         updated_at = :updatedAt
     WHERE id = :id AND version = :expectedVersion;
     ```
   - Si no coincide la versión, el trigger `trg_T_version_guard` aborta con `ERR_VERSION_CONFLICT`.
4. **Triggers con Códigos de Error Estables (`ERR_*`):**
   - Toda validación de trigger aborta mediante `RAISE(ABORT, 'ERR_<CODIGO>: <mensaje>')`.
   - La clase `SqlErrorMapper` en `:core:database` extrae el código mediante la expresión regular `ERR_[A-Z_]+` y lo mapea al subtipo exacto de `DomainError`.

---

## 4. Consultas Clave de Referencia

### 4.1 Roll-up Jerárquico de Gastos con Exclusión de Transacción en Edición

Esta consulta calcula el gasto acumulado de una categoría y todas sus subcategorías (CTE recursiva), excluyendo la transacción que se está editando para evitar el doble conteo:

```sql
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
```

### 4.2 Base de Ingresos para Presupuestos Porcentuales

Calcula el total de ingresos de un ciclo excluyendo los ajustes contables:

```sql
SELECT COALESCE(SUM(amount_minor), 0) AS total_income_minor
FROM transactions
WHERE cycle_id = :cycleId 
  AND type = 'INCOME' 
  AND status = 'ACTIVE' 
  AND adjusts_transaction_id IS NULL;
```

### 4.3 Presupuestos Aplicables a una Categoría y su Padre

```sql
SELECT b.* FROM budgets b
WHERE b.cycle_id = :cycleId 
  AND b.status = 'ACTIVE'
  AND b.category_id IN (:categoryId, (SELECT parent_id FROM categories WHERE id = :categoryId));
```

---

## 5. Implementación de Adaptador de Repositorio

El adaptador en `:features:*:data` une el puerto de dominio con el cliente generado por SQLDelight:

```kotlin
package com.presupuesto.features.cycle.data.repository

import com.presupuesto.core.database.SqlErrorMapper
import com.presupuesto.core.model.CycleId
import com.presupuesto.database.CycleQueries
import com.presupuesto.features.cycle.data.mapper.toDomain
import com.presupuesto.features.cycle.domain.model.Cycle
import com.presupuesto.features.cycle.domain.repository.CycleRepository

class CycleRepositoryImpl(
    private val queries: CycleQueries,
    private val errorMapper: SqlErrorMapper
) : CycleRepository {

    override suspend fun findById(id: CycleId): Cycle? {
        return queries.selectById(id.value)
            .executeAsOneOrNull()
            ?.toDomain()
    }

    override suspend fun insert(cycle: Cycle) {
        errorMapper.safeExecute {
            queries.insertCycle(
                id = cycle.id.value,
                name = cycle.name,
                start_date = cycle.dateRange.start.toString(),
                end_date = cycle.dateRange.end.toString(),
                is_manual_override = if (cycle.isManualOverride) 1 else 0,
                state = cycle.state.name,
                status = cycle.status.name,
                version = cycle.version,
                created_at = cycle.createdAt.toString(),
                updated_at = cycle.updatedAt.toString()
            )
        }
    }
}
```
