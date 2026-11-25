import java.nio.charset.Charset
import java.security.KeyStore
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import java.util.UUID
import javax.inject.Inject
import org.gradle.api.file.FileSystemOperations

plugins { id("com.android.application") }

val signingFile = rootProject.file("signing/release.jks")
val signingPropertiesFile = rootProject.file("signing/release.properties")
val signing = try {
    Properties().apply { if (signingPropertiesFile.isFile) signingPropertiesFile.inputStream().use { load(it) } }
} catch (_: Exception) {
    // A damaged release configuration must not prevent developers from building the QA variant.
    Properties()
}
android {
    namespace = "cn.local.accountmanager"
    compileSdk = 37
    defaultConfig {
        applicationId = "cn.local.accountmanager"
        minSdk = 26
        targetSdk = 37
        versionCode = 11001
        versionName = "1.10.1"
        testInstrumentationRunner = "cn.local.accountmanager.AcceptanceInstrumentation"
    }
    signingConfigs.create("personal") {
        storeFile = signingFile
        storePassword = signing.getProperty("storePassword")
        keyAlias = signing.getProperty("keyAlias")
        keyPassword = signing.getProperty("keyPassword")
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            resValue("string", "app_name", "账号验证管家·测试")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("personal")
        }
    }
    buildFeatures { buildConfig = true; resValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val publishedSigner = rootProject.file("gradle/release-certificate.sha256")
val injectedSigningFile = providers.gradleProperty("android.injected.signing.store.file").orNull?.let { file(it) }
val injectedSigningAlias = providers.gradleProperty("android.injected.signing.key.alias").orNull
val injectedStorePassword = providers.gradleProperty("android.injected.signing.store.password").orNull
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Check that the release key entry has the pinned release certificate."
    doLast {
        val expected = publishedSigner.readText().trim()
        fun verifyCertificate(store: File, alias: String?, password: String?) {
            if (!store.isFile || alias.isNullOrBlank() || password.isNullOrEmpty()) {
                throw GradleException("Missing release signing configuration. Restore the current android/signing backup.")
            }
            val chars = password.toCharArray()
            val certificate = try {
                val keys = KeyStore.getInstance(store, chars)
                if (keys.isKeyEntry(alias)) keys.getCertificate(alias) else null
            } catch (_: Exception) {
                throw GradleException("Cannot read the release keystore. Check the current android/signing backup.")
            } finally {
                chars.fill('\u0000')
            }
            val fingerprint = certificate?.let { HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(it.encoded)) }
            if (!expected.equals(fingerprint, ignoreCase = true)) {
                throw GradleException("Release signing certificate does not match the published Android app.")
            }
        }
        if (signing.getProperty("keyPassword").isNullOrEmpty()) {
            throw GradleException("Missing release signing configuration. Restore the current android/signing backup.")
        }
        verifyCertificate(signingFile, signing.getProperty("keyAlias"), signing.getProperty("storePassword"))
        injectedSigningFile?.let { verifyCertificate(it, injectedSigningAlias, injectedStorePassword) }
    }
}
// Cover both APK and App Bundle packaging, including Gradle's unsigned fallback.
tasks.matching { it.name in setOf("validateSigningRelease", "packageRelease", "packageReleaseBundle", "signReleaseBundle") }
    .configureEach { dependsOn(verifyReleaseSigning) }

// Keep the app's runtime libraries independent of the build-time KGP upgrade.
kotlin { coreLibrariesVersion = "2.2.10" }

dependencies {
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.core:core:1.19.1")
    testImplementation("junit:junit:4.13.2")
}

@org.gradle.work.DisableCachingByDefault(because = "Copies local test inputs to Windows-launcher-compatible paths")
abstract class StageWindowsTestClasspath : DefaultTask() {
    @get:InputFiles @get:SkipWhenEmpty @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val sources: ConfigurableFileCollection
    @get:OutputDirectory abstract val destination: DirectoryProperty
    @get:Inject abstract val fileSystem: FileSystemOperations

    companion object {
        fun entryName(source: File): String {
            val id = UUID.nameUUIDFromBytes(source.absolutePath.toByteArray())
            return "$id-" + source.name.takeLast(64).replace(Regex("[^ -~]"), "_")
        }
    }

    @TaskAction fun stage() {
        val target = destination.get().asFile
        val path = target.toPath()
        check(path.toRealPath() == path.toAbsolutePath().normalize()) { "Refusing to sync a redirected test classpath directory: $target" }
        check(Charset.forName(System.getProperty("native.encoding")).newEncoder().canEncode(target.path)) {
            "Windows needs a test staging directory representable in its native encoding. Check TEMP and GRADLE_USER_HOME."
        }
        fileSystem.sync {
            into(target)
            for (source in sources.files.filter { it.exists() }) {
                val name = entryName(source)
                if (source.isDirectory) from(source) { into(name) }
                else from(source) { rename { name } }
            }
        }
    }
}

// Gradle writes the test worker's @argfile in the Windows native encoding.
// Only paths that the launcher cannot represent need a temporary ASCII name.
if (System.getProperty("os.name").startsWith("Windows")) {
    val encoding = System.getProperty("native.encoding")
    val temp = File(System.getProperty("java.io.tmpdir")).toPath().toRealPath().toFile()
    val tempRoot = if (Charset.forName(encoding).newEncoder().canEncode(temp.path)) temp else gradle.gradleUserHomeDir.toPath().toRealPath().toFile()
    val projectKey = UUID.nameUUIDFromBytes(projectDir.canonicalPath.toByteArray())
    androidComponents.onVariants { variant ->
        variant.hostTests.values.forEach { hostTest ->
            hostTest.withTestTaskProvider { test ->
                val original = objects.fileCollection()
                val stageDir = File(tempRoot, "avm-test-runtime/$projectKey/${test.name}")
                val stage = tasks.register<StageWindowsTestClasspath>("stage${test.name.replaceFirstChar { it.uppercase() }}Classpath") {
                    sources.from(original.filter { !Charset.forName(encoding).newEncoder().canEncode(it.absolutePath) })
                    destination.fileValue(stageDir)
                }
                test.configure {
                    original.from(testClassesDirs, classpath)
                    val mapped = original.elements.map { entries ->
                        val encoder = Charset.forName(encoding).newEncoder()
                        entries.map { entry ->
                            val source = entry.asFile
                            if (encoder.canEncode(source.absolutePath)) source
                            else File(stageDir, StageWindowsTestClasspath.entryName(source))
                        }
                    }
                    classpath = files(mapped).builtBy(stage, original)
                }
            }
        }
    }
}
