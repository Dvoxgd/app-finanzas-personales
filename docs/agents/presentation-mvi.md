# Capa de Presentación MVI (Compose Multiplatform)

Este documento define la arquitectura y las reglas de implementación para la capa de interfaz de usuario en Compose Multiplatform (CMP), basada en el patrón **MVI (Model-View-Intent)** y ViewModels de ciclo de vida multiplataforma (`androidx.lifecycle:lifecycle-viewmodel`).

---

## 1. Contrato Canónico MVI (`*Contract.kt`)

Cada pantalla se rige por un archivo de contrato estricto compuesto por tres elementos sellados o inmutables:

```kotlin
package com.presupuesto.features.accounting.ui.transaction

import androidx.compose.runtime.Immutable
import com.presupuesto.core.model.CategoryId
import com.presupuesto.features.accounting.ui.model.CategoryUiModel
import com.presupuesto.features.accounting.ui.model.SpendingBannerUiModel

// 1. ESTADO DE LA PANTALLA (Unidireccional, Inmutable)
@Immutable
data class TransactionFormUiState(
    val amountText: String = "",
    val selectedCategory: CategoryUiModel? = null,
    val selectedDateText: String = "",
    val notesText: String = "",
    val verdict: SpendingBannerUiModel? = null,
    val isFormValid: Boolean = false,
    val isSaveEnabled: Boolean = false, // REGLA: depende SOLO de isFormValid, NUNCA del veredicto
    val isLoading: Boolean = false
)

// 2. INTENCIONES DEL USUARIO (Eventos de Entrada)
sealed interface TransactionFormIntent {
    data class AmountChanged(val text: String) : TransactionFormIntent
    data class CategorySelected(val id: CategoryId) : TransactionFormIntent
    data class DateChanged(val localDateString: String) : TransactionFormIntent
    data class NotesChanged(val notes: String) : TransactionFormIntent
    data object SaveClicked : TransactionFormIntent
    data object DismissWarningClicked : TransactionFormIntent
}

// 3. EFECTOS SECUNDARIOS EFÍMEROS (Navegación, Snackbars, Toast)
sealed interface TransactionFormSideEffect {
    data object NavigateBackOnSaved : TransactionFormSideEffect
    data class ShowToastError(val message: String) : TransactionFormSideEffect
}
```

---

## 2. ViewModel Canónico MVI

```kotlin
package com.presupuesto.features.accounting.ui.transaction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.presupuesto.core.common.AppResult
import com.presupuesto.features.accounting.domain.usecase.CreateTransactionUseCase
import com.presupuesto.features.accounting.domain.port.TransactionSpendingPolicy
import com.presupuesto.features.accounting.ui.model.toUiModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class TransactionFormViewModel(
    private val createTransactionUseCase: CreateTransactionUseCase,
    private val spendingPolicy: TransactionSpendingPolicy
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionFormUiState())
    val uiState: StateFlow<TransactionFormUiState> = _uiState.asStateFlow()

    private val _sideEffects = Channel<TransactionFormSideEffect>(Channel.BUFFERED)
    val sideEffects: Flow<TransactionFormSideEffect> = _sideEffects.receiveAsFlow()

    fun onIntent(intent: TransactionFormIntent) {
        when (intent) {
            is TransactionFormIntent.AmountChanged -> handleAmountChanged(intent.text)
            is TransactionFormIntent.CategorySelected -> handleCategorySelected(intent.id)
            is TransactionFormIntent.SaveClicked -> handleSave()
            else -> Unit
        }
    }

    private fun handleAmountChanged(text: String) {
        _uiState.update { current ->
            val valid = isFormValid(text, current.selectedCategory)
            current.copy(
                amountText = text,
                isFormValid = valid,
                isSaveEnabled = valid // NUNCA condicionar con el veredicto
            )
        }
        recalculateSpendingVerdict()
    }

    private fun recalculateSpendingVerdict() {
        // Debounce de 200 ms y evaluación reactiva
        // El veredicto solo actualiza _uiState.verdict, NO altera isSaveEnabled
    }

    private fun handleSave() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = createTransactionUseCase(/* params */)
            when (result) {
                is AppResult.Success -> {
                    _sideEffects.send(TransactionFormSideEffect.NavigateBackOnSaved)
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false) }
                    _sideEffects.send(TransactionFormSideEffect.ShowToastError(result.error.toString()))
                }
            }
        }
    }

    private fun isFormValid(amount: String, category: Any?): Boolean {
        return amount.isNotBlank() && category != null
    }
}
```

---

## 3. Reglas Críticas de UI y MVI

1. **Desacoplamiento Estricto del Guardado:**
   - Si el usuario gasta por encima del presupuesto (`BudgetExceeded`) o supera el umbral (`ThresholdWarning`), la UI muestra un banner de advertencia informativo con el monto excedido.
   - **`isSaveEnabled` permanece en `true` si el formulario es válido.** El usuario es el dueño de su dinero y la aplicación nunca le impide registrar un gasto real.
2. **Cero Modelos de Dominio en Composable:**
   - Las funciones `@Composable` **nunca** reciben agregados de dominio (`Transaction`, `Cycle`, `Budget`).
   - Reciben exclusivamente **`UiModel` inmutables** con los datos ya transformados a cadenas y formatos legibles para el usuario.
3. **Manejo de Texto Monetario (`MoneyTextField`):**
   - El componente de entrada de dinero opera con cadenas de texto y enteros, parseando de acuerdo a la moneda vigente (`CurrencyCode.minorUnitExponent`).
   - Queda estrictamente vetado el uso de `String.toDoubleOrNull()`.
4. **Accesibilidad en Alertas:**
   - Los banners informativos de gasto (`AlertBanner`) del design system no dependen únicamente del color para comunicar el estado: incluyen íconos representativos y descripciones de contenido para lectores de pantalla.

---

## 4. Subgrafos de Navegación Tipados por Feature

Cada módulo `:features:*:ui` es autónomo y no conoce las rutas de navegación de otros módulos. Cada feature expone una función de extensión sobre `NavGraphBuilder`:

```kotlin
// features/accounting/ui/src/commonMain/kotlin/com/presupuesto/features/accounting/ui/navigation/AccountingGraph.kt
package com.presupuesto.features.accounting.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
sealed interface AccountingRoute {
    @Serializable
    data object TransactionList : AccountingRoute

    @Serializable
    data class TransactionForm(val transactionId: String? = null) : AccountingRoute
}

fun NavGraphBuilder.accountingGraph(
    onNavigateToCategoryManagement: () -> Unit,
    onNavigateBack: () -> Unit
) {
    composable<AccountingRoute.TransactionList> {
        // TransactionListScreen
    }
    composable<AccountingRoute.TransactionForm> {
        // TransactionFormScreen
    }
}
```

El módulo `:shared-app` es el único responsable de ensamblar los subgrafos en el `NavHost` principal y cablear las navegaciones inter-módulos mediante lambdas.
