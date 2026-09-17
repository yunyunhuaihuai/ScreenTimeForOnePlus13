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
        // libsu（root 数据源）发布在 JitPack
        maven("https://jitpack.io")
    }
}

rootProject.name = "ScreenTimeLog"
include(":app")
