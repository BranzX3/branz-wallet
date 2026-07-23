import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("java")
    id("com.gradleup.shadow") version "9.0.0-rc2"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "dev.branzx"
version = "1.0.0"
val pluginVersion = version.toString()

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "jitpack"
        url = uri("https://jitpack.io")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    // VaultAPI drags in an ancient Bukkit that would shadow paper-api on the
    // compile classpath, so it is taken interface-only.
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }

    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("com.mysql:mysql-connector-j:9.1.0")
    // Not relocated: the native-library loader resolves resources by the
    // original org.sqlite package path.
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.17.0")
    testImplementation("io.papermc.paper:paper-api:26.2.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Dnet.bytebuddy.experimental=true", "--enable-native-access=ALL-UNNAMED")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    relocate("com.zaxxer.hikari", "dev.branzx.wallet.libs.hikari")
    relocate("com.mysql", "dev.branzx.wallet.libs.mysql")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.runServer {
    minecraftVersion("26.2")
    jvmArgs("-Dcom.mojang.eula.agree=true", "--enable-native-access=ALL-UNNAMED")
}
