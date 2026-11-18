# OpenSpec: SPEC-000 - Cimientos del Proyecto, Spikes Técnicos y Gobernanza (Fase 0)

```yaml
spec_id: "SPEC-000"
title: "Cimientos del Proyecto, Spikes Técnicos y Gobernanza Arquitectónica"
bounded_context: "core"
phase: "Fase 0 (§16 Prompt Maestro)"
status: "REVIEW"
owner: "Diego (Supervisor / Tech Lead)"
assigned_agent: "Antigravity Assistant"
created_at: "2026-11-18"
updated_at: "2026-11-18"
adrs_referenced:
  - "ADR-001"
  - "ADR-002"
  - "ADR-003"
  - "ADR-004"
  - "ADR-005"
  - "ADR-006"
  - "ADR-007"
  - "ADR-008"
  - "ADR-009"
  - "ADR-010"
  - "ADR-011"
docs_referenced:
  - "docs/agents/architecture.md"
  - "docs/agents/agent-workflow.md"
  - "docs/agents/testing.md"
  - "docs/agents/deviations.md"
```

---

## 1. Propósito y Alcance

### 1.1 Objetivo
Establecer los cimientos del repositorio multimodular en Kotlin Multiplatform (KMP) y Compose Multiplatform (CMP), configurar la infraestructura de build con Convention Plugins (`build-logic`), fijar versiones de dependencias en `libs.versions.toml`, documentar los ADRs iniciales (001 al 011), diseñar `docs/context-map.md`, redactar el esqueleto de pruebas Konsist para CI y ejecutar los 4 spikes técnicos de alto riesgo con veredicto documentado.

### 1.2 Alcance (In Scope)
- [ ] Configuración del catálogo de dependencias `gradle/libs.versions.toml` con versiones fijadas (Kotlin 2.x, Compose Multiplatform, SQLDelight, Koin, Coroutines, Ktor, okio, Konsist, Kotest).
- [ ] Módulo `build-logic/` con Convention Plugins de Gradle Kotlin DSL para estandarizar la configuración KMP/CMP entre módulos.
- [ ] Estructura de módulos vacíos compilables: `:core:common`, `:core:model`, `:core:database`, `:core:designsystem`, `:shared-app`, `:app-android`, `:app-ios`.
- [ ] Creación de `docs/context-map.md` con la matriz de puertos y adaptadores inter-contexto (§5.3 del Prompt Maestro).
- [ ] Redacción de los borradores de decisión arquitectónica `docs/adr/ADR-001.md` hasta `docs/adr/ADR-011.md`.
- [ ] Esqueleto de pruebas Konsist en `:shared-app` para verificar las reglas R1 a R13.
- [ ] Documentación y veredicto go/no-go de los 4 Spikes de Fase 0:
  1. **Spike 1:** SQLCipher + SQLDelight en iOS (Native Driver y XCFramework).
  2. **Spike 2:** `VACUUM INTO` bajo cifrado con SQLCipher (conservación de clave vs. exportación).
  3. **Spike 3:** Criptografía multiplataforma (Argon2id + AEAD XChaCha20/AES-GCM en Android e iOS).
  4. **Spike 4:** Escritor ZIP STORE en streaming sin librerías pesadas (`okio` en `commonMain`).
- [ ] Pipeline base de CI (`.github/workflows/ci.yml`) para build, test y Konsist.

### 1.3 Fuera de Alcance (Out of Scope)
- Implementación de entidades de negocio o tablas relacionales de la aplicación (corresponde a Fase 1).
- Vistas finales o navegación compleja de la interfaz de usuario.
- Integración real con las APIs de Google Drive o Google Sheets (corresponde a Fase 5).

---

## 2. Invariantes Arquitectónicas y Reglas Aplicables

1. **Versiones Fijadas (Regla R13):** Prohibido usar comodines (`+`), versiones dinámicas o rangos en `gradle/libs.versions.toml`.
2. **Aislamiento de Dependencias (Reglas R1–R6):**
   - `:core:model` no puede depender de ninguna librería de base de datos, red, UI ni coroutines.
   - `:core:common` solo puede depender de stdlib, coroutines y `kotlinx-datetime`.
3. **Plataformas Soportadas:** Compilación garantizada para Android (minSdk 26, targetSdk 35) e iOS (iOS 15+ para simulador `iosSimulatorArm64` y dispositivo `iosArm64`).

---

## 3. Entregables y Contratos de Fase 0

### 3.1 Catálogo de Versiones (`gradle/libs.versions.toml`)
Debe incluir bloques de versiones para:
- `kotlin`, `compose-multiplatform`, `agp` (Android Gradle Plugin)
- `sqldelight`, `sqlcipher-android`
- `koin`, `coroutines`, `datetime`, `okio`, `ktor`
- `konsist`, `kotest`, `turbine`

### 3.2 Documento de Context Map (`docs/context-map.md`)
Debe documentar formalmente la tabla de puertos y contratos del §5.3 del Prompt Maestro:
- `CycleLookupPort`
- `TransactionSpendingPolicy`
- `BudgetCycleLookupPort`
- `CategoryLookupPort`
- `CategorySpendingReader`
- `CycleSnapshotProvider`
- `TransactionExportQuery`
- `BudgetExecutionQuery`

### 3.3 Borradores ADR (`docs/adr/ADR-001.md` al `ADR-011.md`)
Estructura de cada ADR:
- **Título e Identificador**
- **Estado** (Propuesto / Aceptado / En Spike)
- **Contexto del Problema**
- **Decisión Adoptada**
- **Alternativas Evaluadas**
- **Consecuencias Técnicas**

### 3.4 Esqueleto Konsist (`KonsistArchitectureTest.kt`)
Pruebas mínimas de CI que garanticen que:
- Ninguna clase en paquetes `domain` importa clases de `androidx.compose`, `app.cash.sqldelight`, ni `io.ktor`.
- No existen tipos `Double` ni `Float` en los módulos `core:model` ni `core:common`.

---

## 4. Escenarios BDD de Verificación de Cimientos

### Escenario 1: Compilación Multiplataforma Limpia
- **Given** un proyecto clonado en un entorno limpio de desarrollo.
- **When** se ejecuta la tarea de compilación para Android e iOS (`./gradlew assembleDebug compileKotlinIosArm64`).
- **Then** la compilación termina con código de salida `0` y sin errores de resolución de dependencias.

### Escenario 2: Verificación de Reglas de Arquitectura con Konsist
- **Given** los módulos base de `:core` y `:shared-app`.
- **When** se ejecutan las pruebas de Konsist (`./gradlew :shared-app:test`).
- **Then** todas las reglas de frontera de módulos y prohibición de punto flotante en modelo pasan en verde.

### Escenario 3: Veredictos de Spikes Documentados
- **Given** los 4 spikes técnicos de alto riesgo.
- **When** el supervisor revisa la documentación de spikes en `docs/spikes/`.
- **Then** cada spike cuenta con: problema analizado, código del spike, resultado experimental y decisión final *go/no-go*.

---

## 5. Comandos Autónomos de Verificación

```bash
# 1. Compilación del esqueleto KMP
./gradlew compileKotlinJvm compileKotlinIosArm64

# 2. Ensamblado de aplicación Android vacía
./gradlew :app-android:assembleDebug

# 3. Pruebas de arquitectura Konsist
./gradlew :shared-app:test --tests "*KonsistArchitectureTest*"

# 4. Verificación de formato y análisis estático
./gradlew detekt ktlintCheck
```

---

## 6. Checklist de Aceptación (Definition of Done de Fase 0)

- [ ] `gradle/libs.versions.toml` creado con todas las versiones fijadas.
- [ ] `build-logic/` operativo con convention plugins para KMP y Compose.
- [ ] Módulos `:core:common`, `:core:model`, `:core:database`, `:core:designsystem`, `:shared-app`, `:app-android`, `:app-ios` creados con sus respectivos `build.gradle.kts`.
- [ ] La app vacía compila y arranca en Android e iOS.
- [ ] `docs/context-map.md` redactado y alineado con §5.3 del Prompt Maestro.
- [ ] `docs/adr/ADR-001.md` hasta `docs/adr/ADR-011.md` redactados en borrador con plantilla estándar.
- [ ] Los 4 spikes técnicos tienen documento de conclusiones en `docs/spikes/` con veredicto *go/no-go*.
- [ ] Pruebas base de Konsist pasando en verde.
