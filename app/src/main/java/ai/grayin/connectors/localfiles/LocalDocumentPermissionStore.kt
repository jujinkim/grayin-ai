package ai.grayin.connectors.localfiles

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

internal interface LocalDocumentPermissionStore {
    fun persistRead(uri: Uri)

    fun releaseRead(uri: Uri)

    fun persistedReadUris(): List<Uri>
}

internal class AndroidLocalDocumentPermissionStore(
    private val contentResolver: ContentResolver,
) : LocalDocumentPermissionStore {
    override fun persistRead(uri: Uri) {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun releaseRead(uri: Uri) {
        contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun persistedReadUris(): List<Uri> {
        return contentResolver.persistedUriPermissions
            .filter { permission -> permission.isReadPermission }
            .map { permission -> permission.uri }
    }
}
