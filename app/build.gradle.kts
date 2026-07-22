plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.baselineprofile)
}

data class AppVersion(val name: String, val code: Int)

fun readVersion(fileName: String, expectedName: Regex): AppVersion {
    val versionFile = rootProject.file(fileName)
    require(versionFile.isFile) { "Missing $fileName" }
    val values = versionFile.readLines().map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .associate { line ->
            val parts = line.split("=", limit = 2)
            require(parts.size == 2) { "Malformed line in $fileName: $line" }
            parts[0].trim() to parts[1].trim()
        }
    require(values.keys == setOf("versionName", "versionCode")) { "$fileName must contain only versionName and versionCode" }
    val name = requireNotNull(values["versionName"])
    require(expectedName.matches(name)) { "Malformed versionName in $fileName: $name" }
    val code = requireNotNull(values["versionCode"]).toIntOrNull()
    require(code != null && code > 0) { "versionCode in $fileName must be a positive integer" }
    return AppVersion(name, code)
}

val stableVersion = readVersion("release.txt", Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))
val devVersion = readVersion("dev.txt", Regex("[0-9]+\\.[0-9]+\\.[0-9]+-dev\\.[1-9][0-9]*"))
require(stableVersion.code != devVersion.code) { "Version codes must be unique across dev and stable" }

val publishingStoreFile = providers.gradleProperty("releaseStoreFile").orNull
val publishingStorePassword = providers.gradleProperty("releaseStorePassword").orNull
val publishingKeyAlias = providers.gradleProperty("releaseKeyAlias").orNull
val publishingKeyPassword = providers.gradleProperty("releaseKeyPassword").orNull
val hasPublishingSigning = listOf(publishingStoreFile, publishingStorePassword, publishingKeyAlias, publishingKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "com.nixplorer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nixplorer"
        minSdk = 26
        targetSdk = 36
        versionCode = stableVersion.code
        versionName = stableVersion.name
        manifestPlaceholders["appLabel"] = "@string/app_name"
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (hasPublishingSigning) {
            create("publishing") {
                storeFile = rootProject.file(requireNotNull(publishingStoreFile))
                storePassword = publishingStorePassword
                keyAlias = publishingKeyAlias
                keyPassword = publishingKeyPassword
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "NiXplorer Dev"
            buildConfigField("boolean", "INCLUDE_PRERELEASE_UPDATES", "true")
            if (hasPublishingSigning && providers.gradleProperty("usePublishingSigning").orNull == "true") {
                signingConfig = signingConfigs.getByName("publishing")
            }
        }
        release {
            buildConfigField("boolean", "INCLUDE_PRERELEASE_UPDATES", "false")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasPublishingSigning) signingConfig = signingConfigs.getByName("publishing")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true

        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
        apiVersion = "1.9"
    }

    baselineProfile {
        dexLayoutOptimization = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach { output ->
            output.versionName.set(devVersion.name)
            output.versionCode.set(devVersion.code)
        }
    }
}

dependencies {
    "baselineProfile"(project(":baselineprofile"))
    implementation(libs.androidx.profileinstaller)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Local/File-based dependencies
    implementation(files("libs/APKEditor.jar"))

    // AndroidX - Core & Lifecycle
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.ui.tooling.preview.android)

    // Other Jetpack & Android Libraries
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.palette.ktx)

    // Sora Code Editor
    implementation(libs.sora.editor)
    implementation(libs.sora.editor.language.java)
    implementation(libs.sora.editor.language.textmate)

    // Image Loading - Coil
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.video)
    implementation(libs.zoomable.image.coil3)
    implementation(libs.okio)

    // Third-Party UI/Compose Utilities
    implementation(libs.accompanist.systemuicontroller)
    implementation(libs.cascade.compose)
    implementation(libs.compose.swipebox)
    implementation(libs.grid)
    implementation(libs.lazycolumnscrollbar)
    implementation(libs.reorderable)
    implementation(libs.zoomable)

    // Third-Party General Utilities
    implementation(libs.apksig)
    implementation(libs.commons.net)
    implementation(libs.gson)
    implementation(libs.storage)
    implementation(libs.zip4j)
}
