package com.example.scannerkotlin.model

/**
 * Lifecycle states of an incoming warehouse document operation.
 */
enum class ComingOperationState {
    DRAFT,
    VALIDATING,
    READY,
    PROCESSING,
    COMMITTED,
    FAILED,
    RECONCILIATION_REQUIRED;

    fun toRussianLabel(): String = when (this) {
        DRAFT -> "Черновик"
        VALIDATING -> "Проверка позиций..."
        READY -> "Готов к проведению"
        PROCESSING -> "Проведение..."
        COMMITTED -> "Проведён"
        FAILED -> "Ошибка проведения"
        RECONCILIATION_REQUIRED -> "Требуется сверка с Битрикс"
    }

    companion object {
        fun fromNameOrDefault(raw: String?, default: ComingOperationState = DRAFT): ComingOperationState {
            if (raw.isNullOrBlank()) return default
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: default
        }
    }
}

enum class ComingItemStatus {
    PENDING,
    VALIDATED,
    VARIATION_RESOLVED,
    BARCODE_ASSIGNED,
    ELEMENT_ADDED,
    METADATA_UPDATED,
    ERROR,
    COMMITTED;

    companion object {
        fun fromNameOrDefault(raw: String?, default: ComingItemStatus = PENDING): ComingItemStatus {
            if (raw.isNullOrBlank()) return default
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: default
        }
    }
}

enum class ComingSyncState {
    LOCAL_DIRTY,
    LOCAL_SAVED,
    SYNCING,
    REMOTE_VARIATION_CREATED,
    REMOTE_ELEMENT_CREATED,
    REMOTE_COMMITTED,
    SYNC_ERROR;

    companion object {
        fun fromNameOrDefault(raw: String?, default: ComingSyncState = LOCAL_SAVED): ComingSyncState {
            if (raw.isNullOrBlank()) return default
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: default
        }
    }
}

data class ComingDocumentDraftRecord(
    val documentId: Int,
    val operationId: String,
    val documentTitle: String?,
    val destinationStoreId: Int = 1,
    val destinationStoreTitle: String? = null,
    val comment: String? = null,
    val allowedNomenclatureIds: List<Double>? = null,
    val state: ComingOperationState = ComingOperationState.DRAFT,
    val syncState: ComingSyncState = ComingSyncState.LOCAL_SAVED,
    val lastErrorMessage: String? = null,
    val conductAttemptCount: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val lastActiveAtMs: Long = System.currentTimeMillis(),
    val items: List<Product> = emptyList(),
    val deletedRemoteElementIds: List<Int> = emptyList()
) {
    val itemCount: Int get() = items.size

    val isRecoverableActiveDraft: Boolean
        get() = state != ComingOperationState.COMMITTED &&
            (items.isNotEmpty() ||
                !comment.isNullOrBlank() ||
                state == ComingOperationState.PROCESSING ||
                state == ComingOperationState.RECONCILIATION_REQUIRED ||
                state == ComingOperationState.FAILED)
}

data class OperationStepJournalEntry(
    val stepId: Long = 0L,
    val operationId: String,
    val documentId: Int,
    val localItemId: String? = null,
    val stepType: String,
    val status: String,
    val remoteEntityId: Int? = null,
    val details: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class ValidationIssue(
    val positionIndex: Int,
    val localItemId: String?,
    val productId: Int?,
    val field: String,
    val message: String
)

sealed class SaveOutcome {
    data class Saved(val updatedAtMs: Long, val itemCount: Int) : SaveOutcome()
    data class Failed(val errorMessage: String, val cause: Throwable? = null) : SaveOutcome()
}
