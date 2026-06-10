buildscript {
  repositories {
    google()
    mavenCentral()
  }

  dependencies {
    classpath("com.squareup.wire:wire-gradle-plugin:6.4.0") {
      exclude(group = "com.squareup.wire", module = "wire-swift-generator")
      exclude(group = "com.squareup.wire", module = "wire-grpc-client")
      exclude(group = "com.squareup.wire", module = "wire-grpc-jvm")
      exclude(group = "com.squareup.wire", module = "wire-grpc-server-generator")
      exclude(group = "io.outfoxx", module = "swiftpoet")
    }
    // Custom wire schema handler (org.signal.wire.Factory) used by :lib:libsignal-service
    classpath(files("$rootDir/wire-handler/wire-handler-1.0.0.jar"))
  }
}

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.compose.compiler) apply false
}
