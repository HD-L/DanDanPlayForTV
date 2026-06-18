object Dependencies {
    private object Versions {
        const val kotlin = "1.9.22"
        const val kotlin_coroutines = "1.7.3"
        const val arouter = "1.5.2"
        const val room = "2.6.1"
        const val retrofit = "2.9.0"
        const val moshi = "1.15.1"
        const val exoplayer = "2.18.1"
        const val lifecycle = "2.7.0"
        const val navigation = "2.3.0"

        // Compose for TV
        const val compose_bom = "2024.02.01"
        const val compose_compiler = "1.5.8" // 必须匹配 Kotlin 1.9.22
        const val activity_compose = "1.8.2"
        const val lifecycle_viewmodel_compose = "2.7.0"
        const val navigation_compose = "2.7.7"
        const val tv = "1.0.0"
        const val coil = "2.5.0"
    }

    object Alibaba {
        const val arouter_api = "com.alibaba:arouter-api:${Versions.arouter}"
        const val arouter_compiler = "com.alibaba:arouter-compiler:${Versions.arouter}"
        const val alicloud_feedback = "com.aliyun.ams:alicloud-android-feedback:3.4.0"
        const val alicloud_analysis = "com.aliyun.ams:alicloud-android-man:1.2.7"
        const val alicloud_update = "com.taobao.android:update-main:1.1.14-open"
    }

    object AndroidX {
        const val junit_ext = "androidx.test.ext:junit:1.1.4"
        const val espresso = "androidx.test.espresso:espresso-core:3.5.0"

        const val core = "androidx.core:core-ktx:1.12.0"
        const val lifecycle_viewmodel =
            "androidx.lifecycle:lifecycle-viewmodel-ktx:${Versions.lifecycle}"
        const val lifecycle_runtime =
            "androidx.lifecycle:lifecycle-runtime-ktx:${Versions.lifecycle}"

        const val activity_ktx = "androidx.activity:activity-ktx:1.8.2"

        const val appcompat = "androidx.appcompat:appcompat:1.6.1"
        const val recyclerview = "androidx.recyclerview:recyclerview:1.3.2"
        const val constraintlayout = "androidx.constraintlayout:constraintlayout:2.1.4"
        const val swiperefreshlayout = "androidx.swiperefreshlayout:swiperefreshlayout:1.1.0"
        const val multidex = "androidx.multidex:multidex:2.0.1"
        const val palette = "androidx.palette:palette:1.0.0"
        const val startup = "androidx.startup:startup-runtime:1.1.1"
        const val preference = "androidx.preference:preference:1.2.1"
        const val paging = "androidx.paging:paging-runtime-ktx:3.2.1"

        const val room_ktx = "androidx.room:room-ktx:${Versions.room}"
        const val room = "androidx.room:room-runtime:${Versions.room}"
        const val room_compiler = "androidx.room:room-compiler:${Versions.room}"
    }

    object Apache {
        const val commons_net = "commons-net:commons-net:3.9.0"
    }

    object Github {
        const val banner = "io.github.youth5201314:banner:2.2.2"
        const val coil = "io.coil-kt:coil:${Versions.coil}"
        const val coil_video = "io.coil-kt:coil-video:${Versions.coil}"
        const val coil_compose = "io.coil-kt:coil-compose:${Versions.coil}"

        //ftp
        const val nano_http = "org.nanohttpd:nanohttpd:2.3.1"
        //smb
        const val smbj = "com.hierynomus:smbj:0.10.0"
        const val dcerpc = "com.rapid7.client:dcerpc:0.10.0"

        //switch keyboard panel
        const val keyboard_panel = "com.github.albfernandez:juniversalchardet:2.4.0"
        const val jsoup = "org.jsoup:jsoup:1.15.3"
    }

    object Compose {
        // 用 BOM 统一管理 Compose 版本，引用时不写版本号
        const val bom = "androidx.compose:compose-bom:${Versions.compose_bom}"
        const val compilerExtension = Versions.compose_compiler

        const val ui = "androidx.compose.ui:ui"
        const val ui_tooling = "androidx.compose.ui:ui-tooling"
        const val ui_tooling_preview = "androidx.compose.ui:ui-tooling-preview"
        const val foundation = "androidx.compose.foundation:foundation"
        const val material3 = "androidx.compose.material3:material3"
        const val material_icons = "androidx.compose.material:material-icons-extended"
        const val runtime = "androidx.compose.runtime:runtime"
        const val runtime_livedata = "androidx.compose.runtime:runtime-livedata"

        const val activity = "androidx.activity:activity-compose:${Versions.activity_compose}"
        const val viewmodel =
            "androidx.lifecycle:lifecycle-viewmodel-compose:${Versions.lifecycle_viewmodel_compose}"
        const val navigation = "androidx.navigation:navigation-compose:${Versions.navigation_compose}"

        // Compose for TV（10 尺界面组件）
        const val tv_material = "androidx.tv:tv-material:${Versions.tv}"
        const val tv_foundation = "androidx.tv:tv-foundation:1.0.0-alpha11"
    }

    object Google {
        const val material = "com.google.android.material:material:1.11.0"

        const val exoplayer = "com.google.android.exoplayer:exoplayer:${Versions.exoplayer}"
        const val exoplayer_core =
            "com.google.android.exoplayer:exoplayer-core:${Versions.exoplayer}"
        const val exoplayer_dash =
            "com.google.android.exoplayer:exoplayer-dash:${Versions.exoplayer}"
        const val exoplayer_hls = "com.google.android.exoplayer:exoplayer-hls:${Versions.exoplayer}"
        const val exoplayer_smoothstraming =
            "com.google.android.exoplayer:exoplayer-smoothstreaming:${Versions.exoplayer}"
        const val exoplayer_rtmp =
            "com.google.android.exoplayer:extension-rtmp:${Versions.exoplayer}"
        const val exoplayer_rtsp =
            "com.google.android.exoplayer:exoplayer-rtsp:${Versions.exoplayer}"
    }
    object Huawei {
        const val scan = "com.huawei.hms:scan:1.3.1.300"
    }

    object Junit {
        const val junit = "junit:junit:4.13.2"
    }

    object Kotlin {
        const val lib = "org.jetbrains.kotlin:kotlin-stdlib:${Versions.kotlin}"
        const val stdlib_jdk7 = "org.jetbrains.kotlin:kotlin-stdlib-jdk7:${Versions.kotlin}"
        const val coroutines_core =
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.kotlin_coroutines}"
        const val coroutines_android =
            "org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.kotlin_coroutines}"
    }

    object Square {
        const val leakcanary = "com.squareup.leakcanary:leakcanary-android:2.10"
        const val retrofit = "com.squareup.retrofit2:retrofit:${Versions.retrofit}"
        const val retrofit_moshi = "com.squareup.retrofit2:converter-moshi:${Versions.retrofit}"
        const val moshi = "com.squareup.moshi:moshi:${Versions.moshi}"
        const val moshi_codegen = "com.squareup.moshi:moshi-kotlin-codegen:${Versions.moshi}"
    }

    object Tencent {
        const val mmkv = "com.tencent:mmkv-static:1.2.14"
        const val bugly = "com.tencent.bugly:crashreport:4.1.9"
    }

    object VLC {
        const val vlc = "org.videolan.android:libvlc-all:4.0.0-eap9"
    }
}