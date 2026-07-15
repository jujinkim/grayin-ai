package ai.grayin.app

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Closed Android document-picker policy for encrypted backups that request on-device documents. */
internal object BackupDocumentContractPolicy {
    const val MIME_TYPE = "application/vnd.ai.grayin.backup"
    const val FALLBACK_MIME_TYPE = "application/octet-stream"
    const val FILE_EXTENSION = ".grayin"

    val acceptedMimeTypes: Array<String>
        get() = arrayOf(MIME_TYPE, FALLBACK_MIME_TYPE)

    fun suggestedFileName(createdAt: Instant): String {
        return "grayin-backup-${FILE_NAME_TIMESTAMP.format(createdAt)}$FILE_EXTENSION"
    }

    fun requireValidSuggestedFileName(fileName: String): String {
        require(fileName.matches(SAFE_FILE_NAME)) { "Backup file name is invalid." }
        require(fileName.endsWith(FILE_EXTENSION)) { "Backup file extension is invalid." }
        return fileName
    }

    private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val FILE_NAME_TIMESTAMP = DateTimeFormatter
        .ofPattern("yyyy-MM-dd-HHmmss")
        .withZone(ZoneOffset.UTC)
}

/** Requests an on-device destination from the system document picker. */
internal class CreateLocalBackupDocumentContract : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent {
        val suggestedName = BackupDocumentContractPolicy.requireValidSuggestedFileName(input)
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = BackupDocumentContractPolicy.MIME_TYPE
            putExtra(Intent.EXTRA_TITLE, suggestedName)
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return intent
            ?.data
            ?.takeIf { resultCode == Activity.RESULT_OK && it.scheme == ContentResolver.SCHEME_CONTENT }
    }
}

/** Requests an on-device source from the system document picker. */
internal class OpenLocalBackupDocumentContract : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/*"
            putExtra(Intent.EXTRA_MIME_TYPES, BackupDocumentContractPolicy.acceptedMimeTypes)
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return intent
            ?.data
            ?.takeIf { resultCode == Activity.RESULT_OK && it.scheme == ContentResolver.SCHEME_CONTENT }
    }
}
