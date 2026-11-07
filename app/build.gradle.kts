import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val keystoreProps = Properties().apply {
  val f = rootProject.file("keystore.properties")
  if (f.exists()) f.inputStream().use { load(it) }
}

android {
  namespace = "com.goosepaware.biliaudio"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.goosepaware.biliaudio"
    minSdk = 26
    targetSdk = 36
    versionCode = 18
    versionName = "1.18"
    ndk {
      abiFilters += listOf("arm64-v8a")
    }
  }

  signingConfigs {
    if (keystoreProps.isNotEmpty()) {
      create("release") {
        storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
        storePassword = keystoreProps.getProperty("storePassword")
        keyAlias = keystoreProps.getProperty("keyAlias")
        keyPassword = keystoreProps.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    debug {
      if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
    }
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = true
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-extended")
  debugImplementation(libs.androidx.compose.ui.tooling)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // 网络
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

  // ffmpeg（进程内，含 TLS + lame + ALAC/FLAC/AAC）
  implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9")

  // 二维码
  implementation("com.google.zxing:core:3.5.4")

  // 音乐播放（本地无损）
  implementation("androidx.media3:media3-exoplayer:1.11.1")
  implementation("androidx.media3:media3-session:1.11.1")
  implementation("androidx.media3:media3-common:1.11.1")

  // 设置存储 / 加密会话
  implementation("androidx.datastore:datastore-preferences:1.1.7")
  implementation("androidx.security:security-crypto:1.1.0")
  implementation("androidx.documentfile:documentfile:1.1.0")
}
