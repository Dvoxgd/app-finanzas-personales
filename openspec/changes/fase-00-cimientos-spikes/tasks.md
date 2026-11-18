# Tasks: Fase 0 - Cimientos y Spikes

## 1. Configuración de Build y Dependencias

- [ ] 1.1 Configurar `gradle/libs.versions.toml` con versiones fijadas (SQLDelight, Koin, Konsist, Kotest, Ktor, okio).
- [ ] 1.2 Crear el módulo `build-logic/` con convention plugins para KMP y Compose.
- [ ] 1.3 Crear los módulos base vacíos (`:core:common`, `:core:model`, `:core:database`, `:core:designsystem`, `:shared-app`) y registrarlos en `settings.gradle.kts`.

## 2. Documentación Estratégica y Decisiones de Arquitectura

- [ ] 2.1 Crear `docs/context-map.md` con la matriz de puertos y contratos del §5.3.
- [ ] 2.2 Redactar borradores formales de `ADR-001` al `ADR-011` en `docs/adr/`.
- [ ] 2.3 Documentar los 4 Spikes técnicos críticos en `docs/spikes/` con decisiones *go/no-go*.

## 3. Guardarraíles y Pruebas Konsist

- [ ] 3.1 Crear el esqueleto de pruebas Konsist en `:shared-app` para validar R1–R6 y prohibición de Float/Double.
- [ ] 3.2 Crear workflow base de GitHub Actions (`.github/workflows/ci.yml`).

## 4. Verificación y Cierre

- [ ] 4.1 Compilar targets de Android e iOS (`assembleDebug` y `compileKotlinIosSimulatorArm64`).
- [ ] 4.2 Ejecutar pruebas de Konsist (`./gradlew :shared-app:test`).
- [ ] 4.3 Ejecutar `openspec validate fase-00-cimientos-spikes`.
