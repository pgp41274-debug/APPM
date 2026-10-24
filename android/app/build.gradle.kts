plugins {
    id("com.android.application")
}

// PdfCraft's browser build, produced by scripts/build-web.sh (never committed).
val webAssets = rootProject.file("../build/web-assets")
val pdfcraftVersion = webAssets.resolve("app/pdfcraft/VERSION").takeIf { it.isFile }?.readText()?.trim() ?: "0.13.0"

// A fixed, non-secret key so every build installs as an update over the last one. Point
// PDFCRAFT_KEYSTORE (+ _PASSWORD, _ALIAS, _KEY_PASSWORD) at a private key to replace it.
fun setting(name: String): String? =
    (System.getenv(name) ?: project.findProperty(name) as String?)?.takeIf { it.isNotEmpty() }

android {
    namespace = "com.appm.pdfcraft"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.appm.pdfcraft"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = pdfcraftVersion
    }

    signingConfigs {
        create("sideload") {
            storeFile = file(setting("PDFCRAFT_KEYSTORE") ?: "../signing/sideload.jks")
            storePassword = setting("PDFCRAFT_KEYSTORE_PASSWORD") ?: "pdfcraft-sideload"
            keyAlias = setting("PDFCRAFT_KEY_ALIAS") ?: "pdfcraft"
            keyPassword = setting("PDFCRAFT_KEY_PASSWORD") ?: setting("PDFCRAFT_KEYSTORE_PASSWORD") ?: "pdfcraft-sideload"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(webAssets)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += "META-INF/*.version"
    }
}

dependencies {
    implementation("androidx.activity:activity:1.12.4")
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.webkit:webkit:1.15.0")
}

val checkWebAssets by tasks.registering {
    doLast {
        if (!webAssets.resolve("app/pdfcraft/index.html").isFile) {
            throw GradleException("build/web-assets/app/pdfcraft is missing: run scripts/build-web.sh first")
        }
    }
}
tasks.named("preBuild") { dependsOn(checkWebAssets) }
