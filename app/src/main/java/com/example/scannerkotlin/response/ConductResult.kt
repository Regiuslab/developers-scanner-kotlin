package com.example.scannerkotlin.response

import com.example.scannerkotlin.model.ComingOperationState
import com.example.scannerkotlin.model.ProductOffer
import com.example.scannerkotlin.model.ValidationIssue

data class ConductResult(
    val isSuccess: Boolean,
    val message: String? = null,
    val state: ComingOperationState = if (isSuccess) ComingOperationState.COMMITTED else ComingOperationState.FAILED,
    val operationId: String? = null,
    val validationIssues: List<ValidationIssue> = emptyList(),
    val failedPositionIndex: Int? = null,
    val reconciledAlreadyConducted: Boolean = false
) {
    sealed class SaveResult {
        data class Success(val savedVariations: List<Pair<Int, ProductOffer>>) : SaveResult()
        data class Failure(
            val errorMessage: String,
            val failedPositionIndex: Int? = null,
            val isConflict: Boolean = false,
            val requiresReconciliation: Boolean = false
        ) : SaveResult()
    }
}
