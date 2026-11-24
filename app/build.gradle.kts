import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun loadStealthConfig(): Triple<String, Int, Int> {
    val defaultPackageName = "com.local.utility.ukz6v84k"
    val defaultMagicCode = 7417044      // 0x00712CD4 within 0x00530000..0x007EFFFF
    val defaultHeaderMagic = 1408004550 // 0x53EC71C6

    val localPropsFile = rootProject.file("local.properties")
    val props = Properties()
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { props.load(it) }
    }

    val validPkgRegex = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
    val configuredPkg = System.getenv("SYNAPSE_PACKAGE_NAME")?.trim().orEmpty()
        .ifBlank { props.getProperty("synapse.packageName")?.trim().orEmpty() }
    val pkgName = if (configuredPkg.isNotBlank() && validPkgRegex.matches(configuredPkg)) {
        configuredPkg
    } else {
        defaultPackageName
    }

    val configuredMagic = System.getenv("SYNAPSE_IPC_MAGIC_CODE")?.trim()?.toIntOrNull()
        ?: props.getProperty("synapse.ipcMagicCode")?.trim()?.toIntOrNull()
    val magicCode = if (configuredMagic != null && configuredMagic in 0x00530000..0x007EFFFF) {
        configuredMagic
    } else {
        defaultMagicCode
    }

    val configuredHeader = System.getenv("SYNAPSE_IPC_HEADER_MAGIC")?.trim()?.toIntOrNull()
        ?: props.getProperty("synapse.ipcHeaderMagic")?.trim()?.toIntOrNull()
    val headerMagic = if (configuredHeader != null && configuredHeader != 0) {
        configuredHeader
    } else {
        defaultHeaderMagic
    }

    return Triple(pkgName, magicCode, headerMagic)
}

val (stealthPackageName, stealthMagicCode, stealthHeaderMagic) = loadStealthConfig()

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
