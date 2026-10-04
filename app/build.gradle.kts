import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "org.arxiv.physics"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.arxiv.physics"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("updateTestVersionCode").orNull?.toInt() ?: 5
        versionName = providers.gradleProperty("updateTestVersionName").orNull ?: "0.5.0"
        testInstrumentationRunner = "org.arxiv.physics.SmokeInstrumentation"
    }
    buildFeatures { compose = true; buildConfig = true }
    val releaseProperties = Properties()
    val releaseFile = rootProject.file("signing.properties")
    if (releaseFile.exists()) releaseFile.inputStream().use { releaseProperties.load(it) }
    signingConfigs {
        create("distribution") {
            if (releaseFile.exists()) {
                storeFile = rootProject.file(releaseProperties.getProperty("storeFile"))
                storePassword = releaseProperties.getProperty("storePassword")
                keyAlias = releaseProperties.getProperty("keyAlias")
                keyPassword = releaseProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            if (releaseFile.exists()) signingConfig = signingConfigs.getByName("distribution")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
