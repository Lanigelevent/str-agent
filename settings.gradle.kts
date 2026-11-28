pluginManagement {
    repositories {
        maven("https://repo.maven.apache.org/maven2")
        maven("https://repo.maven.apache.org/maven2")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://repo.maven.apache.org/maven2")
        maven("https://repo.maven.apache.org/maven2")
        google()
        mavenCentral()
    }
}

rootProject.name = "str-agent"
include(":core", ":str-agent", ":data", ":bench", ":engine-duckdb", ":host")
