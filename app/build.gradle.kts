plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "de.lesen.reader"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.lesen.reader"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // No instrumentation runner: there are no on-device tests, and adding
        // one would pull in libraries that want the network.
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")   // sideloaded by hand
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // dict.db.gz must reach the device byte-for-byte: a gzip stream that
        // aapt has "helpfully" recompressed cannot be gunzipped by GZIPInputStream.
        noCompress += listOf("gz", "db", "ttf")
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // One normalisation fixture, not two: NormalizerTest and the Python
            // test read the same file (spec 4.3).
            test.systemProperty(
                "lesen.normFixture",
                rootProject.file("tools/dictbuild/fixtures/norm_cases.tsv").absolutePath,
            )
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WebViewAssetLoader: serves the extracted EPUB over https://appassets...
    implementation(libs.androidx.webkit)

    testImplementation(libs.junit)
    // The lookup tests run against a real SQLite file in the shipped schema.
    testImplementation(libs.sqlite.jdbc)
}
