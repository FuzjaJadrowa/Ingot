plugins {
    base
}

group = providers.gradleProperty("mod_group_id").get()
version = providers.gradleProperty("mod_version").get()

allprojects {
    group = rootProject.group
    version = rootProject.version
}