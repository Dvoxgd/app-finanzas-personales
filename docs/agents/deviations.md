# Antipatrones y Desvíos Prohibidos

Este documento cataloga las prácticas, simplificaciones o antipatrones que **están terminantemente prohibidos** en este repositorio. Cualquier PR o fragmento de código que contenga alguna de estas desviaciones será rechazado automáticamente por CI o por revisión.

---

## Tabla de Desvíos y Alternativa Correcta

| Desvío / Antipatrón Prohibido | Dónde Suele Intentarse | Qué DEBE Hacerse en Este Proyecto |
|---|---|---|
| **Uso de `Double`, `Float` o `BigDecimal`** | Cálculos monetarios, porcentajes o parseo de texto | **Estándar Bancario Absoluto:** Usar `Money(minor: Long, currency: CurrencyCode)` y `BasisPoints(value: Int)`. El dinero se opera en unidades menores enteras. Para redondeo: `RoundingHalfEven.divide(n, d)`. |
| **Invocación directa de tiempo o UUIDs** (`Clock.System.now()`, `TimeZone.currentSystemDefault()`, `UUID.randomUUID()`) | UseCases, Agregados, Domain Services | **Inyección de Abstracciones:** Inyectar forzosamente `ClockProvider`, `TimeZoneProvider` e `IdGenerator` (UUIDv7) para garantizar pruebas 100% deterministas. |
| **Reapertura de ciclos cerrados (`CLOSED → OPEN`)** | Ajustes contables, corrección de transacciones pasadas | **Inmutabilidad Contable (D-13):** Prohibido reabrir ciclos cerrados. Toda corrección se registra como una **Transacción de Ajuste** (`adjusts_transaction_id`) en un ciclo actualmente `OPEN`. |
| **Bloqueo del botón Guardar por alerta de sobregiro** | Formulario de transacciones en Compose | **Comportamiento No Bloqueante:** `isSaveEnabled` depende **exclusivamente** de la validez sintáctica del formulario. Las alertas de presupuesto (`SpendingVerdict`) son informativas. |
| **Dependencias directas entre módulos de features** (`features:accounting` importando `features:budgeting`) | Verificación de presupuestos o consulta de ciclos | **Puertos y Adaptadores ACL:** El dominio consumidor define un puerto (ej. `TransactionSpendingPolicy`) y el módulo `:integration` lo implementa traduciendo los datos. |
| **Uso de Room KMP** | Acceso a base de datos | **Decisión D-01:** **SQLDelight** sobre SQLite con cifrado **SQLCipher** es el único motor autorizado. |
| **Borrado físico (`DELETE FROM`) en tablas de negocio** | Eliminación de transacciones, presupuestos o categorías | **Borrado Lógico Universal:** Toda entidad de negocio usa `status = 'DELETED'`. Los triggers abortan cualquier `DELETE` físico con `ERR_PHYSICAL_DELETE_FORBIDDEN`. |
| **Modificar fechas de ciclos mediante `cycle.copy(...)`** | Ajustes de corte de ciclo | **Servicio de Continuidad Obligatorio:** Toda mutación de fechas pasa por `CycleContinuityService`, el único componente autorizado para garantizar la ausencia de solapes y días huérfanos. |
| **Exportación a Sheets bidireccional o con fórmulas** | Sincronización en la nube | **Exportación Unidireccional (D-10):** Sincronización solo dispositivo $\to$ Sheets usando `valueInputOption = RAW` para impedir inyecciones, guiada por el cursor de `sync_outbox`. |
| **Uso de entidades de Dominio o BD en `@Composable`** | Pantallas de Compose Multiplatform | **Aislamiento de UI:** Compose solo recibe `@Immutable` `UiModel` formateados con cadenas primitivas. |
| **Actualizar registros sin incrementar `version`** | Repositorios y queries SQL | **Optimistic Locking:** Todo `UPDATE` exige `WHERE id = :id AND version = :expectedVersion` y suma `version = version + 1`. |
| **Omitir auditoría en operaciones mutantes** | Casos de uso de creación o edición | **Trazabilidad:** Todo UseCase mutante debe registrar en `AuditRecorder.record(...)` dentro de la misma transacción de BD. |
| **Compresión ZIP con librerías externas o métodos incompatibles** | Generador de reportes `.xlsx` | **ZIP STORE en commonMain (D-15):** Generación O(1) con `okio` usando método STORE y data descriptor propio. |
| **Secretos de cliente embebidos en código** | Flujo de autenticación OAuth Google | **OAuth 2.0 con PKCE:** Flujo directo sin intermediarios ni claves secretas compiladas. |
| **Uso de `TODO()`, placeholders o `...` en código entregado** | Fases de desarrollo | **Contrato de Entrega Estricto:** Cada archivo entregado debe compilar al 100%, con tipos completos, sin código omitido y con tests verdes. |
