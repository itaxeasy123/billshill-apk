import java.util.Properties
import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  // Backend endpoints, from a gitignored .env, surfaced as BuildConfig fields.
  //
  // Android has no runtime .env — the file is read here at build time, so changing it
  // requires a rebuild rather than just a relaunch. Defaults keep the build working on a
  // machine that has no .env at all (a fresh clone, or CI), and the OTP base URL was
  // previously a hardcoded constant inside OtpAuthClient, which meant pointing the app at
  // a deployed backend meant editing Kotlin.
  val envProps = Properties().apply {
    val f = rootProject.file(".env")
    if (f.exists()) {
      f.inputStream().use { stream -> load(stream) }
    }
  }
  fun env(key: String, fallback: String): String =
    (System.getenv(key) ?: envProps.getProperty(key) ?: fallback).trim()

  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    // Read from .env at build time; see the envProps block below.
    //
    // The fallback is the DEPLOYED backend, never a local one. A localhost default builds
    // an APK that works on the machine that built it and nowhere else — 10.0.2.2 is the
    // emulator's alias for the host, so on a real phone every call fails with a connection
    // error indistinguishable from the server being down. Wrong-but-reachable beats
    // right-only-here: a developer who wants local overrides it in .env deliberately.
    buildConfigField("String", "APK_API_BASE_URL", "\"${env("APK_API_BASE_URL", "https://apk.itaxeasy.com/")}\"")
    buildConfigField("String", "OCR_API_BASE_URL", "\"${env("OCR_API_BASE_URL", "https://ocr.itaxeasy.com/api/")}\"")

    applicationId = "com.aistudio.mobileaccounting.vxkzp"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Room writes the schema JSON for every version here. Committing these files is
  // what makes migrations testable: MigrationTestHelper builds an old database from
  // the recorded schema, runs the migration, and asserts the result matches the new
  // one. Without them a migration can only be validated by shipping it.
  ksp { arg("room.schemaLocation", "$projectDir/schemas") }

  sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

  // Release signing credentials, read from a gitignored keystore.properties if one exists
  // and otherwise from the environment.
  //
  // The environment-only form meant a plain `./gradlew :app:assembleRelease` always failed
  // with "SigningConfig release is missing required property storePassword" — the vars had
  // to be re-exported for every single invocation, and forgetting looked like a build
  // break rather than a missing secret. The file keeps the secret out of git (.gitignore
  // line 23) while making the ordinary command work, and the env fallback is kept so CI
  // can still supply them from secrets without the file being present.
  val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) {
      f.inputStream().use { stream -> load(stream) }
    }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = keystoreProps.getProperty("storePassword") ?: System.getenv("STORE_PASSWORD")
      keyAlias = keystoreProps.getProperty("keyAlias") ?: "upload"
      keyPassword = keystoreProps.getProperty("keyPassword") ?: System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = true
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.work.runtime.ktx)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  implementation(libs.firebase.firestore)

  // Firebase Auth with Google Sign-In requires all of the following to be uncommented together.
  // If you are using Firebase Auth with other providers (e.g. Email/Password), you may only need
  // firebase-auth.
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  // implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  implementation("com.google.code.gson:gson:2.11.0")
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  androidTestImplementation(libs.androidx.room.testing)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
