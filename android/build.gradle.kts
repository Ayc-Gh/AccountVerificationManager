// AGP's bundled Kotlin Gradle plugin still uses Configuration.setVisible on Gradle 9.9.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.2" apply false
}
