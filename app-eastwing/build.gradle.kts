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
        versionCode = 1
        versionName = "0.1.0"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
}
