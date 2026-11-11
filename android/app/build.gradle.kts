import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    id("com.google.devtools.ksp")
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val moonshineManifest = rootProject.layout.projectDirectory.file("moonshine-model-manifest.json")
val moonshineModel = layout.projectDirectory.dir("src/main/assets/moonshine/small-streaming-en")
val moonshineGeneratedAssets = layout.buildDirectory.dir("generated/moonshine-assets")
val prepareMoonshineAssets = tasks.register("prepareMoonshineAssets") {
    inputs.file(moonshineManifest)
    inputs.files(fileTree(moonshineModel))
    outputs.file(moonshineGeneratedAssets.map { it.file("moonshine/manifest.json") })
    doLast {
        @Suppress("UNCHECKED_CAST")
        val manifest = JsonSlurper().parse(moonshineManifest.asFile) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val files = manifest["files"] as List<Map<String, Any>>
        for (entry in files) {
            val name = entry["name"] as String
            require(name.matches(Regex("[A-Za-z0-9_.-]+"))) { "Invalid Moonshine model filename" }
            val modelFile = moonshineModel.file(name).asFile
            check(modelFile.isFile && modelFile.length() == (entry["bytes"] as Number).toLong()) {
                "Moonshine model missing or incomplete: $name. Run android/scripts/fetch-moonshine-model.ps1."
            }
            val digest = MessageDigest.getInstance("SHA-256")
            modelFile.inputStream().buffered().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(actual == entry["sha256"]) {
                "Moonshine model checksum mismatch: $name. Run android/scripts/fetch-moonshine-model.ps1."
            }
        }
        val output = moonshineGeneratedAssets.get().file("moonshine/manifest.json").asFile
        output.parentFile.mkdirs()
        moonshineManifest.asFile.copyTo(output, overwrite = true)
    }
}

android {
    namespace = "com.visualassistant.app"
    compileSdk = 37

    signingConfigs {
        getByName("debug") {
            val pinnedKeystore = rootProject.file(".android-user-home/debug.keystore")
            if (pinnedKeystore.exists()) {
                storeFile = pinnedKeystore
            }
        }
    }

    defaultConfig {
        applicationId = "com.visualassistant.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    sourceSets.getByName("main").assets.srcDir(moonshineGeneratedAssets.get().asFile)
    // Our JNI library retains the upstream 0.1.6 API but releases input arrays
    // after native inference (upstream issue #232). The app's copy wins the merge.
    packaging.jniLibs.pickFirsts.add("**/libmoonshine-jni.so")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild").configure { dependsOn(prepareMoonshineAssets) }

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("ai.moonshine:moonshine-voice:0.1.6")
    implementation("com.github.gkonovalov.android-vad:webrtc:2.0.10")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    // Bundled Latin OCR model: available offline immediately after app install.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Compose's transitive Espresso 3.5.0 calls an API removed on Android 17.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
