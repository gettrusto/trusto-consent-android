plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Sem as checagens de nulidade geradas em cada chamada: o teto de 100 KB do AAR. A
        // API pública confere a entrada ela mesma (chave, endereço, escolhas).
        freeCompilerArgs.addAll("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
}

dependencies {
    // Em runtime, só a plataforma e a stdlib do Kotlin: nada que o app do cliente
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

/**
 * Pacote para o Maven Central (`io.gettrusto:consent-android`), montado numa pasta local com
 * os checksums que o repositório central exige. Só plugins do próprio Gradle: o build não
 * ganha dependência. A assinatura e o envio ficam no job `release`, o único com a chave.
 */
val javadocJar by tasks.registering(Jar::class) {
    // O repositório central exige o javadoc. A documentação da API está no README.
    archiveClassifier.set("javadoc")
    from(rootProject.file("README.md"))
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.gettrusto"
            artifactId = "consent-android"
            version = sdkVersion
            afterEvaluate { from(components["release"]) }
            artifact(javadocJar)
            pom {
                name.set("Trusto Consent SDK for Android")
                description.set("Consentimento LGPD no aplicativo Android: banner publicado, decisão e prova gravada na Trusto.")
                url.set("https://github.com/gettrusto/trusto-consent-android")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        name.set("Trusto")
                        organization.set("Trusto")
                        organizationUrl.set("https://trusto.com.br")
                    }
                }
                scm {
                    url.set("https://github.com/gettrusto/trusto-consent-android")
                    connection.set("scm:git:https://github.com/gettrusto/trusto-consent-android.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "bundle"
            url = uri(layout.buildDirectory.dir("bundle"))
        }
    }
}
