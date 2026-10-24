plugins { id("com.android.application"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "tv.dandan.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "tv.dandan.clean"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.5.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 调试时可用 -Pabi=arm64-v8a 只打单一架构，减小包体；不传则包含全部架构。
        (project.findProperty("abi") as String?)?.takeIf { it.isNotBlank() }?.let { ndk { abiFilters += it.split(",") } }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    lint { abortOnError = true }
    testOptions { unitTests { isIncludeAndroidResources = true; all { it.systemProperty("robolectric.graphicsMode", "NATIVE"); it.systemProperty("roborazzi.test.record", "true") } } }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Compose for TV：焦点、放大、边框与十英尺排版由 tv-material 提供
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("org.videolan.android:libvlc-all:3.7.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    implementation("commons-net:commons-net:3.11.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.github.albfernandez:juniversalchardet:2.5.0")
    implementation("com.github.houbb:opencc4j:1.14.0")
    implementation("com.google.re2j:re2j:1.8")
    // 迅雷下载引擎（闭源，arm64-v8a / armeabi-v7a），用于磁链边下边播；x86 设备上相关入口自动隐藏
    implementation(files("libs/thunder.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.77.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.77.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
