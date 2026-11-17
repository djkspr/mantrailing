import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Clé d'envoi Play Store : keystore.properties (non versionné) à la racine, voir README.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

// Sauvegarde en ligne : adresse de l'API (o2switch) et identifiant Google « Web », dans gradle.properties.
// Adresse vide : la sauvegarde en ligne est absente de l'appli.
val cloudApiUrl = providers.gradleProperty("mantrailing.apiUrl").getOrElse("").trim().trimEnd('/')
val googleWebClientId = providers.gradleProperty("mantrailing.googleWebClientId").getOrElse("").trim()

// Chaque envoi au Play Store exige un versionCode plus grand : on prend le nombre de commits.
val commitCount = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
    .standardOutput.asText.map { it.trim().toInt() }

android {
    namespace = "app.mantrailing"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.mantrailing"
        minSdk = 26
        targetSdk = 36
        versionCode = commitCount.getOrElse(1)
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CLOUD_API_URL", "\"$cloudApiUrl\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.maplibre)
    // Mises à jour proposées dans l'appli (Google Play).
    implementation(libs.play.app.update)
    implementation(libs.play.app.update.ktx)
    // Déclarée explicitement : les bibliothèques Google Play demandent fragment 1.0.0, trop ancien pour la fenêtre
    // de mise à jour lancée par registerForActivityResult (lint InvalidFragmentVersionForActivityResult).
    implementation(libs.androidx.fragment)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // Sauvegarde en ligne : compte Google, synchronisation en tâche de fond.
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    // Version récente imposée : celle tirée par ui-test plante sur Android 16 (InputManager.getInstance).
    androidTestImplementation(libs.androidx.test.espresso)
    debugImplementation(libs.compose.ui.test.manifest)
}
