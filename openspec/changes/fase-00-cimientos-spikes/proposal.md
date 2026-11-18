# Proposal: Fase 0 - Cimientos del Proyecto, Spikes Técnicos y Gobernanza

## Why
Para garantizar una arquitectura escalable, libre de deuda técnica y con estándares de ingeniería bancaria desde el primer día, es indispensable establecer los cimientos del repositorio multimodular KMP + CMP antes de escribir código de negocio. Además, se deben mitigar y despejar documentalmente los 4 riesgos técnicos más altos del proyecto (spikes de SQLCipher en iOS, VACUUM INTO, Argon2id/AEAD y escritor ZIP STORE).

## What Changes
- Creación de la estructura multimodular KMP: `:core:common`, `:core:model`, `:core:database`, `:core:designsystem`, `:shared-app`, `:build-logic`.
- Configuración de dependencias en `gradle/libs.versions.toml` con versiones fijadas (R13).
- Definición de Convention Plugins de Gradle Kotlin DSL en `build-logic/`.
- Creación del documento de integración de contextos `docs/context-map.md`.
- Redacción de los 11 Architectural Decision Records iniciales (`docs/adr/ADR-001.md` al `ADR-011.md`).
- Documentación experimental con veredicto *go/no-go* de los 4 spikes técnicos de Fase 0.
- Esqueleto de pruebas de arquitectura Konsist para CI.

## Capabilities

### New Capabilities
- `foundation`: Andamiaje multimodular KMP, configuración unificada de build, catálogo de dependencias y guardarraíles Konsist.
- `spikes-verdict`: Pruebas de concepto y decisiones de viabilidad documentadas para SQLCipher en iOS, VACUUM INTO, criptografía y ZIP STORE.

### Modified Capabilities
<!-- Ninguna previa, es el inicio del sistema -->

## Impact
- Afecta la configuración raíz de Gradle, scripts de build y la estructura de directorios del proyecto.
- Establece los contratos para todas las fases posteriores (Fase 1 a Fase 6).
