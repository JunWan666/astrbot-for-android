import java.net.URI
import java.util.Base64
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Keep the fast base-image development build; complete builds overlay a verified offline image.
val bundledRuntime = providers.gradleProperty("bundledRuntime").orNull == "true"
// Machine-specific input paths stay outside Git; -P always overrides local.properties.
val localInputs = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
// Private release credentials are never stored in Gradle properties or command-line arguments.
val releaseSigningFile = rootProject.file(providers.gradleProperty("releaseSigningProperties").orNull
    ?: providers.environmentVariable("ASTRBOT_RELEASE_SIGNING_PROPERTIES").orNull
    ?: ".local/signing/release.properties")
val releaseSigning = Properties().apply {
    if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use { load(it) }
}
val runtimeAssets = (providers.gradleProperty("runtimeAssetsDir").orNull ?: localInputs.getProperty("runtimeAssetsDir"))?.let { file(it) }
    ?: file("../runtime/apk-assets")
val distributionAssets = (providers.gradleProperty("distributionAssetsDir").orNull ?: localInputs.getProperty("distributionAssetsDir"))?.let { file(it) }
    ?: file("../runtime/distribution-assets")
val bootstrapAssets = (providers.gradleProperty("bootstrapAssetsDir").orNull ?: localInputs.getProperty("bootstrapAssetsDir"))?.let { file(it) }
val nativeLibs = (providers.gradleProperty("nativeLibsDir").orNull ?: localInputs.getProperty("nativeLibsDir"))?.let { file(it) }
val channelBase = "https://raw.githubusercontent.com/JunWan666/astrbot-for-android/updates/stable"
val updateCatalogUrl = providers.gradleProperty("updateCatalogUrl").getOrElse("$channelBase/astrbot.json")
val napcatCatalogUrl = providers.gradleProperty("napcatCatalogUrl").getOrElse("$channelBase/napcat.json")
val managerCatalogUrl = providers.gradleProperty("managerCatalogUrl").getOrElse("$channelBase/manager.json")
require(napcatCatalogUrl.isEmpty() || URI(napcatCatalogUrl).let {
    it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null
}) { "napcatCatalogUrl must be an HTTPS URL without credentials or a fragment" }
require(managerCatalogUrl.isEmpty() || URI(managerCatalogUrl).let {
    it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null
}) { "managerCatalogUrl must be an HTTPS URL without credentials or a fragment" }
val appVersionCode = providers.gradleProperty("appVersionCode").getOrElse("10000").toInt()
val appVersionName = providers.gradleProperty("appVersionName").getOrElse("1.0.0")
require(appVersionCode > 0 && appVersionName.matches(Regex("[a-zA-Z0-9._+-]{1,100}"))) { "Invalid app release version" }
require(updateCatalogUrl.isEmpty() || URI(updateCatalogUrl).let {
    it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null
}) { "updateCatalogUrl must be an HTTPS URL without credentials or a fragment" }
val updatePublicKey = providers.gradleProperty("updatePublicKeyFile").getOrElse("../runtime/update-public-key.der").let {
    Base64.getEncoder().encodeToString(file(it).readBytes())
}.orEmpty()

android {
    namespace = "io.astrbot.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.astrbot.android" + providers.gradleProperty("verificationSuffix").getOrElse("")
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        manifestPlaceholders["appLabel"] = "@string/app_name"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "UPDATE_CATALOG_URL", "\"${updateCatalogUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "UPDATE_PUBLIC_KEY", "\"$updatePublicKey\"")
        buildConfigField("String", "NAPCAT_CATALOG_URL", "\"${napcatCatalogUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "MANAGER_CATALOG_URL", "\"${managerCatalogUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        ndk { abiFilters += if (bundledRuntime) listOf("arm64-v8a") else listOf("arm64-v8a", "x86_64") }
    }
    buildFeatures { compose = true; buildConfig = true }
    bundle.language.enableSplit = false
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    if (releaseSigning.isNotEmpty()) {
        val required = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        require(required.all { !releaseSigning.getProperty(it).isNullOrBlank() }) { "正式签名配置不完整" }
        val keyStore = releaseSigningFile.parentFile.resolve(releaseSigning.getProperty("storeFile"))
        require(keyStore.isFile) { "正式签名密钥不存在，请恢复原密钥和配置" }
        val identity = signingConfigs.create("managerRelease") {
            storeFile = keyStore
            storeType = "PKCS12"
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
        buildTypes.getByName("release").signingConfig = identity
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.merges += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/NOTICE")
    }
    androidResources {
        noCompress += "bin"
        // Asset source-set excludes are not honored by AGP's asset merger.
        // Complete builds target ARM64 and must not carry the x86 development image.
        if (bundledRuntime) ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:x86_64"
    }
    flavorDimensions += "runtime"
    productFlavors {
        create("bootstrap") {
            dimension = "runtime"; buildConfigField("boolean", "BUNDLED_RUNTIME", "false")
            manifestPlaceholders["appLabel"] = if (providers.gradleProperty("verificationSuffix").orNull == ".filecheck")
                "AstrBot 文件验证包" else if (providers.gradleProperty("verificationSuffix").orNull == ".uicheck")
                "AstrBot 界面验证包" else "AstrBot 基础验证包"
        }
        create("bundled") {
            dimension = "runtime"; buildConfigField("boolean", "BUNDLED_RUNTIME", "true")
            if (providers.gradleProperty("verificationSuffix").orNull == ".uicheck") {
                manifestPlaceholders["appLabel"] = "AstrBot 界面验证包"
            }
        }
    }
    sourceSets.getByName("bundled").assets.srcDir(runtimeAssets)
    sourceSets.getByName("bundled").assets.srcDir(distributionAssets)
    bootstrapAssets?.let { sourceSets.getByName("main").assets.srcDir(it) }
    nativeLibs?.let { sourceSets.getByName("main").jniLibs.setSrcDirs(listOf(it)) }
    // An explicit development build may trust a local HTTPS fixture. Release never includes it.
    providers.gradleProperty("onlineVerificationResources").orNull?.let {
        sourceSets.getByName("debug").res.srcDir(file(it))
    }
    providers.gradleProperty("managerVerificationAssets").orNull?.let {
        sourceSets.getByName("androidTest").assets.srcDir(file(it))
    }
}

tasks.matching { it.name.startsWith("mergeBundled") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(configurations.getByName("bundledReleaseRuntimeClasspath").buildDependencies)
    doFirst {
        check(runtimeAssets.resolve("bootstrap/arm64-v8a/manifest.json").isFile) {
            "Prepare a verified complete runtime as described in docs/构建指南.md before building bundled APKs"
        }
        val noticeIndex = distributionAssets.resolve("third-party/manifest.json")
        val notices = distributionAssets.resolve("third-party/notices.zip")
        check(noticeIndex.isFile && notices.isFile) {
            "Run :app:collectReleaseDependencyMetadata then tools/build_support/collect_distribution.py --apk-assets runtime/distribution-assets"
        }
        val parser = groovy.json.JsonSlurper()
        val index = parser.parse(noticeIndex) as Map<*, *>
        val runtime = parser.parse(runtimeAssets.resolve("bootstrap/arm64-v8a/manifest.json")) as Map<*, *>
        check(index["runtimeSha256"] == runtime["sha256"]) { "Third-party notices refer to a different runtime image" }
        val hash = MessageDigest.getInstance("SHA-256").digest(notices.readBytes()).joinToString("") { "%02x".format(it) }
        check(index["noticesSha256"] == hash) { "Third-party notice archive digest mismatch" }
        val resolved = configurations.getByName("bundledReleaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .map { artifact ->
                val sha = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes()).joinToString("") { "%02x".format(it) }
                "${artifact.moduleVersion.id}:${artifact.extension}:$sha"
            }.toSet()
        check((index["androidArtifacts"] as List<*>).toSet() == resolved) {
            "Release dependencies changed; regenerate third-party notices"
        }
    }
}

// Build-time inventory only; never inspect a device or include local credentials.
tasks.register("collectReleaseDependencyMetadata") {
    val destination = layout.buildDirectory.dir("distribution/android-dependencies")
    outputs.dir(destination)
    outputs.upToDateWhen { false }
    dependsOn(configurations.getByName("bundledReleaseRuntimeClasspath").buildDependencies)
    doLast {
        val target = destination.get().asFile.apply { mkdirs() }
        val artifacts = configurations.getByName("bundledReleaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
        val records = artifacts.sortedBy { it.moduleVersion.id.toString() + it.name }.map { artifact ->
            val id = artifact.moduleVersion.id
            val hash = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes())
                .joinToString("") { "%02x".format(it) }
            listOf(id.group, id.name, id.version, artifact.extension, hash, artifact.file.absolutePath).joinToString("\t")
        }.distinct()
        target.resolve("artifacts.tsv").writeText(records.joinToString("\n", postfix = "\n"))
        val ids = configurations.getByName("bundledReleaseRuntimeClasspath").incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }.toSet()
        val query = dependencies.createArtifactResolutionQuery().forComponents(ids)
            .withArtifacts(org.gradle.jvm.JvmLibrary::class.java, org.gradle.language.base.artifact.SourcesArtifact::class.java)
            .execute()
        val sources = query.resolvedComponents.flatMap { component ->
            component.getArtifacts(org.gradle.language.base.artifact.SourcesArtifact::class.java).map { artifact ->
                val resolved = artifact as? org.gradle.api.artifacts.result.ResolvedArtifactResult
                listOf(component.id.displayName, resolved?.file?.absolutePath.orEmpty(), if (resolved == null) "unavailable" else "resolved").joinToString("\t")
            }
        }.sorted()
        target.resolve("sources.tsv").writeText(sources.joinToString("\n", postfix = "\n"))
        val pomQuery = dependencies.createArtifactResolutionQuery().forComponents(ids)
            .withArtifacts(org.gradle.maven.MavenModule::class.java, org.gradle.maven.MavenPomArtifact::class.java)
            .execute()
        val poms = pomQuery.resolvedComponents.flatMap { component ->
            component.getArtifacts(org.gradle.maven.MavenPomArtifact::class.java).map { artifact ->
                val resolved = artifact as? org.gradle.api.artifacts.result.ResolvedArtifactResult
                listOf(component.id.displayName, resolved?.file?.absolutePath.orEmpty(), if (resolved == null) "unavailable" else "resolved").joinToString("\t")
            }
        }.sorted()
        target.resolve("poms.tsv").writeText(poms.joinToString("\n", postfix = "\n"))
        println("Recorded ${records.size} release artifacts, ${sources.size} source results and ${poms.size} POM results in $target")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.apache.sshd:sshd-core:2.20.0")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation("com.google.zxing:core:3.5.3")
}
