package ai.grayin.core.connector

import ai.grayin.connectors.calendar.CalendarConnector
import ai.grayin.connectors.localfiles.LocalFilesConnector
import ai.grayin.connectors.location.LocationConnector
import ai.grayin.connectors.notification.NotificationConnector
import ai.grayin.connectors.photos.PhotosConnector
import ai.grayin.connectors.usagestats.AppUsageConnector
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectorDateRangeMetadataTest {
    @Test
    fun onlyHistoricalAndroidConnectorsAdvertiseDateRangeIndexing() {
        val metadata = listOf(
            CalendarConnector.METADATA,
            LocalFilesConnector.CONNECTOR_METADATA,
            LocationConnector.METADATA,
            NotificationConnector.METADATA,
            PhotosConnector.METADATA,
            AppUsageConnector.METADATA,
        )

        assertEquals(
            setOf("calendar", "photos", "app_usage"),
            metadata.filter(ConnectorMetadata::supportsDateRangeIndexing)
                .mapTo(mutableSetOf(), ConnectorMetadata::connectorId),
        )
    }
}
