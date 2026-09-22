plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.signalprofessor.qhub.eastwing"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.signalprofessor.qhub.eastwing"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "0.8.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":platform-core"))
    implementation(project(":event-log"))
    implementation(project(":capabilities:navigation"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    testImplementation(libs.junit)
}
