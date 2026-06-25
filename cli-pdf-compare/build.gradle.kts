plugins {
    kotlin("jvm")
}

group = "ru.fiddlededee.unidoc.loconverter"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation("de.redsix:pdfcompare:1.2.3")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("unidoc-pdf-compare-cli")
    archiveVersion.set(project.version.toString())
    manifest.attributes["Main-Class"] = "ru.fiddlededee.unidoc.pdfcompare.MainKt"
    val dependencies = configurations
        .runtimeClasspath
        .get()
        .map(::zipTree)
    from(dependencies)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
