plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val sdkVersion = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "io.gettrusto.consent"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Em runtime, só a plataforma e a stdlib do Kotlin (ADR 0135): nada que o app do cliente
    // precise conciliar com as próprias versões.
    testImplementation("junit:junit:4.13.2")
}

/**
 * Teto de tamanho do AAR de release, como o teto de 15 KB do widget: o SDK vai dentro do
 * aplicativo do cliente, e cada KB conta para quem baixa.
 */
val maxAarBytes = 100 * 1024L
val checkAarSize by tasks.registering {
    val aar = layout.buildDirectory.file("outputs/aar/consent-release.aar")
    inputs.file(aar)
    doLast {
        val size = aar.get().asFile.length()
        check(size <= maxAarBytes) { "AAR de release com $size bytes, acima do teto de $maxAarBytes" }
        println("AAR de release: $size bytes (teto $maxAarBytes)")
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(checkAarSize) }
