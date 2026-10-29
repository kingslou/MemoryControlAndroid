plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.kingslou.memorycontrol.sample"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.github.kingslou.memorycontrol.sample"
        minSdk = 23
        targetSdk = 34
        versionCode = 3
        versionName = "1.1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
        languageVersion = "1.7"
        apiVersion = "1.7"
    }
}

dependencies {
    implementation(project(":"))
}
