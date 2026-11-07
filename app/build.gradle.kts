import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.play.publisher)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
// Chemins relatifs au module app, comme dans les local.properties des autres projets.
val releaseStoreFile = file(localProperties.getProperty("RELEASE_STORE_FILE", "../release-keystore.jks"))
val releaseSigningReady = releaseStoreFile.exists()

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

android {
    namespace = "com.wobblebot.papotons"
    compileSdk {
        version = release(37)
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD", "")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS", "")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD", "")
            }
        }
    }

    defaultConfig {
        applicationId = "com.wobblebot.papotons"
        // Android 8 : les vieilles tablettes d'entrée de gamme des familles et des écoles
        // doivent pouvoir faire tourner l'appli (décision du 2026-11-06, docs/TODO.md).
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (releaseSigningReady) {
                signingConfigs.getByName("release")
            } else {
                // Garde `bundleRelease` vert sur un clone sans clé. Play refuse
                // un AAB qui n'est pas signé par la clé d'envoi : rien ne part.
                logger.warn("[Signature] ${releaseStoreFile.name} absent : release signée avec la clé debug.")
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        // La brique version lit BuildConfig.VERSION_NAME.
        buildConfig = true
    }
    lint {
        error += "MissingTranslation"
    }
    testOptions {
        // Pour android.util.Log, que les chemins d'échec testés appellent.
        unitTests.isReturnDefaultValues = true
    }
}

// Schéma de la base exporté et versionné : les migrations automatiques s'en servent,
// et sans lui une montée de version ne peut pas prouver qu'elle ne perd rien (invariant 4).
room {
    schemaDirectory("$projectDir/schemas")
}

// Les tests JVM lisent les pictos embarqués (chaque case doit avoir son image) : un picto
// retiré doit relancer les tests, pas les laisser « à jour ».
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/assets/pictograms")
}

// gradle-play-publisher : envoi sur la piste interne, puis promotion (brique
// deploiement). Une clé absente ne casse que l'envoi : bundleRelease reste vert.
play {
    track.set("internal")
    promoteTrack.set(providers.gradleProperty("promoteTrack"))
    defaultToAppBundles.set(true)
    // `-PupdatePriority=5` rend la release bloquante (brique mise-a-jour).
    updatePriority.set(providers.gradleProperty("updatePriority").map(String::toInt))
    serviceAccountCredentials.set(
        file(localProperties.getProperty("PLAY_PUBLISH_KEY", "../play-publish-key.json"))
    )
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.androidx.room.runtime)
    implementation(platform(libs.kotlinx.serialization.bom))
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.room.compiler)

    // Brique mise-a-jour
    implementation(libs.play.app.update.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
