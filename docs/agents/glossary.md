# Glosario de Dominio y Trampas Frecuentes

Este documento establece el **Lenguaje Ubicuo** formal del sistema de Finanzas Personales «Local-First» y cataloga las trampas conceptuales más comunes para prevenir errores de implementación.

---

## 1. Términos del Negocio y Correspondencia en Código

| Término | Definición de Negocio | En el Código | En Base de Datos | No Confundir Con |
|---|---|---|---|---|
| **Ciclo Financiero** | Periodo contable personalizado delimitado por fechas de corte (desacoplado del mes calendario). | `Cycle`, `features:cycle:domain` | Tabla `cycles` (`id`, `start_date`, `end_date`, `state`, `status`) | Mes calendario (`YYYY-MM`). |
| **Día de Corte** | Día del mes (1..31) configurado por el usuario para fijar el corte de cuentas. | `billingCutoffDay: Int` en `AppSettings` | Columna `billing_cutoff_day` en `app_settings` | Fecha de pago o vencimiento. |
| **Ajuste de Fin de Mes (Clamp)** | Mecanismo que traslada un corte en día inexistente al último día del mes (ej. día 31 en febrero pasa a 28 o 29). | `CutoffCalculator.calculateCycleBounds` | Lógica en `features:cycle:domain` | Salto de mes. |
| **Estado Contable (`state`)** | Condición operativa de un ciclo: `OPEN` (admite imputaciones) o `CLOSED` (contablemente bloqueado e inmutable). | `CycleState` (`OPEN`, `CLOSED`) | Columna `state` en `cycles` | Estado de ciclo de vida (`status`). |
| **Ciclo de Vida (`status`)** | Estado del registro físico: `ACTIVE`, `INACTIVE` o `DELETED`. | `EntityStatus` | Columna `status` en todas las tablas de negocio | Estado contable (`state`). |
| **Transacción** | Registro de un movimiento de dinero real (`INCOME` o `EXPENSE`). | `Transaction`, `features:accounting` | Tabla `transactions` | Movimiento bancario de cuenta corriente (no manejado). |
| **Ajuste Contable** | Transacción en un ciclo `OPEN` que corrige o compensa un hecho de un ciclo cerrado. | `Transaction.adjustsTransactionId` | Columna `adjusts_transaction_id` en `transactions` | Reapertura de ciclo. |
| **Roll-up Jerárquico** | Acumulación recursiva del gasto de una categoría padre sumando el gasto de todas sus subcategorías. | Consulta CTE en `CategorySpendingReader` | CTE recursiva en `budget.sq` | Suma plana simple. |
| **Unidades Menores** | Representación entera del dinero en céntimos según el exponente de la moneda. | `Money.minor: Long` | Columna `amount_minor INTEGER` | Cantidad decimal o de punto flotante. |
| **Puntos Base (bps)** | Unidad de medida para porcentajes ($10000 = 100.00\%$, $1 = 0.01\%$). | `BasisPoints.value: Int` | `target_value_raw` (en porcentaje), `alert_threshold_bps` | Porcentaje flotante `0.0 .. 1.0`. |
| **Veredicto de Gasto** | Diagnóstico no bloqueante en tiempo real del impacto de un gasto sobre los presupuestos. | `SpendingVerdict` (`WithinSafeLimits`, `ThresholdWarning`, `BudgetExceeded`) | Calculado en memoria (`BudgetEvaluationEngine`) | Regla bloqueante de guardado. |
| **Tombstone** | Registro anulado lógicamente (`status = 'DELETED'`), retenido para auditoría y sincronización. | `EntityStatus.DELETED` | `status = 'DELETED'` | Fila borrada físicamente con `DELETE`. |
| **Optimistic Lock** | Control de concurrencia mediante un entero que se incrementa en cada actualización. | `version: Int` en entidades | Columna `version INTEGER` en todas las tablas mutables | Bloqueo pesimista de fila en SQLite. |
| **Outbox de Sync** | Tabla técnica append-only que encola mutaciones con un cursor monotónico para la nube. | `SyncOutboxEntry` | Tabla `sync_outbox` (`outbox_id`, `entity_type`, `entity_id`, `op`) | Registro de auditoría (`audit_log`). |

---

## 2. Trampas Frecuentes de Dominio

### Trampa 1: `occurred_at` vs. `local_date`
- **Error Común:** Calcular a qué ciclo pertenece una transacción usando su instante UTC `occurred_at`.
- **Regla Correcta:** La asignación a un ciclo se determina estrictamente por **`local_date`** (`YYYY-MM-DD`). Esta fecha civil se calcula en el momento exacto del registro convirtiendo `occurred_at` mediante la zona horaria del dispositivo (`TimeZoneProvider`). Esto evita que un gasto efectuado a las 23:30 caiga en el día siguiente debido al desfase UTC.

### Trampa 2: Ingresos en Presupuestos Porcentuales
- **Error Común:** Sumar todas las transacciones de tipo `INCOME` del ciclo para calcular la base del porcentaje.
- **Regla Correcta:** La base de ingresos para presupuestos porcentuales exige filtrar obligatoriamente **`adjusts_transaction_id IS NULL`**. Los ingresos de ajuste corresponden a correcciones de periodos anteriores y no representan ingresos genuinos generados en el ciclo corriente.

### Trampa 3: Cascada de Borrado en Categorías y Presupuestos
- **Error Común:** Eliminar físicamente los presupuestos de una categoría eliminada o marcar como eliminados los presupuestos de ciclos pasados.
- **Regla Correcta:**
  - Al marcar una categoría como `DELETED`, sus subcategorías se marcan como `DELETED` en la misma transacción.
  - Sus presupuestos asociados en ciclos **`OPEN`** se marcan como `DELETED`.
  - Sus presupuestos en ciclos **`CLOSED` permanecen intactos** (son historia inmutable).
  - Sus transacciones históricas en cualquier ciclo **permanecen intactas**.

### Trampa 4: Monomoneda en la Instalación pero Tipado Seguro en `Money`
- **Error Común:** Asumir que como la app es monomoneda por instalación (D-02), `Money` puede ser solo un `Long` sin código de moneda.
- **Regla Correcta:** `Money` siempre encapsula `CurrencyCode` (`Money(minor: Long, currency: CurrencyCode)`). Esto garantiza seguridad de tipos, permite que las operaciones rechacen monedas diferentes y asegura que el formateo decimal use el exponente real de la moneda (`minorUnitExponent`).

### Trampa 5: Eliminación de Ciclos
- **Error Común:** Permitir la eliminación de cualquier ciclo intermedio si no tiene transacciones.
- **Regla Correcta:** Solo se puede eliminar lógicamente el **último ciclo** en la línea de tiempo temporal, siempre que esté en estado `OPEN` y no contenga transacciones ni presupuestos activos. Eliminar un ciclo intermedio rompería la invariante de continuidad dejando un hueco temporal.

### Trampa 6: Doble Conteo en la Edición de Transacciones
- **Error Común:** Consultar el gasto acumulado del ciclo sumando todas las transacciones cuando el usuario está modificando el monto de una existente.
- **Regla Correcta:** Al evaluar el presupuesto durante una edición, el cálculo debe excluir el ID de la transacción actual (`AND t.id != :excludeTransactionId`). De lo contrario, se sumaría el monto nuevo sobre el monto anterior de la misma transacción.

### Trampa 7: `state` (Negocio) vs. `status` (Técnico)
- **Error Común:** Usar un solo campo para saber si un ciclo está activo o cerrado.
- **Regla Correcta:** Son dos dimensiones ortogonales:
  - `state`: `OPEN` (permite movimientos) o `CLOSED` (cerrado contablemente).
  - `status`: `ACTIVE` (vigente) o `DELETED` (anulado lógicamente).
  - Un ciclo contablemente cerrado es `state = 'CLOSED'` y `status = 'ACTIVE'`.
