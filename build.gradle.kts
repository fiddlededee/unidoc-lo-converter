plugins {
    kotlin("jvm") version "2.2.20" apply false
}

group = "ru.fiddlededee.unidoc.loconverter"
version = "0.1.0"

subprojects {
    group = rootProject.group
    version = rootProject.version

    // 1. Явно применяем плагины, чтобы их расширения стали доступны для настройки
    apply(plugin = "java")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    // 2. Настраиваем Java (Toolchain)
    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    // 3. Настраиваем Kotlin (JVM Target)
    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

allprojects {
    repositories {
        mavenCentral()
    }
}