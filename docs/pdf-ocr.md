# PDF and OCR Indexing

## Current Status

Settings can install, cancel, and delete the fixed English, Korean, and Japanese Tesseract language packs. The installer verifies and publishes each download atomically and does not bundle `.traineddata` files in the APK/AAB.

PDF selection, parsing, page rendering, and local OCR are not implemented yet. Installing a language pack therefore does not make PDF indexing available by itself. The remaining runtime work is tracked in `docs/roadmap.md`.

## Explicit Source Consent

- Only a PDF explicitly selected through Android's Storage Access Framework may be processed.
- A persisted read permission is scoped to that selected document.
- Folder crawling, automatic discovery, and media-wide PDF scans are forbidden.
- Selecting or indexing a document never downloads OCR language data.
- Each language-data download requires a separate Settings action.

## Fixed OCR Language Catalog

The bundled catalog pins `tesseract-ocr/tessdata_fast` commit `87416418657359cb625c412a48b6e1d6d41c29bd`.

| ID | File | Exact bytes | SHA-256 |
| --- | --- | ---: | --- |
| `eng` | `eng.traineddata` | 4,113,088 | `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2` |
| `kor` | `kor.traineddata` | 1,677,415 | `6b85e11d9bbf07863b97b3523b1b112844c43e713df8b66418a081fd1060b3b2` |
| `jpn` | `jpn.traineddata` | 2,471,260 | `1f5de9236d2e85f5fdf4b3c500f2d4926f8d9449f28f5394472d9e8d83b91b4d` |

Artifact URLs have the fixed form:

`https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/87416418657359cb625c412a48b6e1d6d41c29bd/{id}.traineddata`

The immutable Apache-2.0 license reference is:

`https://github.com/tesseract-ocr/tessdata_fast/blob/87416418657359cb625c412a48b6e1d6d41c29bd/LICENSE`

## Download and Installation Boundary

- WorkManager requires an unmetered network and non-low storage.
- Work input contains only pack ID and local generation; it contains no URL, document URI, document identity, content, query, or evidence.
- The downloader rejects cleartext, user information, custom ports, queries, fragments, redirects, unexpected content type/encoding, short or long bodies, and SHA-256 mismatch.
- A same-filesystem `.part` is flushed, verified, and published only with an atomic move.
- A monotonically increasing generation fences canceled and replaced workers.
- Retryable work stays queued; terminal state stores only a stable failure enum.
- A failed, canceled, or stale replacement preserves any last verified pack.
- Packs live under `noBackupFilesDir/ocr/tesseract/tessdata`; staging lives under the adjacent `staging` directory.
- The artifact host can observe the selected fixed pack path and ordinary network metadata such as IP address, but receives no document or memory data.

## Planned Local Document Runtime

The next implementation step must use a private, non-exported Android service in the `:document` process. That process is for crash and memory-pressure isolation; it is not an Android isolated UID because it must read the user-granted document descriptor and app-private language data.

The runtime contract is:

- validate a seekable `ParcelFileDescriptor` and PDF signature instead of trusting name, MIME type, or provider size alone
- prefer embedded page text
- render and OCR a page locally only when embedded text is absent or insufficient
- never call the network during parsing, rendering, or OCR
- return only bounded derived page signals and stable outcome codes across Binder
- never return PDF bytes, page bitmaps, full extracted text, OCR transcripts, document URIs, or exception messages across Binder
- recover from document-process death without crashing the main app or committing a partial connector snapshot

## Canonical Resource Limits

The PDF runtime must enforce these fail-closed limits:

| Resource | Limit |
| --- | ---: |
| PDF size | 25 MiB per document |
| PDF pages | 64 per document |
| OCR pages | 32 per document |
| Rendered bitmap | 4 megapixels and 2,048 px longest edge |
| Transient extracted/OCR text | 32 KiB per page |
| OCR time | 10 seconds per page |
| Total document processing | 2 minutes |
| Connector scan | 10 minutes |
| Derived output | 128 page-derived rows per scan |

Exceeding a limit returns a typed partial/unsupported result. It must not silently truncate while reporting a complete scan.

## Zero-Raw-Retention Lifecycle

Transient-only objects include the open descriptor, PDF parser state, rendered page bitmap, embedded page text, and OCR transcript. They must be closed or cleared after the bounded page scope and must never be written to a temporary PDF/image/text file, cache, log, SQLCipher table, WorkData, export, backup, or network request.

Persistent output is limited to an HMAC source reference, page citation metadata, bounded derived summary/keyword/entity signals, timestamp when available, capability tags, and confidence. Connector scan commit remains atomic, so removed or failed pages cannot leave stale mixed snapshots.

## Typed Outcomes

Document processing uses fixed `ConnectorScanIssueCode` values:

- `DOCUMENT_FILE_TOO_LARGE`
- `DOCUMENT_SIZE_UNKNOWN`
- `DOCUMENT_NOT_SEEKABLE`
- `PDF_PAGE_LIMIT_EXCEEDED`
- `PDF_PASSWORD_REQUIRED`
- `PDF_MALFORMED`
- `PDF_PAGE_DIMENSIONS_UNSUPPORTED`
- `OCR_MODEL_UNAVAILABLE`
- `OCR_PAGE_LIMIT_REACHED`
- `OCR_TIMED_OUT`
- `DOCUMENT_PROCESS_CRASHED`
- `NO_EXTRACTABLE_TEXT`
- `PARTIAL_DOCUMENT_INDEX`

Stored status contains only the stable code. UI text is localized after reading and never includes a file name, URI, parser exception, extracted text, OCR transcript, or provider response.

## Revocation and Deletion

Revoking Local Files must release its persisted URI permission when possible and delete the connector's derived SQLCipher snapshot. OCR language packs are independent public runtime artifacts and are removed only through their explicit Settings delete action.

## Dependencies and Notices

The planned runtime uses `io.legere:pdfiumandroid:1.0.35` for PDF access and `cz.adaptech.tesseract4android:tesseract4android:4.9.0` for local OCR. They are not yet present in the build. The runtime step must add dependency verification metadata, review transitive native libraries, and add required license notices before marking PDF/OCR complete.

## Verification

Required coverage includes catalog constants, URL closure, redirect/header/size/hash rejection, cancellation cleanup, generation fencing, last-verified preservation, no-backup placement, explicit Settings-only scheduling, PDF signature/seekability checks, every resource limit, embedded-text and OCR paths, process death, typed outcomes, no raw persistence, atomic snapshot replacement, and APK inspection proving that `.traineddata` and raw document fixtures are not shipped unintentionally. `:app:verifyDebugApkNoBundledOcrData` automates the `.traineddata` APK check.
