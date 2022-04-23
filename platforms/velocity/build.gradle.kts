plugins {
    `java-library`
    `maven-publish`
}

group = "us.ajg0702.queue.platforms.velocity"

repositories {
    //mavenLocal()
    maven { url = uri("https://repo.ajg0702.us") }
    maven { url = uri("https://nexus.velocitypowered.com/repository/maven-public/") }
    maven { url = uri("https://repo.viaversion.com/") }
    mavenCentral()
}

dependencies {
    compileOnly("net.kyori:adventure-api:4.9.3")
    compileOnly("com.google.guava:guava:30.1.1-jre")
    compileOnly("us.ajg0702:ajUtils:1.1.22")

    compileOnly("com.velocitypowered:velocity-api:3.0.0")
    annotationProcessor("com.velocitypowered:velocity-api:3.0.0")
    implementation("net.kyori:adventure-text-minimessage:4.10.0")

    compileOnly("com.viaversion:viaversion-api:4.2.0-SNAPSHOT")

    implementation("org.bstats:bstats-velocity:2.2.1")

    implementation(project(":common"))
    implementation(project(":api"))
}


tasks.withType<ProcessResources> {
    from(sourceSets.main.get().java.srcDirs)
    filter<org.apache.tools.ant.filters.ReplaceTokens>(
        "tokens" to mapOf(
            "VERSION" to project.version.toString()
        )
    ).into("$buildDir/src")
}

tasks.jar {
    exclude("**/*.java")
}

tasks.compileJava {
    source = tasks.getByName("processResources").outputs.files.asFileTree
}


publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifact(tasks["jar"])
        }
    }

    repositories {

        val mavenUrl = "https://repo.ajg0702.us/releases"

        if(!System.getenv("REPO_TOKEN").isNullOrEmpty()) {
            maven {
                url = uri(mavenUrl)
                name = "ajRepo"

                credentials {
                    username = "plugins"
                    password = System.getenv("REPO_TOKEN")
                }
            }
        }
    }
}