import java.util.Properties

plugins { id("com.android.application") }

val signing = Properties().apply {
    val path = rootProject.file("signing/release.properties")
    if (path.exists()) path.inputStream().use { load(it) }
}
android {
    namespace = "cn.local.accountmanager"
    compileSdk = 37
    defaultConfig {
        applicationId = "cn.local.accountmanager"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "cn.local.accountmanager.AcceptanceInstrumentation"
    }
    if (!signing.isEmpty) {
        signingConfigs.create("personal") {
            storeFile = rootProject.file("signing/release.jks")
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
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
            if (!signing.isEmpty) signingConfig = signingConfigs.getByName("personal")
        }
    }
    buildFeatures { buildConfig = true; resValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies { testImplementation("junit:junit:4.13.2") }

// The Windows Java launcher reads Gradle's @argfile in the native encoding. Stage
// non-ASCII runtime paths to the system temp directory so Chinese checkouts work.
tasks.withType<Test>().configureEach {
    doFirst {
        val tempRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile
        val staging = File(tempRoot, "avm-test-runtime/${rootProject.projectDir.absolutePath.hashCode()}/$name")
        classpath = files(files(testClassesDirs, classpath).files.filter { it.exists() }.mapIndexed { index, source ->
            if (System.getProperty("os.name").startsWith("Windows") && source.absolutePath.any { it.code > 127 }) {
                val target = File(staging, "$index-${source.name}").canonicalFile
                check(target.toPath().startsWith(tempRoot.toPath()) && target != tempRoot)
                if (source.isDirectory) {
                    project.sync { from(source); into(target) }
                } else {
                    target.parentFile.mkdirs(); source.copyTo(target, overwrite = true)
                }
                target
            } else source
        })
    }
}
