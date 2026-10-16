plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val libtorrent4jVersion = "2.1.0-39"
val torrentNativeTests = providers.gradleProperty("torrentNativeTests").orNull == "true"
val torrentHostJni = configurations.create("torrentHostJni") {
    isCanBeConsumed = false
    isTransitive = false
}
val extractTorrentNative = tasks.register<org.gradle.api.tasks.Sync>("extractTorrentNative") {
    from({ torrentHostJni.map { zipTree(it) } })
    include("**/libtorrent4j.so")
    eachFile { relativePath = org.gradle.api.file.RelativePath(true, name) }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("torrent-native"))
}

android {
    namespace = "app.localcache"
    compileSdk = 34

    defaultConfig {
        // Different package from the old WuPlay cache (app.localcache) so both can stay installed.
        applicationId = "app.localcache.release"
        minSdk = 24
        targetSdk = 34
        versionCode = 80
        versionName = "0.5.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "WUPLAY_MODE", "false")
        buildConfigField("String", "PREFS_FILE", "\"local_cache_release\"")
        buildConfigField("int", "DEFAULT_PORT", "7100")
        buildConfigField("String", "CLIENT_NAME", "\"Stremio\"")
        buildConfigField("String", "ADDON_ID", "\"org.localcache.release\"")

        // Android TV boxes are arm only. Skipping x86 keeps the torrent engine
        // from tripling the APK for architectures nobody installs this on.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            if (torrentNativeTests) {
                it.dependsOn(extractTorrentNative)
                // libtorrent4j calls System.loadLibrary; a native jar on classpath alone
                // does not extract/load the host binary.
                it.systemProperty("libtorrent4j.jni.path", layout.buildDirectory.file("torrent-native/libtorrent4j.so").get().asFile.absolutePath)
            }
            it.testLogging {
                events("passed", "skipped", "failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
            it.useJUnit {
                if (!torrentNativeTests) excludeCategories("app.localcache.torrent.NativeIntegration")
            }
        }
    }

    flavorDimensions += "audience"
    productFlavors {
        create("standard") {
            dimension = "audience"
            isDefault = true
            // Public: need at least 2 GB free for internal fallback.
            buildConfigField("boolean", "ALLOW_TINY_INTERNAL", "false")
            buildConfigField("long", "INTERNAL_MIN_FREE_BYTES", "${2L * 1024 * 1024 * 1024}L")
        }
        create("personal") {
            dimension = "audience"
            applicationIdSuffix = ".personal"
            resValue("string", "app_name", "Local Cache Personal")
            // Dev TV with little free space — skip the 2 GB gate.
            buildConfigField("boolean", "ALLOW_TINY_INTERNAL", "true")
            buildConfigField("long", "INTERNAL_MIN_FREE_BYTES", "0L")
            // Waydroid / emulators are x86_64. TV releases stay arm-only via defaultConfig.
            ndk {
                abiFilters += "x86_64"
            }
        }
        create("wuplay") {
            dimension = "audience"
            // Full id (not suffix) — sits beside Stremio Local Cache and old port-7000 cache.
            applicationId = "app.localcache.wuplay"
            resValue("string", "app_name", "Local Cache WuPlay")
            buildConfigField("boolean", "WUPLAY_MODE", "true")
            buildConfigField("String", "PREFS_FILE", "\"local_cache_wuplay\"")
            buildConfigField("int", "DEFAULT_PORT", "7001")
            buildConfigField("String", "CLIENT_NAME", "\"WuPlay\"")
            buildConfigField("String", "ADDON_ID", "\"org.localcache.wuplay\"")
            buildConfigField("boolean", "ALLOW_TINY_INTERNAL", "false")
            buildConfigField("long", "INTERNAL_MIN_FREE_BYTES", "${2L * 1024 * 1024 * 1024}L")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
            )
        }
        jniLibs {
            // libtorrent is ~30 MB of uncompressed .so. This app is sideloaded from a GitHub
            // release onto a TV, so download size matters far more than the extra copy the
            // installer leaves on internal storage.
            useLegacyPackaging = true
        }
    }
}

configurations.configureEach {
    if (torrentNativeTests && name.endsWith("UnitTestRuntimeClasspath")) {
        // Android's Bionic .so files must not shadow the Linux host JNI library.
        exclude(group = "org.libtorrent4j", module = "libtorrent4j-android-arm64")
        exclude(group = "org.libtorrent4j", module = "libtorrent4j-android-arm")
        exclude(group = "org.libtorrent4j", module = "libtorrent4j-android-x86_64")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    if (torrentNativeTests) {
        // Host JNI tests are opt-in and never packaged into the Android APK.
        add(torrentHostJni.name, "org.libtorrent4j:libtorrent4j-linux:$libtorrent4jVersion")
    }
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // BitTorrent fallback for magnet streams (libtorrent 2.x via JNI).
    implementation("org.libtorrent4j:libtorrent4j:$libtorrent4jVersion")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$libtorrent4jVersion")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$libtorrent4jVersion")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:$libtorrent4jVersion")
}
