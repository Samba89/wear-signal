/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Vendored from Signal-Android v8.15.0; build file trimmed for standalone use.
 */

import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("java-library")
  id("org.jetbrains.kotlin.jvm")
  id("com.squareup.wire")
}

java {
  sourceCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
  targetCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
}

tasks.withType<KotlinCompile>().configureEach {
  compilerOptions {
    jvmTarget = JvmTarget.fromTarget(libs.versions.kotlinJvmTarget.get())
    suppressWarnings = true
  }
}

wire {
  kotlin {
    javaInterop = true
  }

  sourcePath {
    srcDir("src/main/protowire")
  }
}

val sourceSets = extensions.getByName("sourceSets") as SourceSetContainer
sourceSets.named("main") {
  output.dir(
    mapOf("builtBy" to tasks.named("compileKotlin")),
    "${layout.buildDirectory.get()}/classes/kotlin/main"
  )
}

dependencies {
  api(libs.jackson.core)
  api(libs.jackson.module.kotlin)
  api(libs.rxjava3.rxjava)
  api(libs.square.okio)

  implementation(libs.google.jsr305)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.core.jvm)
  implementation(libs.libsignal.client)

  implementation(project(":core:util-jvm"))
  implementation(project(":core:models-jvm"))
}
