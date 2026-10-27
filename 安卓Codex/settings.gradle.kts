pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "CodexAndroid"
include(":core", ":app")

include(":terminal-emulator", ":terminal-view")
project(":terminal-emulator").projectDir = file("第三方/termux-terminal/terminal-emulator")
project(":terminal-view").projectDir = file("第三方/termux-terminal/terminal-view")
