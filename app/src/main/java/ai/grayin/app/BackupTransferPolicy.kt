package ai.grayin.app

import ai.grayin.core.transfer.TransferFailureCode

internal object BackupTransferPolicy {
    fun retainImportStageAfter(code: TransferFailureCode): Boolean {
        return code == TransferFailureCode.AUTHENTICATION_FAILED ||
            code == TransferFailureCode.PASSWORD_POLICY_FAILED ||
            code == TransferFailureCode.CONSENT_RESET_FAILED ||
            code == TransferFailureCode.STORE_TRANSACTION_FAILED
    }
}
