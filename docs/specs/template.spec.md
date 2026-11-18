# Template OpenSpec: Especificación Agéntica Ejecutable

```yaml
spec_id: "SPEC-XXX"
title: "Título Descriptivo de la Funcionalidad"
bounded_context: "cycle | accounting | budgeting | reporting-local | cloud-sync | core"
phase: "Fase X (§16 Prompt Maestro)"
status: "DRAFT | REVIEW | APPROVED | IN_PROGRESS | IMPLEMENTED | VERIFIED"
owner: "Usuario (Supervisor / Tech Lead)"
assigned_agent: "Antigravity Assistant"
created_at: "YYYY-MM-DD"
updated_at: "YYYY-MM-DD"
adrs_referenced:
  - "ADR-001"
docs_referenced:
  - "docs/agents/architecture.md"
  - "docs/agents/domain-rules.md"
  - "docs/agents/deviations.md"
```

---

## 1. Propósito y Alcance

### 1.1 Objetivo
*Descripción concisa del valor de negocio o capacidad técnica que esta especificación introduce en el sistema.*

### 1.2 Alcance (In Scope)
- [ ] Elemento 1 dentro del alcance.
- [ ] Elemento 2 dentro del alcance.

### 1.3 Fuera de Alcance (Out of Scope / No Objetivos)
- [ ] Elemento explícitamente excluido (referencia a §18 del Prompt Maestro si aplica).

---

## 2. Invariantes de Negocio y Reglas Bancarias

*Cita textual de las reglas de `docs/agents/domain-rules.md` que este componente debe honrar sin excepción.*

1. **Invariante 1:** (ej. Prohibición de punto flotante, importes en `Money` de 64 bits con unidades menores).
2. **Invariante 2:** (ej. Inmutabilidad en ciclos `CLOSED`).
3. **Invariante 3:** (ej. Optimistic locking con columna `version`).

---

## 3. Contratos de Interfaz y Tipado

*Definición previa de los contratos que el agente debe generar antes de implementar la lógica.*

### 3.1 Entidades y Value Objects (Dominio)
```kotlin
// Contrato de dominio esperado
data class XEntity(
    val id: XId,
    val amount: Money,
    val status: EntityStatus,
    val version: Int
)
```

### 3.2 Puertos / Interfaces de Repositorio o Servicios
```kotlin
// Firma del UseCase o Puerto
interface XRepository {
    suspend fun findById(id: XId): XEntity?
    suspend fun save(entity: XEntity)
}
```

### 3.3 Esquema SQL / Migración Requerida (si aplica)
```sql
-- DDL o consulta SQLDelight esperada
CREATE TABLE IF NOT EXISTS x_table (
    id TEXT PRIMARY KEY NOT NULL,
    -- columnas con CHECKs y status
);
```

---

## 4. Escenarios BDD Ejecutables (Kotest `BehaviorSpec`)

*Escenarios en lenguaje natural estructurado. El agente debe traducir estos bloques 1:1 al archivo `*Spec.kt` antes de escribir la implementación.*

### Escenario 1: Camino Feliz / Éxito
- **Given** una precondición en estado válido.
- **When** se ejecuta la acción o caso de uso.
- **Then** el resultado es `AppResult.Success(valor)` y se emite auditoría en `AuditRecorder`.

### Escenario 2: Violación de Regla de Negocio
- **Given** una condición que rompe una invariante (ej. ciclo en estado `CLOSED`).
- **When** se intenta insertar o modificar un registro.
- **Then** el resultado es `AppResult.Failure(DomainError.CycleClosed)` y no se ejecuta persistencia.

### Escenario 3: Concurrencia / Optimistic Locking
- **Given** un registro con `version = 1`.
- **When** dos procesos intentan actualizar el registro con la misma versión esperada.
- **Then** uno triunfa (`version = 2`) y el segundo falla con `DomainError.ConcurrentModification`.

---

## 5. Pruebas Basadas en Propiedades (Property-Based Testing)

*Invariantes que deben comprobarse contra miles de combinaciones automáticas con Kotest Property.*

- **Generador de Entradas:** Rango de valores sintéticos (ej. montos de $1$ a $10^{14}$ minor units, días de corte 1 a 31).
- **Propiedad a Verificar:** Invariante matemática o temporal que debe ser verdadera para toda entrada.

---

## 6. Comandos Autónomos de Verificación

*Comandos exactos que el agente debe ejecutar en la terminal para validar el cumplimiento:*

```bash
# 1. Compilación multiplataforma
./gradlew compileKotlinIosArm64 compileKotlinJvm

# 2. Ejecutar la especificación BDD
./gradlew testDebugUnitTest --tests "*XComponentSpec*"

# 3. Verificación Konsist
./gradlew :shared-app:test --tests "*KonsistArchitectureTest*"
```

---

## 7. Checklist de Aceptación (Definition of Done)

Para que el supervisor humano apruebe el PR o cierre la tarea:

- [ ] La especificación fue aprobada por el supervisor antes de codificar (`status: APPROVED`).
- [ ] Las pruebas `*Spec.kt` existen, compilan y pasan al 100%.
- [ ] No existen `Double`, `Float`, `TODO` ni placeholders en el código entregado.
- [ ] Reglas Konsist en verde (sin violaciones arquitectónicas).
- [ ] Verificación en ambas plataformas (Android e iOS).
- [ ] Documentación y diagramas actualizados en `docs/`.
