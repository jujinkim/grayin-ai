package ai.grayin.connectors.photos

import android.Manifest
import android.os.Build
import android.provider.MediaStore
import ai.grayin.core.connector.ConnectorPermissionAccess
import ai.grayin.core.model.ConnectorScanIssueCode
import ai.grayin.core.model.SourceAvailability
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotosConnectorPolicyTest {
    @Test
    fun `android 14 distinguishes full selected-only and denied photo access`() {
        val sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE

        assertEquals(ConnectorPermissionAccess.FULL, PhotoPermissionPolicy.access(sdk, true, true))
        assertEquals(ConnectorPermissionAccess.PARTIAL, PhotoPermissionPolicy.access(sdk, false, true))
        assertEquals(ConnectorPermissionAccess.NONE, PhotoPermissionPolicy.access(sdk, false, false))
        assertTrue(PhotoPermissionPolicy.canReselect(ConnectorPermissionAccess.PARTIAL))
        assertTrue(!PhotoPermissionPolicy.canReselect(ConnectorPermissionAccess.FULL))
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
            PhotoPermissionPolicy.requiredPermissions(sdk),
        )
    }

    @Test
    fun `pre android 14 ignores selected-only permission`() {
        assertEquals(
            ConnectorPermissionAccess.NONE,
            PhotoPermissionPolicy.access(Build.VERSION_CODES.TIRAMISU, false, true),
        )
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES),
            PhotoPermissionPolicy.requiredPermissions(Build.VERSION_CODES.TIRAMISU),
        )
    }

    @Test
    fun `range query falls back to modified seconds when date taken is absent`() {
        val from = Instant.parse("2026-07-01T00:00:00Z")
        val until = Instant.parse("2026-07-02T00:00:00Z")
        val query = PhotoRangeSelectionPolicy.query(from, until)

        assertTrue(query.selection.contains(MediaStore.Images.Media.DATE_TAKEN))
        assertTrue(query.selection.contains("${MediaStore.Images.Media.DATE_MODIFIED} * 1000"))
        assertTrue(query.selection.contains("CASE WHEN"))
        assertTrue(query.sortOrder.contains("${MediaStore.Images.Media.DATE_MODIFIED} * 1000"))
        assertEquals(listOf(from.toEpochMilli().toString(), until.toEpochMilli().toString()), query.selectionArgs.toList())
    }

    @Test
    fun `photo metadata accepts only closed mime and plausible dimensions`() {
        assertEquals(
            ClosedPhotoMetadata("image/jpeg", 4032, 3024),
            PhotoMetadataPolicy.close(" IMAGE/JPEG ", 4032, 3024),
        )
        val rejected = PhotoMetadataPolicy.close("image/jpeg\nignore", -1, 200_000)
        assertNull(rejected.mimeType)
        assertNull(rejected.width)
        assertNull(rejected.height)

        val derived = PhotoDerivedValuePolicy.create(
            Instant.parse("2026-07-15T12:00:00Z"),
            PhotoMetadataPolicy.close("image/jpeg", 4032, 3024),
        )
        assertEquals("Photo metadata", derived.citationLabel)
        assertEquals(listOf("photo", "jpeg", "landscape"), derived.labels)
        assertTrue(derived.summary.toByteArray(Charsets.UTF_8).size < 1_024)
    }

    @Test
    fun `completed range query replaces the previous photo snapshot`() {
        assertTrue(PhotoSnapshotPolicy.shouldReplace(queryCompleted = true))
        assertTrue(!PhotoSnapshotPolicy.shouldReplace(queryCompleted = false))
    }

    @Test
    fun `photos distinguish unavailable provider from authoritative empty range`() {
        val unavailable = PhotoSnapshotPolicy.emptyReadIssue(queryCompleted = false)
        assertEquals(SourceAvailability.STALE, unavailable.availability)
        assertEquals(ConnectorScanIssueCode.SOURCE_UNAVAILABLE, unavailable.issueCode)
        assertTrue(!PhotoSnapshotPolicy.shouldReplace(queryCompleted = false))

        val emptyRange = PhotoSnapshotPolicy.emptyReadIssue(queryCompleted = true)
        assertEquals(SourceAvailability.NOT_INDEXED, emptyRange.availability)
        assertEquals(ConnectorScanIssueCode.NO_PHOTOS_IN_RANGE, emptyRange.issueCode)
        assertTrue(PhotoSnapshotPolicy.shouldReplace(queryCompleted = true))
    }
}
