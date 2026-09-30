import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("com.squareup.wire")
}

wire {
  kotlin {
    javaInterop = true
  }

  sourcePath {
    srcDir("src/main/protowire")
  }
}

android {
  namespace = "dev.sam.wearsignal"
  compileSdk = 36
  // Needed to strip debug symbols from libsignal's native libs (~95MB → ~20MB per ABI).
  ndkVersion = "27.2.12479018"

  defaultConfig {
    applicationId = "dev.sam.wearsignal"
    minSdk = 30
    targetSdk = 35
    versionCode = 1
    versionName = "0.1"

    ndk {
      // armeabi-v7a: Pixel Watch (incl. Pixel Watch 4) runs a 32-bit ARM userspace.
      // x86_64: emulator. arm64-v8a: 64-bit watches.
      abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
    }

    // Production Signal service endpoints + keys, copied from Signal-Android v8.15.0 app/build.gradle.kts
    buildConfigField("String", "SIGNAL_URL", "\"https://chat.signal.org\"")
    buildConfigField("String", "STORAGE_URL", "\"https://storage.signal.org\"")
    buildConfigField("String", "SIGNAL_CDN_URL", "\"https://cdn.signal.org\"")
    buildConfigField("String", "SIGNAL_CDN2_URL", "\"https://cdn2.signal.org\"")
    buildConfigField("String", "SIGNAL_CDN3_URL", "\"https://cdn3.signal.org\"")
    buildConfigField("String", "SIGNAL_CDSI_URL", "\"https://cdsi.signal.org\"")
    buildConfigField("String", "SIGNAL_SVR2_URL", "\"https://svr2.signal.org\"")
    buildConfigField("String", "SIGNAL_AGENT", "\"OWA\"")
    buildConfigField("String[]", "UNIDENTIFIED_SENDER_TRUST_ROOTS", "new String[]{ \"BXu6QIKVz5MA8gstzfOgRQGqyLqOwNKHL6INkv3IHWMF\", \"BUkY0I+9+oPgDCn4+Ac6Iu813yvqkDr/ga8DzLxFxuk6\"}")
    buildConfigField("String", "ZKGROUP_SERVER_PUBLIC_PARAMS", "\"AMhf5ywVwITZMsff/eCyudZx9JDmkkkbV6PInzG4p8x3VqVJSFiMvnvlEKWuRob/1eaIetR31IYeAbm0NdOuHH8Qi+Rexi1wLlpzIo1gstHWBfZzy1+qHRV5A4TqPp15YzBPm0WSggW6PbSn+F4lf57VCnHF7p8SvzAA2ZZJPYJURt8X7bbg+H3i+PEjH9DXItNEqs2sNcug37xZQDLm7X36nOoGPs54XsEGzPdEV+itQNGUFEjY6X9Uv+Acuks7NpyGvCoKxGwgKgE5XyJ+nNKlyHHOLb6N1NuHyBrZrgtY/JYJHRooo5CEqYKBqdFnmbTVGEkCvJKxLnjwKWf+fEPoWeQFj5ObDjcKMZf2Jm2Ae69x+ikU5gBXsRmoF94GXTLfN0/vLt98KDPnxwAQL9j5V1jGOY8jQl6MLxEs56cwXN0dqCnImzVH3TZT1cJ8SW1BRX6qIVxEzjsSGx3yxF3suAilPMqGRp4ffyopjMD1JXiKR2RwLKzizUe5e8XyGOy9fplzhw3jVzTRyUZTRSZKkMLWcQ/gv0E4aONNqs4P+NameAZYOD12qRkxosQQP5uux6B2nRyZ7sAV54DgFyLiRcq1FvwKw2EPQdk4HDoePrO/RNUbyNddnM/mMgj4FW65xCoT1LmjrIjsv/Ggdlx46ueczhMgtBunx1/w8k8V+l8LVZ8gAT6wkU5J+DPQalQguMg12Jzug3q4TbdHiGCmD9EunCwOmsLuLJkz6EcSYXtrlDEnAM+hicw7iergYLLlMXpfTdGxJCWJmP4zqUFeTTmsmhsjGBt7NiEB/9pFFEB3pSbf4iiUukw63Eo8Aqnf4iwob6X1QviCWuc8t0LUlT9vALgh/f2DPVOOmR0RW6bgRvc7DSF20V/omg+YBw==\"")
    buildConfigField("String", "GENERIC_SERVER_PUBLIC_PARAMS", "\"AByD873dTilmOSG0TjKrvpeaKEsUmIO8Vx9BeMmftwUs9v7ikPwM8P3OHyT0+X3EUMZrSe9VUp26Wai51Q9I8mdk0hX/yo7CeFGJyzoOqn8e/i4Ygbn5HoAyXJx5eXfIbqpc0bIxzju4H/HOQeOpt6h742qii5u/cbwOhFZCsMIbElZTaeU+BWMBQiZHIGHT5IE0qCordQKZ5iPZom0HeFa8Yq0ShuEyAl0WINBiY6xE3H/9WnvzXBbMuuk//eRxXgzO8ieCeK8FwQNxbfXqZm6Ro1cMhCOF3u7xoX83QhpN\"")
    buildConfigField("String", "BACKUP_SERVER_PUBLIC_PARAMS", "\"AJwNSU55fsFCbgaxGRD11wO1juAs8Yr5GF8FPlGzzvdJJIKH5/4CC7ZJSOe3yL2vturVaRU2Cx0n751Vt8wkj1bozK3CBV1UokxV09GWf+hdVImLGjXGYLLhnI1J2TWEe7iWHyb553EEnRb5oxr9n3lUbNAJuRmFM7hrr0Al0F0wrDD4S8lo2mGaXe0MJCOM166F8oYRQqpFeEHfiLnxA1O8ZLh7vMdv4g9jI5phpRBTsJ5IjiJrWeP0zdIGHEssUeprDZ9OUJ14m0v61eYJMKsf59Bn+mAT2a7YfB+Don9O\"")
  }

  // Shared signing key so installs from any machine (and debug vs release) update in place
  // without wiping app data — a signature change forces uninstall + relink. Copy
  // keystore/wear-signal.keystore (gitignored) to each dev machine.
  val sharedKeystore = rootProject.file("keystore/wear-signal.keystore")
  if (sharedKeystore.exists()) {
    signingConfigs {
      create("shared") {
        storeFile = sharedKeystore
        storePassword = (project.findProperty("wearSignalKeystorePassword") as String?) ?: "wear-signal"
        keyAlias = "wear-signal"
        keyPassword = (project.findProperty("wearSignalKeystorePassword") as String?) ?: "wear-signal"
      }
    }
    buildTypes {
      debug {
        signingConfig = signingConfigs.getByName("shared")
      }
      release {
        signingConfig = signingConfigs.getByName("shared")
      }
    }
  }

  buildTypes {
    release {
      // Sideloaded straight onto the watch: Pixel Watch runs a 32-bit ARM userspace,
      // so one ABI suffices. Debug keeps all three (emulator etc.) via defaultConfig.
      ndk {
        abiFilters.clear()
        abiFilters += "armeabi-v7a"
      }
    }
  }

  lint {
    // False positive from a stale transitive fragment artifact: we have no fragments,
    // and ComponentActivity's ActivityResult handling doesn't have the flagged bug.
    disable += "InvalidFragmentVersionForActivityResult"
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
  }

  packaging {
    resources {
      excludes += setOf("META-INF/LICENSE", "META-INF/NOTICE", "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES")
      // Desktop JNI binaries bundled inside the libsignal-client jar; useless on Android.
      excludes += setOf("**/*.dylib", "**/*.dll")
    }
    jniLibs {
      // libsignal's test-only native library; never loaded at runtime.
      excludes += "**/libsignal_jni_testing.so"
    }
  }
}

kotlin {
  compilerOptions {
    jvmTarget = JvmTarget.fromTarget(libs.versions.kotlinJvmTarget.get())
  }
}

// Don't package the merged baseline profile (assets/dexopt/baseline.prof): Wear's ART
// reports run-from-apk for it, which Android Studio's release deployer treats as
// INSTALL_BASELINE_PROFILE_FAILED. The profile only warms up the first launches, and
// background dexopt on the charger achieves the same soon enough.
tasks.whenTaskAdded {
  if (name.contains("ArtProfile")) {
    enabled = false
  }
}

dependencies {
  coreLibraryDesugaring(libs.android.tools.desugar)

  implementation(project(":lib:libsignal-service"))
  implementation(project(":core:util-jvm"))
  implementation(project(":core:models-jvm"))
  implementation(project(":core:network"))

  implementation(libs.libsignal.android)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.play.services)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.wear.compose.material)
  implementation(libs.wear.compose.foundation)
  implementation(libs.wear.compose.navigation)
  implementation(libs.wear.input)
  implementation(libs.playservices.wearable)
  implementation(libs.zxing.core)
  implementation(libs.work.runtime)
  implementation(libs.wear.tiles)
  implementation(libs.wear.protolayout)
  implementation(libs.watchface.complications.datasource.ktx)
  implementation(libs.androidx.concurrent.futures)
}
