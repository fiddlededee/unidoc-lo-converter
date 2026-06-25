plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("ru.fiddlededee.unidoc.loconverter.cli.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
}

tasks.jar {
    archiveBaseName.set("unidoc-lo-converter-cli")
    archiveVersion.set(project.version.toString())
    manifest.attributes["Main-Class"] = "ru.fiddlededee.unidoc.loconverter.cli.MainKt"
    val dependencies = configurations
        .runtimeClasspath
        .get()
        .map(::zipTree)
    from(dependencies)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}