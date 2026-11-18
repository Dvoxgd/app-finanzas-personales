# Design: Fase 0 - Cimientos y Spikes

## Context
El proyecto es un cliente móvil autónomo (*Local-First*) desarrollado en Kotlin Multiplatform (KMP) y Compose Multiplatform (CMP) para Android e iOS.
Se rige por el Prompt Maestro v2.0, las decisiones resueltas D-01 a D-15 y las reglas de `docs/agents/architecture.md`.

## Goals / Non-Goals

**Goals:**
- Configuración de Gradle con Convention Plugins (`build-logic/`) para evitar duplicación de scripts entre módulos.
- Catálogo de dependencias estricto en `gradle/libs.versions.toml` con versiones fijadas.
- Esqueleto de módulos compilables para Android e iOS.
- Documentación completa de `docs/context-map.md` y `docs/adr/ADR-001.md` al `ADR-011.md`.
- Veredictos go/no-go documentados para los 4 spikes críticos.
- Guardarraíles Konsist configurados en `:shared-app`.

**Non-Goals:**
- Implementación de entidades de dominio de negocio o queries SQL de transacciones (corresponde a Fase 1).
- Diseño final de pantallas o flujos MVI (Fase 2 y 3).
- Integración real con Google Drive o Google Sheets API (Fase 5).

## Decisions
1. **Convention Plugins en `build-logic`:** Estandarizar la configuración de KMP, Android Library y Compose Multiplatform mediante plugins de Gradle Kotlin DSL.
2. **Shared Kernel Físico:** El módulo `:core:database` centralizará las dependencias de SQLDelight y SQLCipher, aislándolo de los dominios.
3. **Reglas Konsist como Código:** Las restricciones R1 a R13 se ejecutan como tests unitarios en `:shared-app` para fallar el build ante violaciones de capas.

## Risks / Trade-offs
- **Riesgo:** SQLCipher nativo en iOS y VACUUM INTO bajo cifrado.
  - *Mitigación:* Spike 1 y Spike 2 con alternativas aprobadas en ADR-002.
- **Riesgo:** Generación de ZIP STORE en streaming sin librerías pesadas en `commonMain`.
  - *Mitigación:* Spike 4 con `okio` y CRC32 streaming.
