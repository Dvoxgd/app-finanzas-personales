# Arquitectura del Gestor de Finanzas Personales (KMP + CMP)

Este documento establece las reglas y la estructura arquitectónica para el cliente móvil **Local-First** de Finanzas Personales desarrollado con **Kotlin Multiplatform (KMP)** y **Compose Multiplatform (CMP)** para Android e iOS.

El repositorio está organizado como un **monolito modular con base de datos compartida**:
- **A nivel físico:** `:core:database` es el **Shared Kernel** dueño del esquema relacional y las migraciones.
- **A nivel lógico:** Los Bounded Contexts están estrictamente desacoplados por puertos e interactúan mediante la capa Anti-Corruption Layer (ACL) ubicada en `:integration`.

---

## 1. Árbol de Módulos del Proyecto

```text
root/
├── build-logic/                     # Convention Plugins (Gradle Kotlin DSL) + libs.versions.toml
├── docs/                            # domain.md, context-map.md, adr/*.md, agents/*.md
├── core/
│   ├── common/                      # AppResult, DomainError, ClockProvider, TimeZoneProvider,
│   │                                # IdGenerator, TransactionRunner (INTERFAZ), AuditRecorder (INTERFAZ)
│   ├── model/                       # Money, BasisPoints, CurrencyCode, DateRange, RoundingHalfEven,
│   │                                # EntityStatus, CycleState, Typed IDs (@JvmInline)
│   ├── database/                    # SQLDelight .sq/.sqm, SQLCipher DriverFactory, TransactionRunnerImpl,
│   │                                # SqlErrorMapper (ERR_* -> DomainError), AuditRecorderImpl, VacuumBackupExecutor
│   ├── network/                     # Ktor HttpClientFactory genérico, reintentos (SIN lógica de Google)
│   ├── security/                    # Vault (Keystore/Keychain), DatabaseKeyManager, Argon2id, AEAD, BackupEnvelope
│   └── designsystem/                # Design tokens, tema adaptativo Material3/Cupertino, MoneyTextField, AlertBanner
│
├── features/
│   ├── cycle/
│   │   ├── domain/                  # Cycle, CutoffCalculator, CycleContinuityService, CycleRepository, CycleSnapshotProvider
│   │   ├── data/                    # CycleRepositoryImpl, CycleMapper, CycleSnapshotProviderImpl
│   │   └── ui/                      # CycleContract, CycleViewModel, CycleScreen, cycleGraph()
│   ├── accounting/
│   │   ├── domain/                  # Transaction, Category, PaymentMethod, SpendingCheck/SpendingVerdict,
│   │   │                            # CycleLookupPort, TransactionSpendingPolicy, TransactionExportQuery
│   │   ├── data/                    # Repositories, Mappers, consultas jerárquicas
│   │   └── ui/                      # TransactionFormViewModel, CategoryScreen, accountingGraph()
│   ├── budgeting/
│   │   ├── domain/                  # Budget, BudgetEvaluationEngine, LimitCalculator, BudgetRepository,
│   │   │                            # BudgetCycleLookupPort, CategoryLookupPort, CategorySpendingReader, BudgetExecutionQuery
│   │   ├── data/                    # BudgetRepositoryImpl, CategorySpendingReaderImpl, BudgetExecutionQueryImpl
│   │   └── ui/                      # BudgetListViewModel, BudgetDetailScreen, budgetingGraph()
│   ├── reporting-local/
│   │   ├── domain/                  # LocalReportGeneratorPort, ExportLocalReportUseCase, ReportModel
│   │   ├── data/                    # SqlDumpGenerator, SqlLiteral, OpenXmlExcelGenerator, ZipWriter (STORE), FileExporter
│   │   └── ui/                      # LocalReportViewModel, ExportReportBottomSheet
│   └── cloud-sync/
│       ├── domain/                  # CloudBackupGateway, SheetsSyncGateway, UseCases
│       ├── data/                    # GoogleAuthProvider (expect/actual), GoogleDriveClient, GoogleSheetsClient, OutboxSync
│       └── ui/                      # CloudSyncViewModel, CloudSyncSettingsScreen
│
├── integration/                     # ACL: CycleLookupAdapter, SpendingPolicyAdapter, BudgetCycleLookupAdapter, CategoryLookupAdapter
├── shared-app/                      # Raíz Koin DI, navegación compuesta tipada, MainAppScaffold, biometría
├── app-android/                     # Entrypoint Android: Application, MainActivity, AndroidManifest (allowBackup=false)
└── app-ios/                         # Entrypoint iOS: Xcode wrapper, ComposeUIViewController
```

---

## 2. Flujos Canónicos

### 2.1 Flujo Canónico Intra-Feature (MVI + Clean Architecture)

```text
UiScreen (CMP) 
   │ emits Intent
   ▼
ViewModel (MVI) 
   │ executes
   ▼
UseCase (Domain) 
   │ invokes inside TransactionRunner
   ├──► AuditRecorder.record(...)
   └──► RepositoryPort (Domain Interface)
            ▲
            │ implements
   RepositoryImpl (Data)
            │ calls
            ▼
   SQLDelight Queries (Core Database)
```

### 2.2 Flujo Canónico Inter-Contexto (ACL en `:integration`)

Los módulos `features:X` **tienen prohibido depender de `features:Y`**. Cuando el contexto A necesita información o servicios del contexto B:

```text
[accounting:domain]                    [:integration]                    [budgeting:domain]
TransactionSpendingPolicy (Puerto) <── SpendingPolicyAdapter ──────────► BudgetEvaluationEngine
SpendingCheck / SpendingVerdict         (Traduce tipos entre contextos)   CategorySpendingReader
```

**Regla de Traducción Estricta:** El ACL traduce tipos. Por ejemplo, `SpendingVerdict` es un tipo sellado propio de `accounting:domain`; el adaptador en `:integration` ejecuta el cálculo con el motor de `budgeting:domain` y traduce el resultado hacia `SpendingVerdict`. Ningún tipo de `budgeting` contamina `accounting`.

---

## 3. Matriz de Dependencias (Reglas Konsist R1–R13)

| Capa / Módulo | Puede Depender De | Estrictamente Prohibido |
|---|---|---|
| `:features:*:domain` | `:core:common`, `:core:model`, `kotlin-stdlib`, `kotlinx-coroutines-core`, `kotlinx-datetime` | UI, Compose, SQLDelight/SQLite, Ktor, okio, Android/iOS platform, DTOs de red/BD, otros `:features:*` |
| `:features:*:data` | Su propio `:features:*:domain`, `:core:database`, `:core:common`, `:core:model` (y opcionalmente `:core:network` o `:core:security`) | `:features:*:ui`, otros `:features:*`, tipos de vista |
| `:features:*:ui` | Su propio `:features:*:domain`, `:core:designsystem`, `:core:common`, `:core:model`, Compose Multiplatform, `lifecycle-viewmodel-compose` | `:features:*:data`, `:core:database`, entidades de base de datos directas, otros `:features:*` |
| `:integration` | **Solo** los submódulos `:domain` de los contextos involucrados, `:core:common` y `:core:model` | Módulos `:data`, módulos `:ui`, SQLDelight, Ktor |
| `:core:common` | `kotlin-stdlib`, `kotlinx-coroutines-core`, `kotlinx-datetime` | Ningún módulo de feature, ninguna base de datos concreta |
| `:core:model` | `kotlin-stdlib` (puro) | Coroutines, plataforma, bases de datos, redes |
| `:core:database` | `:core:common`, `:core:model`, drivers SQLDelight / SQLCipher | Ningún módulo de feature |
| `:shared-app` | Todos los módulos anteriores (compone Koin DI y la navegación central) | Lógica de negocio directa o queries SQL |

---

## 4. Frontera Transaccional y Concurrencia

1. **Abstracción `TransactionRunner`:**
   - La interfaz vive en `:core:common` (D-06) para que los UseCases y Domain Services la invoquen sin violar las dependencias de Clean Architecture.
   - La implementación vive en `:core:database` respaldada por transacciones de SQLite.
2. **Anidamiento y Rollback:**
   - Si un bloque transaccional se ejecuta dentro de otro ya abierto, se une a la transacción exterior.
   - Cualquier excepción no controlada o retorno de `AppResult.Failure` configurado para rollback revierte la totalidad de la transacción.
3. **Optimistic Locking:**
   - Toda tabla de negocio mutable contiene una columna `version INTEGER NOT NULL DEFAULT 1`.
   - Toda mutación ejecuta: `UPDATE ... SET version = version + 1 WHERE id = :id AND version = :expectedVersion`. Si el registro fue modificado concurrentemente, el trigger SQLite `trg_T_version_guard` aborta con `ERR_VERSION_CONFLICT`, que se traduce a `DomainError.ConcurrentModification`.
4. **Auditoría Append-Only:**
   - Todo caso de uso mutante (`CREATE`, `UPDATE`, `STATUS_CHANGE`, `CLOSE`, `ADJUST`) debe invocar `AuditRecorder.record(...)` **dentro de la misma transacción de base de datos**.

---

## 5. Identificación y Nomenclatura

| Elemento | Convención | Ejemplo |
|---|---|---|
| Value Object ID | `@JvmInline value class XId(val value: String)` | `CycleId`, `CategoryId`, `TransactionId` |
| Formato de ID | UUIDv7 canónico en String (36 caracteres, ordenable en tiempo) | Generado mediante `IdGenerator.newId()` |
| Entidad / Agregado | Nombre en singular | `Transaction`, `Category`, `Budget`, `Cycle` |
| Puerto de Repositorio | `XRepository` (interfaz en `:domain`) | `TransactionRepository`, `CycleRepository` |
| Adaptador de Repositorio | `XRepositoryImpl` (en `:data`) | `TransactionRepositoryImpl` |
| Caso de Uso | Verbo en infinitivo + sustantivo + `UseCase` | `EnsureCyclesUseCase`, `CloseCycleUseCase` |
| Contrato MVI | `XContract` con `XUiState`, `XIntent`, `XSideEffect` | `TransactionFormContract` |
| ViewModel | `XViewModel` (hereda de `ViewModel`) | `TransactionFormViewModel` |
| Subgrafo de Navegación | `NavGraphBuilder.xGraph(...)` con rutas `@Serializable` | `NavGraphBuilder.accountingGraph(...)` |
