import java.util.Properties
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.athar.android.application)
    alias(libs.plugins.athar.android.compose)
    alias(libs.plugins.athar.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Reads keystore.properties (gitignored) so the release build can be signed locally + in CI.
// In CI, `keystore.properties` is generated from secrets at workflow time (see
// .github/workflows/release.yml). Locally, copy `keystore.properties.template` and fill it in.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// `-Pathar.seed=true` enables the personal seeded build. When true, the
// gitignored `app/src/seeded/` source set is added so the bundled
// seed/*.csv assets ship inside the APK. The DataSeeder reads them on
// first launch (controlled by BuildConfig.SEED_ON_FIRST_LAUNCH).
val atharSeed = (providers.gradleProperty("athar.seed").orNull?.toBoolean() ?: false)

android {
    namespace = "com.athar"

    defaultConfig {
        applicationId = "com.athar"
        versionCode = 10
        versionName = providers.gradleProperty("athar.version").orNull ?: "0.1.0"
        buildConfigField("boolean", "SEED_ON_FIRST_LAUNCH", atharSeed.toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false  // alpha-phase: keep symbol names for debugging
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // If keystore.properties is present, sign with it; otherwise leave unsigned.
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("personalFullSms") {
            dimension = "distribution"
            applicationIdSuffix = ".personal"
            versionNameSuffix = "-personal"
        }
        create("storeSafe") {
            dimension = "distribution"
            // No id suffix — this is the public-facing app id.
        }
    }

    buildFeatures {
        buildConfig = true
    }

    if (atharSeed) {
        sourceSets.named("main") {
            assets.srcDir("src/seeded/assets")
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:design-system"))
    implementation(project(":feature:today"))
    implementation(project(":feature:trends"))
    implementation(project(":feature:plan"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:widgets"))

    implementation(project(":ml:categorizer"))
    // Parser is pure Kotlin and used by both flavors (storeSafe parses bank-app notifications
    // through the same template registry). sms-listener is sideload-only because it needs
    // RECEIVE_SMS; notification-listener is Play-Store-eligible.
    implementation(project(":ingestion:sms-parser"))
    "personalFullSmsImplementation"(project(":ingestion:sms-listener"))
    "storeSafeImplementation"(project(":ingestion:notification-listener"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler.androidx)

    implementation(libs.timber)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:testing"))
}
