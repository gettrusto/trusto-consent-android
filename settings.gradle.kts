pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "trusto-consent-android"
include(":consent")
// Aplicativo de teste do SDK. Fica fora do pacote publicado: o release só monta `:consent`.
include(":sample")
