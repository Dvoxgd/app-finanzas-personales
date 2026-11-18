# Capability Spec: Foundation

## ADDED Requirements

### Requirement: Multimodule Architecture Integrity
The repository MUST be configured as a modular monolith with physical module separation following `docs/agents/architecture.md`.

#### Scenario: Base Project Compiles for Android and iOS
- **Given** un entorno limpio con JDK compatible y Gradle 9.x.
- **When** se compilan los targets de Android e iOS (`assembleDebug` y `compileKotlinIosSimulatorArm64`).
- **Then** el build finaliza con éxito sin errores de resolución de dependencias.

### Requirement: Strict Dependency Versioning (R13)
All dependencies and plugins MUST be declared in `gradle/libs.versions.toml` with pinned versions without ranges or dynamic wildcards.

#### Scenario: No Dynamic Versions Allowed
- **Given** el archivo `gradle/libs.versions.toml`.
- **When** se analizan las entradas de versiones.
- **Then** ninguna versión contiene caracteres como `+`, `latest`, o rangos dinámicos.

### Requirement: Architecture Verification with Konsist
The CI pipeline MUST execute Konsist tests in `:shared-app` enforcing rules R1 to R6.

#### Scenario: Domain Module Does Not Depend on Infrastructure
- **Given** cualquier módulo con sufijo `:domain`.
- **When** se analizan sus importaciones mediante Konsist.
- **Then** no existen imports de `androidx.compose`, `app.cash.sqldelight`, `io.ktor`, ni tipos `Double`/`Float`.
