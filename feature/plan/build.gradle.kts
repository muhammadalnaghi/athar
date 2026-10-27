plugins {
    alias(libs.plugins.athar.android.feature)
}

android {
    namespace = "com.athar.feature.plan"
}

dependencies {
    // WM028-fast-goals: feature-owned versioned input codec for SavedFinancialPlan
    // documents (explicit JsonObject API only; no serialization compiler plugin).
    implementation(libs.kotlinx.serialization.json)
}
