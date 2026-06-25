plugins {
    kotlin("jvm")
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    // Зависимости LibreOffice (api для транзитивности)
    api("org.libreoffice:juh:7.4.7")
    api("org.libreoffice:unoil:7.4.7")
    api("org.libreoffice:ridl:7.4.7")
    api("org.libreoffice:libreoffice:7.4.7")
    api("org.libreoffice:unoloader:7.4.7")
    api("org.libreoffice:jurt:7.4.7")

    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("ru.fiddlededee:unidoc-publisher:0.9.3")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.5.6")
}