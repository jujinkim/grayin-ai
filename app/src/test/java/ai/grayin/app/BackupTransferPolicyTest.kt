package ai.grayin.app

import ai.grayin.core.transfer.TransferFailureCode
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupTransferPolicyTest {
    @Test
    fun `only retryable import failures retain encrypted stage`() {
        val expected = setOf(
            TransferFailureCode.AUTHENTICATION_FAILED,
            TransferFailureCode.PASSWORD_POLICY_FAILED,
            TransferFailureCode.CONSENT_RESET_FAILED,
            TransferFailureCode.STORE_TRANSACTION_FAILED,
        )

        assertEquals(
            expected,
            TransferFailureCode.entries.filterTo(
                destination = mutableSetOf(),
                predicate = BackupTransferPolicy::retainImportStageAfter,
            ),
        )
    }
}
