plugins {
    id("com.android.application")
}

android {
    namespace = "com.csabi.readsync"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.csabi.readsync"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val ksProps = mutableMapOf<String, String>()
rootProject.file("keystore.properties").takeIf { it.exists() }?.readLines()?.forEach { line ->
    val i = line.indexOf('=')
    if (i > 0) ksProps[line.substring(0, i).trim()] = line.substring(i + 1).trim()
}

android {
    signingConfigs {
        if (ksProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(ksProps["storeFile"]!!)
                storePassword = ksProps["storePassword"]!!
                keyAlias = ksProps["keyAlias"]!!
                keyPassword = ksProps["keyPassword"]!!
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
