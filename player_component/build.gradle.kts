import setup.moduleSetup

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
}

moduleSetup()

android {
    sourceSets {
        getByName("main") {
            jniLibs.srcDir("libs")
        }
    }
    namespace = "com.xyoye.player_component"

    // 原生 TV 播放器控制层（Compose 覆盖层）
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = Dependencies.Compose.compilerExtension
    }
}

kapt {
    arguments {
        arg("AROUTER_MODULE_NAME", name)
    }
}

dependencies {

    implementation(project(":common_component"))
    implementation(project(":repository:panel_switch"))
    implementation(project(":repository:danmaku"))
    implementation(project(":repository:video_cache"))

    implementation(Dependencies.Github.keyboard_panel)

    // TODO 暂时移除，编译出64位后再考虑重新添加
    //implementation "com.github.ctiao:ndkbitmap-armv7a:0.9.21"

    implementation(Dependencies.Google.exoplayer)
    implementation(Dependencies.Google.exoplayer_core)
    implementation(Dependencies.Google.exoplayer_dash)
    implementation(Dependencies.Google.exoplayer_hls)
    implementation(Dependencies.Google.exoplayer_smoothstraming)
    implementation(Dependencies.Google.exoplayer_rtmp)

    implementation(Dependencies.VLC.vlc)

    // Compose for TV：播放器控制覆盖层
    implementation(platform(Dependencies.Compose.bom))
    implementation(Dependencies.Compose.ui)
    implementation(Dependencies.Compose.foundation)
    implementation(Dependencies.Compose.material3)
    implementation(Dependencies.Compose.material_icons)
    implementation(Dependencies.Compose.activity)
    implementation(Dependencies.Compose.runtime_livedata)

    kapt(Dependencies.Alibaba.arouter_compiler)
}
