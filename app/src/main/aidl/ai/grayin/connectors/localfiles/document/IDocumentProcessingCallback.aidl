package ai.grayin.connectors.localfiles.document;

import ai.grayin.connectors.localfiles.document.DocumentProcessingResult;

interface IDocumentProcessingCallback {
    oneway void onComplete(String requestId, in DocumentProcessingResult result);
}
