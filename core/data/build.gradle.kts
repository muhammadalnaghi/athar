plugins {
    alias(libs.plugins.athar.android.library)
    alias(libs.plugins.athar.android.hilt)
    alias(libs.plugins.athar.android.room)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.athar.core.data"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDir("schemas")
        }
    }
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:common"))

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.coroutines)
    implementation(libs.timber)

    // SQLCipher: encryption-at-rest backed by Android Keystore-wrapped DB key (ADR-003).
    implementation(libs.sqlcipher.android)

    // ENH-009: the single vetted client for the one fixed-endpoint catalog GET
    // (cancellable call, call/connect/read timeouts, redirects disabled).
    implementation(libs.okhttp)

    // ENH-009 platform verification (parent preflight F01/F06): the REAL
    // android.util.JsonReader guard and the REAL AtomicFile behavior on-device —
    // fakes and no-op guard seams cannot prove platform behavior.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.truth)
}
