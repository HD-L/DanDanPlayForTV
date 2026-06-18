import setup.applicationSetup

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
}

applicationSetup()

android {
    namespace = "com.xyoye.dandanplay"
    compileSdk = Versions.compileSdkVersion
    defaultConfig {
        applicationId = Versions.applicationId
        minSdk = Versions.minSdkVersion
        targetSdk = Versions.targetSdkVersion
        targetSdk = Versions.targetSdkVersion
        versionCode = Versions.versionCode
        versionName = Versions.versionName
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = Dependencies.Compose.compilerExtension
    }
}

kapt {
    arguments {
        arg("AROUTER_MODULE_NAME", project.name)
    }
}

dependencies {
    implementation(project(":common_component"))
    implementation(project(":player_component"))
    implementation(project(":anime_component"))
    implementation(project(":user_component"))
    implementation(project(":local_component"))
    implementation(project(":storage_component"))

    // Compose for TV（10 尺界面）
    implementation(platform(Dependencies.Compose.bom))
    implementation(Dependencies.Compose.ui)
    implementation(Dependencies.Compose.foundation)
    implementation(Dependencies.Compose.material3)
    implementation(Dependencies.Compose.material_icons)
    implementation(Dependencies.Compose.tv_material)
    implementation(Dependencies.Compose.tv_foundation)
    implementation(Dependencies.Compose.activity)
    implementation(Dependencies.Compose.viewmodel)
    implementation(Dependencies.Compose.navigation)
    implementation(Dependencies.Compose.runtime_livedata)
    implementation(Dependencies.Compose.ui_tooling_preview)
    implementation(Dependencies.Github.coil_compose)
    debugImplementation(Dependencies.Compose.ui_tooling)

    kapt(Dependencies.Alibaba.arouter_compiler)
}
