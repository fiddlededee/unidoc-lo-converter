plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
    signing
}

java {
    withJavadocJar()
    withSourcesJar()
}
publishing {
    repositories {
        maven {
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }

    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = "unidoc-lo-converter-core"
            from(components["java"])

            pom {
                name = "UniDoc LO Converter Core"
                description = "Core library for LibreOffice document conversion"
                url = "https://github.com/fiddlededee/unidoc-lo-converter"

                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }

                scm {
                    url = "https://github.com/fiddlededee/unidoc-lo-converter"
                    connection = "scm:git://github.com:fiddlededee/unidoc-lo-converter.git"
                    developerConnection = "scm:git://github.com:fiddlededee/unidoc-lo-converter.git"
                }

                developers {
                    developer {
                        id = "fiddlededee"
                        name = "Nikolaj Potashnikov"
                        email = "consulting@yandex.ru"
                    }
                }
            }
        }
    }
}

signing {
    sign(publishing.publications)
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