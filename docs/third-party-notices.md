# PDF and OCR Third-Party Notices

The PDF/OCR runtime uses two exact Android artifacts:

| Artifact | Source release | Packaged native libraries | License coverage |
| --- | --- | --- | --- |
| `io.legere:pdfiumandroid:1.0.35` | `PdfiumAndroidKt` tag `v1.0.35`; headers match PDFium `140.0.7337.0` | `libpdfium.so`, `libpdfiumandroid.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` | Apache-2.0 Android binding, PDFium BSD 3-Clause terms, and the complete 17-file upstream Android distribution license bundle |
| `cz.adaptech.tesseract4android:tesseract4android:4.9.0` | `Tesseract4Android` tag `4.9.0`, standard variant | `libtesseract.so`, `libleptonica.so`, `libjpeg.so`, and `libpngx.so` for the same four ABIs | Apache-2.0 wrapper/Tesseract, Leptonica BSD 2-Clause, IJG JPEG terms, and PNG Reference Library License |

Pdfium resolves Guava `33.5.0-android`. The app declares that same version directly because application code consumes WorkManager APIs whose public return type is Guava `ListenableFuture`; Pdfium publishes Guava as a runtime dependency, which would otherwise leave that public type absent from the compile classpath.

The application packages readable copies under `app/src/main/assets/third_party_licenses/`:

- `NOTICE.txt` identifies exact releases, artifact hashes, native contents, and attribution.
- `APACHE-2.0.txt` covers PdfiumAndroidKt, Tesseract4Android, Tesseract OCR, and Guava.
- `PDFIUM-BSD-3-CLAUSE.txt`, `LEPTONICA-BSD-2-CLAUSE.txt`, `IJG-JPEG.txt`, and `LIBPNG.txt` reproduce the directly identified native-component terms.
- `pdfium-140.0.7337.0/` reproduces all 17 license files shipped by the matching immutable PDFium Android distribution, including Abseil, AGG 2.3, Catapult, cpu_features, fast_float, FreeType, ICU, Little CMS, libjpeg-turbo/IJG, OpenJPEG, libpng, libtiff, libunwind, LLVM libc, PDFium, and zlib. The legacy single-byte FreeType copyright character is normalized to UTF-8 for readable Android packaging; the license wording is unchanged.

These files are runtime assets, so they are present in the APK without adding OCR `.traineddata` or document fixtures. The PDFium source match was verified by comparing every public header from PdfiumAndroidKt `v1.0.35` with PDFium `140.0.7337.0`; all match byte-for-byte except `fpdfview.h`, which matches the distribution's preserved `.orig` before its component-export macro rewrite. They cover the PDF/OCR dependency addition, not a complete legal inventory of every dependency already used by the application. A release review must keep this inventory aligned whenever an artifact version, variant, ABI set, or native component changes.

The immutable matching distribution is `bblanchon/pdfium-binaries` release `chromium/7337`; its `pdfium-android-arm64.tgz` digest is `33621b05dcec8cf074fdd53886cc0e574035c0cb67b110cf89d01851c3f5e87b`. The archive carries the license bundle packaged here.

## Repository and Integrity Boundary

- Pdfium is resolved from Maven Central.
- JitPack is configured through Gradle `exclusiveContent` and can resolve only `cz.adaptech.tesseract4android:tesseract4android`; every other module remains restricted to the existing Google and Maven Central repositories.
- `app/gradle.lockfile` pins the resolved module versions for all app build variants and test classpaths.
- `gradle/verification-metadata.xml` verifies SHA-256 for resolved artifacts and metadata. The reviewed AAR hashes are:
  - PdfiumAndroidKt `1.0.35`: `862ed337d6b52485fefba9ced9fe7fdb800d41fb300d8c8ebb03d8bea64d72f0`
  - Tesseract4Android `4.9.0`: `bce5d6413a1a5ae3d7240033fbbc851ba3217d0a08d9769400e17a077f42cb2a`

Dependency upgrades must update the exact version, lock state, verification hashes, native inventory, and packaged notices in one reviewed change. Do not regenerate verification metadata and accept changed hashes without comparing them to the intended immutable upstream release.

`:app:verifyDebugApkPdfOcrNotices` checks the built debug APK for every notice and license asset listed above. It complements `:app:verifyDebugApkNoBundledOcrData`, which rejects bundled `.traineddata` files.
