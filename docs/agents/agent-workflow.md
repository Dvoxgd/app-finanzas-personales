# Flujo de Trabajo del Agente

Este documento define la metodología, el protocolo de desarrollo y los puntos de control obligatorios que todo agente de IA y desarrollador debe seguir en este repositorio.

---

## 1. Antes de Escribir Código

1. **Revisión de Especificaciones:** Consulta los archivos pertinentes en `docs/agents/`:
   - Arquitectura y dependencias: `architecture.md`.
   - Reglas bancarias y de cálculo: `domain-rules.md`.
   - Vistas y contratos MVI: `presentation-mvi.md`.
   - Persistencia y triggers: `persistence-sqldelight.md`.
   - Antipatrones a evitar: `deviations.md`.
2. **Contexto Estratégico y Decisiones:**
   - Consulta `docs/context-map.md` para verificar los puertos y adaptadores autorizados.
   - Revisa `docs/adr/` y la sección 1.1 del Prompt Maestro. **Las decisiones D-01 a D-15 están cerradas y no se reabren sin un ADR formal.**
3. **Inspección de Referencias:** Antes de duplicar patrones existentes, verifica `deviations.md` para no arrastrar prácticas prohibidas (como usar punto flotante, invocar `Clock.System.now()` directo o crear dependencias circulares entre features).
4. **Verificación de Entorno:** Confirma que el árbol de trabajo esté limpio y estés posicionado en la fase de entrega correcta (§16 del Prompt Maestro).

---

## 2. Puntos de Control: Detente y Pregunta Antes de...

- **Reabrir decisiones resueltas (D-01 a D-15):** En particular: cambiar SQLDelight por Room (D-01), permitir multimoneda en el MVP (D-02), o alterar la inmutabilidad de ciclos cerrados (D-13).
- **Modificar el DDL base (`1.sq`) una vez establecida la versión 1:** Todo cambio posterior exige un archivo de migración `N.sqm` con su correspondiente test de migración.
- **Agregar librerías externas o modificar versiones:** Toda dependencia debe figurar con versión fija en `gradle/libs.versions.toml` (sin rangos dinámicos ni `+`).
- **Crear dependencias directas entre features:** Si `accounting` necesita datos de `cycle` o `budgeting`, **debes** usar puertos definidos en el dominio consumidor e implementados en `:integration`.
- **Introducir `Double`, `Float` o `REAL`:** Queda estrictamente vetado en cualquier capa (dominio, datos, mappers, exportadores o UI).
- **Omitir la auditoría en mutaciones:** Todo UseCase que cree, modifique o borre lógicamente un registro debe invocar `AuditRecorder` dentro de `TransactionRunner.run`.

---

## 3. Comandos de Verificación Permitidos

Puedes ejecutar los siguientes comandos de forma autónoma en el entorno para validar tus cambios:

```bash
# 1. Compilación de código Kotlin multiplataforma
./gradlew compileKotlinIosArm64 compileKotlinJvm

# 2. Ejecución de pruebas unitarias y de arquitectura
./gradlew testDebugUnitTest
./gradlew iosSimulatorArm64Test

# 3. Verificación de reglas arquitectónicas con Konsist
./gradlew :shared-app:test --tests "*KonsistArchitectureTest*"

# 4. Verificación de estilo y análisis estático
./gradlew detekt ktlintCheck

# 5. Estado del repositorio
git status
git diff
```

---

## 4. Estándar de Commits

- **Atomicidad:** Un commit por componente o tarea lógica (ej. DDL + triggers, entidad de dominio + pruebas unitarias, UseCase + adaptador ACL, pantalla MVI + ViewModel).
- **Convención:** Formato Conventional Commits en minúsculas:
  - `feat(cycle): implement cutoff calculator and continuity service`
  - `test(budgeting): add property-based tests for budget evaluation engine`
  - `fix(accounting): prevent double counting in spending check policy`
  - `chore(database): add migration 2.sqm and contract test`
- **Condición de Integridad:** Cada commit DEBE compilar limpiamente y tener todas las pruebas de su alcance en verde. Prohibido comitear código con `TODO` o llamadas a `error("stub")`.

---

## 5. Checklist Obligatorio Antes de Finalizar la Tarea

- [ ] **Cero Punto Flotante:** No existe ningún `Double`, `Float` ni `BigDecimal`. Todo dinero usa `Money(Long, CurrencyCode)` y todo porcentaje usa `BasisPoints(Int)`.
- [ ] **Redondeo Bancario:** Toda división financiera usa `RoundingHalfEven.divide(n, d)` con algoritmo Half-Even exacto.
- [ ] **Determinismo:** Ningún UseCase ni Agregado invoca `Clock.System.now()`, `TimeZone.currentSystemDefault()` ni `UUID.randomUUID()`. Se inyectan `ClockProvider`, `TimeZoneProvider` e `IdGenerator`.
- [ ] **Transaccionalidad y Auditoría:** Toda mutación ocurre dentro de `TransactionRunner.run { ... }` y genera su registro en `AuditRecorder`.
- [ ] **Optimistic Locking:** Toda actualización valida y aumenta la columna `version`.
- [ ] **Borrado Lógico Universal:** No existen sentencias `DELETE FROM` en consultas de negocio. Todas las operaciones usan `status = 'DELETED'`.
- [ ] **Contrato Dominio vs. Triggers:** Cada regla de negocio crítica cuenta con pruebas que verifican que tanto el dominio como los triggers SQLite rechazan los mismos escenarios con los códigos `ERR_*` estipulados.
- [ ] **MVI Reactivo en CMP:**
  - `UiState` es `@Immutable` y solo contiene `UiModel` planos formateados.
  - El botón de guardado (`isSaveEnabled`) es completamente independiente del veredicto presupuestal (`SpendingVerdict`).
- [ ] **Reglas Konsist Cumplidas:** No hay dependencias cruzadas entre features ni fugas de infraestructura hacia los dominios.
- [ ] **Privacidad y Cero Leaks:** No se imprimen montos, notas, tokens ni datos personales en logs del sistema.
