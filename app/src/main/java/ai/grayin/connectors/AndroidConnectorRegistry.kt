package ai.grayin.connectors

import android.content.Context
import ai.grayin.connectors.calendar.CalendarConnector
import ai.grayin.connectors.localfiles.LocalFilesConnector
import ai.grayin.connectors.location.LocationConnector
import ai.grayin.connectors.notification.NotificationConnector
import ai.grayin.connectors.photos.PhotosConnector
import ai.grayin.connectors.usagestats.AppUsageConnector
import ai.grayin.core.connector.MemoryConnectorRegistry

class AndroidConnectorRegistry(context: Context) {
    private val applicationContext = context.applicationContext

    val localFilesConnector = LocalFilesConnector(applicationContext)

    val registry = MemoryConnectorRegistry(
        listOf(
            LocationConnector(applicationContext),
            PhotosConnector(applicationContext),
            CalendarConnector(applicationContext),
            NotificationConnector(applicationContext),
            AppUsageConnector(applicationContext),
            localFilesConnector,
        ),
    )
}
