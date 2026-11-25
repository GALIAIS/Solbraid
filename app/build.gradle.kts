import org.gradle.process.ExecOperations
import com.android.build.api.artifact.SingleArtifact
import groovy.json.JsonSlurper
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** 把 runtime/ 平台脚本与校验过的 Claude Agent SDK 打包到 assets/galiais/runtime。 */
abstract class RuntimeAssetsTask : DefaultTask() {
    @get:InputFile abstract val generator: RegularFileProperty
    @get:InputFile abstract val helper: RegularFileProperty
    @get:InputDirectory abstract val runtime: DirectoryProperty
    @get:InputFile abstract val claudeSdk: RegularFileProperty
    @get:InputFile abstract val officialPlan: RegularFileProperty
    @get:InputFiles abstract val terminalArchives: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Inject abstract val operations: ExecOperations

    @TaskAction fun generate() {
        val nodeCmd = if (System.getProperty("os.name").lowercase().contains("win")) "node.exe" else "node"
        operations.exec {
            commandLine(nodeCmd, generator.get().asFile, runtime.get().asFile, claudeSdk.get().asFile, outputDirectory.get().asFile)
        }
    }
}

/** 将全量离线预置的 Ubuntu 24.04 根系统与官方原生 CLI 按 ABI 打包进分包 APK 的 jniLibs。 */
abstract class OfflineBundleTask : DefaultTask() {
    @get:InputFile abstract val generator: RegularFileProperty
    @get:InputFile abstract val helper: RegularFileProperty
    @get:InputFile abstract val manifest: RegularFileProperty
    @get:InputDirectory abstract val offlineSource: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Inject abstract val operations: ExecOperations

    @TaskAction fun generate() {
        val nodeCmd = if (System.getProperty("os.name").lowercase().contains("win")) "node.exe" else "node"
        operations.exec {
            commandLine(nodeCmd, generator.get().asFile, offlineSource.get().asFile, outputDirectory.get().asFile, manifest.get().asFile)
        }
    }
}

abstract class VerifyRuntimePackageTask : DefaultTask() {
    @get:InputFile abstract val verifier: RegularFileProperty
    @get:InputFile abstract val helper: RegularFileProperty
    @get:InputDirectory abstract val apkDirectory: DirectoryProperty
    @get:InputDirectory abstract val assetDirectory: DirectoryProperty
    @get:OutputFile abstract val report: RegularFileProperty
    @get:Inject abstract val operations: ExecOperations

    @TaskAction fun verify() {
        val nodeCmd = if (System.getProperty("os.name").lowercase().contains("win")) "node.exe" else "node"
        operations.exec {
            commandLine(nodeCmd, verifier.get().asFile, apkDirectory.get().asFile, assetDirectory.get().asFile, report.get().asFile)
        }
    }
}

val componentManifest = rootProject.layout.projectDirectory.file("runtime/components.json")
val componentConfig = JsonSlurper().parseText(providers.fileContents(componentManifest).asText.get()) as? Map<*, *>
    ?: error("Invalid runtime component manifest")
val sdkConfig = componentConfig["sdk"] as? Map<*, *> ?: error("Missing Claude SDK component")
val sdkArchive = sdkConfig["archive"] as? String ?: error("Missing Claude SDK archive")
require(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]*").matches(sdkArchive)) { "Invalid Claude SDK archive name" }
val planConfig = componentConfig["plan"] as? Map<*, *> ?: error("Missing official Plan component")
val planArchive = planConfig["archive"] as? String ?: error("Missing official Plan archive")
require(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]*").matches(planArchive)) { "Invalid official Plan archive name" }

val prepareRuntimeAssets by tasks.registering(RuntimeAssetsTask::class) {
    generator.set(rootProject.layout.projectDirectory.file("tools/runtime-assets.cjs"))
    helper.set(rootProject.layout.projectDirectory.file("tools/component-manifest.cjs"))
    runtime.set(rootProject.layout.projectDirectory.dir("runtime"))
    claudeSdk.set(rootProject.layout.projectDirectory.file("downloads/runtime/$sdkArchive"))
    officialPlan.set(rootProject.layout.projectDirectory.file("downloads/runtime/$planArchive"))
    terminalArchives.from((componentConfig["terminal"] as? List<*>)?.map { raw ->
        val archive = (raw as? Map<*, *>)?.get("archive") as? String ?: error("Missing terminal archive")
        require(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]*").matches(archive)) { "Invalid terminal archive" }
        rootProject.layout.projectDirectory.file("downloads/runtime/$archive")
    } ?: error("Missing terminal components"))
    outputDirectory.set(layout.buildDirectory.dir("generated/runtimeAssets"))
}

val prepareOfflineBundle by tasks.registering(OfflineBundleTask::class) {
    generator.set(rootProject.layout.projectDirectory.file("tools/offline-bundle.cjs"))
    helper.set(rootProject.layout.projectDirectory.file("tools/component-manifest.cjs"))
    manifest.set(componentManifest)
    offlineSource.set(rootProject.layout.projectDirectory.dir("downloads/offline"))
    outputDirectory.set(layout.buildDirectory.dir("generated/offlineJniLibs"))
}

android {
    namespace = "com.galiais.agent"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.galiais.agent"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libbin_*.so", "**/libpayload_*.so", "**/libproot*.so", "**/libtalloc.so", "**/libandroid-shmem.so")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareRuntimeAssets, RuntimeAssetsTask::outputDirectory)
    variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareOfflineBundle, OfflineBundleTask::outputDirectory)
    val variantTitle = variant.name.replaceFirstChar { it.uppercaseChar() }
    val verification = tasks.register<VerifyRuntimePackageTask>("verify${variantTitle}RuntimePackage") {
        verifier.set(rootProject.layout.projectDirectory.file("tools/verify-runtime-package.cjs"))
        helper.set(rootProject.layout.projectDirectory.file("tools/component-manifest.cjs"))
        apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
        assetDirectory.set(prepareRuntimeAssets.flatMap { it.outputDirectory })
        report.set(layout.buildDirectory.file("reports/runtime/${variant.name}.json"))
    }
    tasks.matching { it.name == "assemble$variantTitle" }.configureEach { finalizedBy(verification) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.commonmark:commonmark:0.33.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.33.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.33.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
