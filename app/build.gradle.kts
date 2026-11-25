import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

// Release signing: a gitignored keystore.properties (storeFile, storePassword, keyAlias, keyPassword)
// at the project root. Without it, release builds are left unsigned and everything else still works.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
  if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

android {
  namespace = "com.animeshahilya.geminitts"
  compileSdk = 37
  defaultConfig {
    applicationId = "com.animeshahilya.geminitts"
    minSdk = 26
    targetSdk = 37
    versionCode = 1
    versionName = "1.0"
  }

  signingConfigs {
    if (keystorePropertiesFile.exists()) {
      create("release") {
        storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
        storePassword = keystoreProperties.getProperty("storePassword")
        keyAlias = keystoreProperties.getProperty("keyAlias")
        keyPassword = keystoreProperties.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (keystorePropertiesFile.exists()) signingConfig = signingConfigs.getByName("release")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
  }
  testOptions {
    // PcmProcessor logs through android.util.Log; let JVM tests ignore it.
    unitTests.isReturnDefaultValues = true
  }
  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  debugImplementation(libs.androidx.compose.ui.tooling)

  // Lists and downloads the on-device GenAI voices.
  implementation("com.google.mlkit:genai-text-to-speech:1.0.0-alpha1")

  testImplementation(libs.junit)
}
