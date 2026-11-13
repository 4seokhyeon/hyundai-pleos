import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}


// Development-only key: local.properties stays untracked, but debug APK contains this value.
val localSettings = Properties().apply {
    val source = rootProject.file("local.properties")
    if (source.isFile) source.inputStream().use { load(it) }
}
val youtubeDevelopmentKey = localSettings.getProperty("YOUTUBE_API_KEY", "").trim()
require(youtubeDevelopmentKey.isEmpty() || youtubeDevelopmentKey.matches(Regex("[A-Za-z0-9_-]+"))) {
    "YOUTUBE_API_KEY must contain only the unquoted API key; check local.properties"
}


// Debug-only public-data credentials. Never log values or include them in release builds.
fun quotedPublicDataKey(propertyName: String): String {
    val value = localSettings.getProperty(propertyName, "").trim()
    require(value.none { it.isISOControl() }) { "$propertyName must be a single-line key" }
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
val ggDataDevelopmentKey = quotedPublicDataKey("GG_DATA_API_KEY")
val publicDataDevelopmentKey = quotedPublicDataKey("PUBLIC_DATA_API_KEY")

// Debug defaults to headless SOS; -PgleoSosOverlayProbe=false restores legacy debug behavior.
// Release keeps the main schema and has no overlay service.
val gleoSosOverlayProbe = providers.gradleProperty("gleoSosOverlayProbe").map { it == "true" }.orElse(true)

android {
    if (gleoSosOverlayProbe.get()) {
        sourceSets.getByName("debug").assets.srcDir("src/gleoSosOverlayProbe/assets")
        sourceSets.getByName("debug").manifest.srcFile("src/gleoSosOverlayProbe/AndroidManifest.xml")
    }
    useLibrary("android.car")
    namespace = "com.shsh.pleos.playground"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.shsh.pleos.playground"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "YOUTUBE_API_KEY", "\"\"")
        buildConfigField("String", "GG_DATA_API_KEY", "\"\"")
        buildConfigField("String", "PUBLIC_DATA_API_KEY", "\"\"")
        buildConfigField("boolean", "SOS_OVERLAY_ENABLED", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            manifestPlaceholders["gleoSosOverlayProbeEnabled"] = gleoSosOverlayProbe.get().toString()
            buildConfigField("boolean", "SOS_OVERLAY_ENABLED", gleoSosOverlayProbe.get().toString())
            buildConfigField("String", "YOUTUBE_API_KEY", "\"$youtubeDevelopmentKey\"")
            buildConfigField("String", "GG_DATA_API_KEY", ggDataDevelopmentKey)
            buildConfigField("String", "PUBLIC_DATA_API_KEY", publicDataDevelopmentKey)
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)
    implementation(libs.pleos.fusedlocation)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}