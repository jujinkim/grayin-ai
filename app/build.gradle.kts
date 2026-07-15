import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("kotlin-parcelize")
}

android {
    namespace = "ai.grayin"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.grayin"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        aidl = true
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.sqlite:sqlite:2.6.2")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")
    implementation("com.google.guava:guava:33.5.0-android")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("io.legere:pdfiumandroid:1.0.35")
    implementation("net.zetetic:sqlcipher-android:4.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlin:kotlin-parcelize-runtime:2.3.21")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

tasks.register("verifyDebugApkNoBundledOcrData") {
    dependsOn("assembleDebug")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        check(apk.isFile) { "Debug APK was not produced." }
        val bundledOcrData = ZipFile(apk).use { archive ->
            archive.entries()
                .asSequence()
                .map { entry -> entry.name }
                .filter { name -> name.endsWith(".traineddata", ignoreCase = true) }
                .toList()
        }
        check(bundledOcrData.isEmpty()) {
            "OCR language data must not be bundled: ${bundledOcrData.joinToString()}"
        }
    }
}

tasks.register("verifyDebugApkPdfOcrNotices") {
    dependsOn("assembleDebug")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        check(apk.isFile) { "Debug APK was not produced." }
        val expectedNotices = setOf(
            "assets/third_party_licenses/NOTICE.txt",
            "assets/third_party_licenses/APACHE-2.0.txt",
            "assets/third_party_licenses/PDFIUM-BSD-3-CLAUSE.txt",
            "assets/third_party_licenses/LEPTONICA-BSD-2-CLAUSE.txt",
            "assets/third_party_licenses/IJG-JPEG.txt",
            "assets/third_party_licenses/LIBPNG.txt",
            "assets/third_party_licenses/pdfium-140.0.7337.0/abseil.txt",
            "assets/third_party_licenses/pdfium-140.0.7337.0/agg23.txt",
            "assets/third_party_licenses/pdfium-140.0.7337.0/catapult.txt",
            "assets/third_party_licenses/pdfium-140.0.7337.0/cpu_features.txt",
            "assets/third_party_licenses/pdfium-140.0.7337.0/fast_float.txt",
            "assets/third_party_licenses/pdfium-140.0.7337.0/freetype.txt",
            "assets/third_party_licenses/pdfium-140.0.7337.0/icu.txt",
            "assets/third_party_licenses/pdfium-140.0.7337.0/lcms.txt",
            "assets/third_party_licenses/pdfium-140.0.7337.0/libjpeg_turbo.ijg",
            "assets/third_party_licenses/pdfium-140.0.7337.0/libjpeg_turbo.md",
            "assets/third_party_licenses/pdfium-140.0.7337.0/libopenjpeg.txt",
            "assets/third_party_licenses/pdfium-140.0.7337.0/libpng.txt",
            "assets/third_party_licenses/pdfium-140.0.7337.0/libtiff.txt",
            "assets/third_party_licenses/pdfium-140.0.7337.0/libunwind.txt",
            "assets/third_party_licenses/pdfium-140.0.7337.0/llvm-libc.txt",
            "assets/third_party_licenses/pdfium-140.0.7337.0/pdfium.txt",
            "assets/third_party_licenses/pdfium-140.0.7337.0/zlib.txt",
        )
        val packagedEntries = ZipFile(apk).use { archive ->
            archive.entries().asSequence().map { entry -> entry.name }.toSet()
        }
        val missingNotices = expectedNotices - packagedEntries
        check(missingNotices.isEmpty()) {
            "PDF/OCR third-party notices must be packaged: ${missingNotices.joinToString()}"
        }
    }
}

tasks.register("verifyDebugApkDocumentBoundary") {
    dependsOn("assembleDebug")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        check(apk.isFile) { "Debug APK was not produced." }
        ZipFile(apk).use { archive ->
            val entries = archive.entries().asSequence().toList()
            val forbiddenDocuments = entries
                .map { entry -> entry.name }
                .filter { name -> name.endsWith(".pdf", ignoreCase = true) }
            check(forbiddenDocuments.isEmpty()) {
                "Production APK must not bundle PDF fixtures: ${forbiddenDocuments.joinToString()}"
            }

            val requiredNativeLibraries = setOf(
                "libpdfium.so",
                "libpdfiumandroid.so",
                "libtesseract.so",
                "libleptonica.so",
                "libjpeg.so",
                "libpngx.so",
            )
            val requiredAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            val packagedNames = entries.map { entry -> entry.name }.toSet()
            val missingLibraries = requiredAbis.flatMap { abi ->
                requiredNativeLibraries.mapNotNull { library ->
                    "lib/$abi/$library".takeUnless(packagedNames::contains)
                }
            }
            check(missingLibraries.isEmpty()) {
                "PDF/OCR native runtime is incomplete: ${missingLibraries.joinToString()}"
            }
        }
    }
}
