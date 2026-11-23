import java.security.SecureRandom
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun loadOrGenerateStealthConfig(): Triple<String, Int, Int> {
    val localPropsFile = rootProject.file("local.properties")
    val props = Properties()
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { props.load(it) }
    }

    var changed = false
    val rng = SecureRandom()

    var pkgName = props.getProperty("synapse.packageName")?.trim().orEmpty()
    if (pkgName.isBlank()) {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val suffix = (1..8).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
        pkgName = "com.local.utility.$suffix"
        props.setProperty("synapse.packageName", pkgName)
        changed = true
    }

    var magicCode = props.getProperty("synapse.ipcMagicCode")?.trim()?.toIntOrNull()
    if (magicCode == null || magicCode !in 0x00530000..0x007EFFFF) {
        magicCode = 0x00530000 + rng.nextInt(0x007EFFFF - 0x00530000)
        props.setProperty("synapse.ipcMagicCode", magicCode.toString())
        changed = true
    }

    var headerMagic = props.getProperty("synapse.ipcHeaderMagic")?.trim()?.toIntOrNull()
    if (headerMagic == null || headerMagic == 0) {
        headerMagic = 0x53000000 or (rng.nextInt(0x00FFFFFF))
        props.setProperty("synapse.ipcHeaderMagic", headerMagic.toString())
        changed = true
    }

    if (changed) {
        localPropsFile.outputStream().use { props.store(it, "Synapse stealth build parameters (auto-generated)") }
    }
    return Triple(pkgName, magicCode, headerMagic)
}

val (stealthPackageName, stealthMagicCode, stealthHeaderMagic) = loadOrGenerateStealthConfig()

android {
    namespace = "com.synapse.app"
    compileSdk = 36

    defaultConfig {
        applicationId = stealthPackageName
        minSdk = 30
        targetSdk = 36
        versionCode = 310
        versionName = "3.1.0"

        buildConfigField("int", "IPC_MAGIC_CODE", stealthMagicCode.toString())
        buildConfigField("int", "IPC_HEADER_MAGIC", stealthHeaderMagic.toString())
        buildConfigField("String", "STEALTH_PACKAGE_NAME", "\"$stealthPackageName\"")
    }

    flavorDimensions += "xposed"
    productFlavors {
        create("modern") {
            dimension = "xposed"
            buildConfigField("boolean", "IS_MODERN_XPOSED", "true")
        }
        create("legacy") {
            dimension = "xposed"
            applicationIdSuffix = ".legacy"
            buildConfigField("boolean", "IS_MODERN_XPOSED", "false")
        }
    }

    signingConfigs {
        val ksPath = System.getenv("SYNAPSE_KEYSTORE_PATH")?.trim().orEmpty()
        if (ksPath.isNotEmpty() && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("SYNAPSE_KEYSTORE_PASS").orEmpty()
                keyAlias = System.getenv("SYNAPSE_KEY_ALIAS").orEmpty()
                keyPassword = System.getenv("SYNAPSE_KEY_PASS").orEmpty()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-protocol"))
    implementation(project(":kernel-engine"))
    implementation(project(":agent-runtime"))

    add("modernCompileOnly", libs.libxposed.api)
    add("modernImplementation", libs.libxposed.service)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
}
