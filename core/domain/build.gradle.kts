plugins {
    alias(libs.plugins.athar.jvm.library)
}

dependencies {
    api(project(":core:common"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.collections.immutable)

    // WM028: BigDecimal fractional powers for XIRR (ch.obermuhlner:big-math, MIT).
    // implementation, not api: the solver primitive is an internal math detail.
    implementation(libs.big.math)
}
