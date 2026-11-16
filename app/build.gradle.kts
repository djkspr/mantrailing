import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Clé d'envoi Play Store : keystore.properties (non versionné) à la racine, voir README.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

// Projet Firebase de la sauvegarde en ligne : app/google-services.json (console Firebase). Lu ici plutôt que par le
// plugin google-services ; sans ce fichier, la sauvegarde en ligne est absente de l'appli.
@Suppress("UNCHECKED_CAST")
val firebaseConfig: Map<String, String> = file("google-services.json").takeIf { it.exists() }?.let { f ->
    val root = groovy.json.JsonSlurper().parse(f) as Map<String, Any?>
    val project = root["project_info"] as Map<String, Any?>
    val client = (root["client"] as List<Map<String, Any?>>).first { c ->
        ((c["client_info"] as Map<String, Any?>)["android_client_info"] as Map<String, Any?>)["package_name"] == "app.mantrailing"
    }
    val webClient = (client["oauth_client"] as List<Map<String, Any?>>? ?: emptyList()).firstOrNull { it["client_type"] == 3 }
    if (webClient == null) {
        // Sans identifiant « Web », pas de connexion Google : la sauvegarde en ligne reste absente de l'appli.
        logger.warn("google-services.json : connexion Google pas encore activée dans Firebase (oauth_client vide) — sauvegarde en ligne désactivée.")
        return@let emptyMap<String, String>()
    }
    mapOf(
        "PROJECT_ID" to project["project_id"] as String,
        "APP_ID" to (client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"] as String,
        "API_KEY" to ((client["api_key"] as List<Map<String, Any?>>).first()["current_key"] as String),
        "WEB_CLIENT_ID" to webClient["client_id"] as String,
    )
} ?: emptyMap()

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
        listOf("PROJECT_ID", "APP_ID", "API_KEY", "WEB_CLIENT_ID").forEach { key ->
            buildConfigField("String", "FIREBASE_$key", "\"${firebaseConfig[key] ?: ""}\"")
        }
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
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // Sauvegarde en ligne : compte Google, base Firestore, synchronisation en tâche de fond.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.kotlinx.coroutines.play.services)
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
