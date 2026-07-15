package ai.grayin.connectors.localfiles.document;

import android.os.ParcelFileDescriptor;
import ai.grayin.connectors.localfiles.document.IDocumentProcessingCallback;

interface IDocumentProcessingService {
    oneway void process(
        String requestId,
        in ParcelFileDescriptor descriptor,
        IDocumentProcessingCallback callback
    );

    oneway void cancel(String requestId);
}
