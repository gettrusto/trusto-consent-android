import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Aplicativo de teste do SDK, só para rodar no emulador ou no aparelho. Nunca é publicado:
 * o lançamento monta só o `:consent`.
 *
 * A chave e o coletor vêm do `local.properties` (fora do git), para nenhuma chave entrar no
 * repositório:
 *
 *   trusto.sample.key=trusto_pk_test_...
 *   trusto.sample.endpoint=https://...   (padrão: o coletor de staging)
 *
 * Sem chave, o app usa uma chave falsa no formato certo, e o coletor responde 401.
 */
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun sampleProp(name: String, default: String): String =
    providers.gradleProperty(name).orNull ?: localProps.getProperty(name) ?: default

val stagingEndpoint = "https://trusto-staging-consent-collector-77mizweawa-ue.a.run.app"
val placeholderKey = "trusto_pk_test_" + "0".repeat(32)

android {
    namespace = "io.gettrusto.consent.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.gettrusto.consent.sample"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "TRUSTO_KEY", "\"${sampleProp("trusto.sample.key", placeholderKey)}\"")
        buildConfigField("String", "TRUSTO_ENDPOINT", "\"${sampleProp("trusto.sample.endpoint", stagingEndpoint)}\"")
        // Coletor falso na máquina de desenvolvimento (`sample/tools/mock-collector.py`), visto
        // do emulador em 10.0.2.2. O SDK só aceita http para esse endereço em app debuggable.
        buildConfigField("String", "LOCAL_ENDPOINT", "\"http://10.0.2.2:8787\"")
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
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":consent"))
}
