# PDF and OCR Indexing

## Current Status

Settings can install, cancel, and delete the fixed English, Korean, and Japanese Tesseract language packs. The installer verifies and publishes each download atomically and does not bundle `.traineddata` files in the APK/AAB. The build includes the exact PdfiumAndroidKt and standard Tesseract4Android runtime libraries, locked dependency versions, SHA-256 dependency verification metadata, and packaged license notices. Open physical-device acceptance work is tracked only in `docs/remaining-work.md`.

The private `:document` runtime validates descriptors and signatures, extracts embedded page text, renders and runs local OCR only when needed, applies the canonical resource limits, and returns a bounded derived-only AIDL result. Local Files now accepts explicitly selected PDFs, sends only a read-only descriptor to that runtime, independently validates the terminal result, and maps accepted page signals to an atomic HMAC-only derived graph. Installing a language pack alone still does not select or index PDFs.

## Explicit Source Consent

- Only a PDF explicitly selected through Android's Storage Access Framework may be processed.
- A persisted read permission is scoped to that selected document. Local Files is the only current owner of persisted SAF read grants in the app; a future feature must add an ownership registry before taking one.
- Grayin preferences store only a domain-separated Android Keystore HMAC marker, not the selected URI or file name. The live URI is resolved from Android's persisted permission list at scan time.
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
- OCR runtime eligibility is determined by re-verifying the installed file and hash directly, never by cross-process preference status; a verified last-good pack remains usable during replacement work.
- Packs live under `noBackupFilesDir/ocr/tesseract/tessdata`; staging lives under the adjacent `staging` directory.
- The artifact host can observe the selected fixed pack path and ordinary network metadata such as IP address, but receives no document or memory data.

## Local Document Runtime

The runtime uses a private, non-exported Android service in the `:document` process. That process is for crash and memory-pressure isolation; it is not an Android isolated UID because it must read the user-granted document descriptor and app-private language data.

The runtime contract is:

- validate a seekable `ParcelFileDescriptor` and PDF signature instead of trusting name, MIME type, or provider size alone
- prefer embedded page text
- render and OCR a page locally only when embedded text is absent or insufficient
- never call the network during parsing, rendering, or OCR
- return only bounded derived page signals and stable outcome codes across Binder
- never return PDF bytes, page bitmaps, full extracted text, OCR transcripts, document URIs, or exception messages across Binder
- recover from document-process death without crashing the main app or committing a partial connector snapshot

The AIDL request contains only a random request ID and duplicated descriptor. The terminal result contains a protocol version, fixed outcome/issue/confidence/extraction codes, page numbers, bounded structural counts, and at most eight bounded keyword signals per page. It is independently validated below 64 KiB in both processes. It contains no URI, path, file name, MIME value, source identity, raw text, bitmap, byte payload, exception, or free-form error. OCR never materializes a full-page transcript or a copied image `Pixa`; recognition is bounded by the 4-megapixel render limit and a read-only result iterator admits at most 512 geometrically bounded words into the 32 KiB UTF-8 accumulator. A non-blocking 10-second deadline starts before Tesseract construction and returns the typed timeout when native work yields; a separate 12-second hard watchdog terminates only `:document` if JNI remains stuck. An equivalent hard document watchdog protects the two-minute limit.

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

Per-document limit failures return a typed partial/unsupported result. The connector's aggregate 128-page cap adds `DOCUMENT_DERIVED_OUTPUT_LIMIT_REACHED` and `PARTIAL_DOCUMENT_INDEX`. If the 10-minute connector deadline expires, the coroutine is canceled and no partial replacement is committed, preserving the previous SQLCipher snapshot.

## Zero-Raw-Retention Lifecycle

Transient-only objects include the open descriptor, PDF parser state, rendered page bitmap, embedded page text, and OCR transcript. They must be closed or cleared after the bounded page scope and must never be written to a temporary PDF/image/text file, cache, log, SQLCipher table, WorkData, export, backup, or network request.

Persistent output is limited to a full 64-character page HMAC source reference, a closed `PDF page N` citation, bounded structural summary/keyword signals, connector scan status codes, timestamp, labels, and confidence. The URI, document HMAC, file name, and MIME never enter the graph. Every terminal Local Files scan fully replaces its prior connector snapshot in one SQLCipher transaction, so removed, revoked, shortened, or failed documents cannot leave stale page rows. Invalid Binder results are discarded and represented only by `DOCUMENT_PROCESS_CRASHED`.

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
- `DOCUMENT_PROCESS_TIMED_OUT`
- `NO_EXTRACTABLE_TEXT`
- `PARTIAL_DOCUMENT_INDEX`
- `DOCUMENT_DERIVED_OUTPUT_LIMIT_REACHED`

Text and Markdown scans may additionally report `LOCAL_DOCUMENT_TEXT_LIMIT_REACHED` when the connector's 64 Ki-character transient read limit is reached. A migrated legacy selection above 128 documents is scanned in deterministic HMAC order up to the current bound and reports `LOCAL_DOCUMENT_SELECTION_LIMIT_REACHED`; no new selection can exceed that bound, and revoke still covers every stored legacy marker.

Stored status contains only the stable code. UI text is localized after reading and never includes a file name, URI, parser exception, extracted text, OCR transcript, or provider response.

## Revocation and Deletion

Selecting a document commits its bounded HMAC marker before taking the persisted read grant, and selection/revoke grant operations share one process lock. The connector never acquires a new grant after the 128-marker bound. Revoking Local Files attempts to release every app-held persisted SAF read grant, then re-enumerates the grant list and clears HMAC markers only when none remain. A release, verification, or preference failure reports revoke failure without deleting the SQLCipher snapshot. Local Files has no preferences indexing checkpoint; Sources derives indexed status from SQLCipher.

After successful permission revoke, the controller deletes the connector's derived SQLCipher snapshot. Deleting derived data alone keeps the selection and permission so the user can reindex. Both delete paths fence pending and running queue work in the deletion transaction, preventing an in-flight scan from republishing after deletion. OCR language packs are independent public runtime artifacts and are removed only through their explicit Settings delete action.

## Dependencies and Notices

The build uses `io.legere:pdfiumandroid:1.0.35` from Maven Central for PDF access and the standard `cz.adaptech.tesseract4android:tesseract4android:4.9.0` variant for local OCR. JitPack is an `exclusiveContent` repository for that one Tesseract module only; it cannot supply or shadow other dependencies.

`app/gradle.lockfile` locks the resolved versions, and `gradle/verification-metadata.xml` verifies artifact and metadata SHA-256 values for executable build/runtime dependencies. Android Studio-only source and Javadoc attachments, including the synthetic Gradle source component, are narrowly trusted by filename or component so IDE sync does not require those non-executable attachments in the checksum inventory. The reviewed AAR hashes are `862ed337d6b52485fefba9ced9fe7fdb800d41fb300d8c8ebb03d8bea64d72f0` for PdfiumAndroidKt and `bce5d6413a1a5ae3d7240033fbbc851ba3217d0a08d9769400e17a077f42cb2a` for Tesseract4Android.

The AAR native inventory and license mapping are documented in `docs/third-party-notices.md`. Readable notice and license files ship under `app/src/main/assets/third_party_licenses/`. PDF selection and SQLCipher commit remain connector-owned so no raw descriptor or incomplete cross-process result can enter the store.

## Verification

Required coverage includes catalog constants, URL closure, redirect/header/size/hash rejection, cancellation cleanup, generation fencing, last-verified preservation, no-backup placement and platform extraction rules, explicit Settings-only scheduling, PDF signature/seekability checks, every resource limit, embedded-text and OCR paths, process death, typed outcomes, HMAC-only source identity, closed Local Files output, fail-closed HMAC migration, verified all-grant revoke, deletion fencing, atomic snapshot replacement, and APK inspection proving that `.traineddata` and raw document fixtures are not shipped unintentionally. `:app:verifyDebugApkNoBundledOcrData` automates the `.traineddata` APK check, `:app:verifyDebugApkPdfOcrNotices` confirms that all PDF/OCR notice assets are packaged, and `:app:verifyDebugApkDocumentBoundary` rejects production PDF fixtures and verifies every expected native library/ABI. JVM, lint, debug/release APK, and instrumentation-source compilation are covered on the host. The open device/emulator matrix and completion criteria live only under section 1.2 of `docs/remaining-work.md`.
