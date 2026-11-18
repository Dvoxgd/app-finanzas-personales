# Seguridad, Criptografía, Backup y Restauración

Este documento detalla los mecanismos de protección criptográfica, el formato del archivo de respaldo (`.fpbk`), el protocolo de restauración atómica y la sincronización en la nube.

---

## 1. Gestión de Claves Locales (`:core:security`)

1. **Clave Maestra de Base de Datos:**
   - La base de datos SQLCipher está protegida por una clave criptográfica de 256 bits generada localmente con un CSPRNG en el primer arranque (`DatabaseKeyManager`).
   - **Android:** Se guarda en almacenamiento privado envuelta con una clave AES maestra generada y resguardada en `AndroidKeyStore` (`allowBackup=false`).
   - **iOS:** Se almacena en el `Keychain` con el atributo de seguridad `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`.
   - **Desacoplamiento:** La clave maestra de la BD **no** se deriva de la frase de recuperación del usuario; son dos secretos completamente independientes.
2. **Borrado Total de Datos (Crypto-Erase):**
   - Cuando el usuario solicita "Eliminar todos mis datos", la app destruye la clave maestra en el Keystore/Keychain, elimina los archivos `.db`, `.db-wal`, `.db-shm` del disco y revoca tokens OAuth. Sin la clave, los datos cifrados residuales quedan matemáticamente inaccesibles de forma instantánea.

---

## 2. Instantánea Consistente (`VacuumBackupExecutor`)

- **Prohibido Copiar la BD en Caliente:** En modo WAL, copiar directamente el archivo `.db` puede generar copias corruptas o inconsistentes.
- **`VACUUM INTO`:** La instantánea se obtiene ejecutando:
  ```sql
  VACUUM INTO '/ruta/privada/temp_snapshot.db';
  ```
- **Verificación Previa al Empaquetado:** Sobre la copia temporal se ejecutan `PRAGMA integrity_check` y `PRAGMA foreign_key_check`.
- **Limpieza Segura:** El archivo temporal se sobrescribe con ceros antes de eliminarse en un bloque `finally`.

---

## 3. Formato del Backup: Envelope Versionado (`.fpbk`) (D-09)

El archivo de backup es un archivo binario estructurado para streaming y resistente a manipulaciones:

```text
┌──────────────────────────────────────────────────────────────┐
│ CABECERA (En claro, autenticada como AAD en los chunks)     │
│  - Magic: "FPBK" (4 bytes)                                   │
│  - FormatVersion: 1 (u8)                                     │
│  - SchemaVersion: 1 (u32)                                    │
│  - KdfId: 1 (Argon2id)                                       │
│  - KdfParams: memoryKiB (65536), iterations (3), parallel (1)│
│  - Salt: 16 bytes aleatorios                                 │
│  - AeadId: 1 (XChaCha20-Poly1305) o 2 (AES-256-GCM)          │
│  - ChunkSize: 1048576 (1 MiB)                                │
│  - CreatedAt: Instant ISO-8601 UTC                           │
│  - WrappedKeys: nonce + AEAD_seal(dataKey[32] || dbKey[32])  │
├──────────────────────────────────────────────────────────────┤
│ CUERPO (Chunks de 1 MiB cifrados con dataKey)                │
│  - Chunk 0: EncryptedBytes + AuthTag                         │
│  - Chunk 1: EncryptedBytes + AuthTag                         │
│  - ...                                                       │
│  - Chunk N: EncryptedBytes + AuthTag (isFinal = true)         │
└──────────────────────────────────────────────────────────────┘
```

1. **KDF (Argon2id Obligatorio):**
   - La Clave de Envoltura de Claves (KEK) se deriva de la frase de recuperación del usuario usando **Argon2id**.
   - Parámetros por defecto: `memoryKiB = 65536`, `iterations = 3`, `parallelism = 1`.
   - **Defensa contra Ataques DoS en Restore:** Antes de derivar KEK en un archivo descargado, se validan los parámetros de cabecera: rechazar si `memoryKiB > 262144` o `iterations > 10`.
2. **Autenticación de Integridad de Chunks (Anti-Tampering):**
   - El Additional Authenticated Data (AAD) de cada chunk contiene: `SHA-256(cabecera) + chunkIndex + isFinal`.
   - Reordenar, truncar, eliminar o alterar cualquier byte del backup hace fallar la autenticación de inmediato.

---

## 4. Protocolo de Restauración Atómica (`RestoreCloudBackupUseCase`)

La restauración de un backup reemplaza la base de datos activa de forma segura y atómica:

1. **Descarga y Validación:** Descarga el archivo `.fpbk` a un temporal privado.
2. **Descifrado:** Solicita la frase de recuperación, valida parámetros KDF, deriva la KEK, desenvuelve `dataKey` y `dbMasterKey`, y descifra el cuerpo a un archivo temporal `restore_candidate.db`.
3. **Chequeo de Integridad:** Abre `restore_candidate.db` con la `dbMasterKey` desempaquetada y ejecuta `PRAGMA integrity_check` y `PRAGMA foreign_key_check`.
4. **Verificación de Versión:**
   - Si `candidate.schemaVersion > app.supportedSchemaVersion` $\to$ abortar con error "Actualiza la app para abrir este backup".
   - Si `candidate.schemaVersion < app.supportedSchemaVersion` $\to$ aplicar migraciones `.sqm` sobre la copia temporal.
5. **Verificación de Invariantes Financieras:** Ejecutar `CycleContinuityService.verifyContinuity()` sobre el candidato.
6. **Reemplazo Atómico:**
   - La base de datos actual se renombra a `database.db.pre-restore`.
   - Se cierran las conexiones SQLDelight.
   - Se renombra `restore_candidate.db` a `database.db`.
   - Se adopta la nueva clave maestra en el Keystore/Keychain mediante `DatabaseKeyManager.adopt(newMasterKey)`.
   - Se reabre la conexión y se emite auditoría `RESTORE`.
7. **Rollback:** Ante cualquier falla en el paso 6, se restaura `database.db.pre-restore`.

---

## 5. Exportación a Google Sheets (Sincronización Unidireccional)

1. **Modo Unidireccional (D-10):** La aplicación únicamente exporta del dispositivo hacia Google Sheets; jamás lee o importa cambios desde Sheets.
2. **Prevención de Inyección de Fórmulas:** Toda escritura utiliza `valueInputOption = RAW` (prohibido `USER_ENTERED`).
3. **Cursor Monotónico con Outbox:**
   - La sincronización no depende de fechas (`updated_at` es susceptible a desfases horarios).
   - Depende de la tabla `sync_outbox` (autoincremental con triggers) y de `sync_state.last_outbox_id`.
4. **Advertencia de Privacidad:** Antes de sincronizar o exportar a Sheets/Excel, la aplicación alerta explícitamente al usuario de que la hoja de cálculo contendrá sus finanzas en texto en claro en su cuenta de Google.
