plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gaby.questnitidez"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gaby.questnitidez"
        minSdk = 29
        targetSdk = 32
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    // Cliente ADB en Kotlin: la app se conecta al ADB inalambrico de las propias gafas
    implementation("dev.mobile:dadb:1.2.+")
}
