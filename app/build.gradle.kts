plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.zeidex.convert"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zeidex.convert"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: project.findProperty("KEYSTORE_PATH") as? String
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD") ?: project.findProperty("KEYSTORE_PASSWORD") as? String ?: "zeidex_keystore_pass_2026"
            val keyAlias = System.getenv("KEY_ALIAS") ?: project.findProperty("KEY_ALIAS") as? String ?: "zeidex"
            val keyPassword = System.getenv("KEY_PASSWORD") ?: project.findProperty("KEY_PASSWORD") as? String ?: keystorePassword

            val candidateFile = if (!keystorePath.isNullOrBlank()) {
                val f = File(keystorePath)
                when {
                    f.isAbsolute && f.exists() -> f
                    file(keystorePath).exists() -> file(keystorePath)
                    rootProject.file(keystorePath).exists() -> rootProject.file(keystorePath)
                    else -> null
                }
            } else {
                val defaultInApp = file("keystore/release.keystore")
                val defaultInRoot = rootProject.file("keystore/release.keystore")
                when {
                    defaultInApp.exists() -> defaultInApp
                    defaultInRoot.exists() -> defaultInRoot
                    else -> null
                }
            }

            if (candidateFile != null) {
                storeFile = candidateFile
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            val relConfig = signingConfigs.getByName("release")
            if (relConfig.storeFile?.exists() == true) {
                signingConfig = relConfig
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)

    debugImplementation(libs.androidx.ui.tooling)
}
