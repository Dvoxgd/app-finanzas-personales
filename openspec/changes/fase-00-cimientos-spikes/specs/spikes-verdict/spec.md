# Capability Spec: Spikes Verdict

## ADDED Requirements

### Requirement: Documented Spike Verdicts
The 4 high-risk technical spikes defined in §16 of the Master Prompt MUST have their analysis and go/no-go verdict documented in `docs/spikes/`.

#### Scenario: Verification of the 4 Spike Decisions
- **Given** el directorio `docs/spikes/`.
- **When** se revisan los artefactos de análisis técnico.
- **Then** existen documentos para:
  1. SQLCipher + SQLDelight en iOS nativo.
  2. VACUUM INTO bajo SQLCipher.
  3. Argon2id y AEAD multiplataforma.
  4. Escritor ZIP STORE en streaming con `okio`.
- **And** cada uno especifica la alternativa elegida y su justificación.
