plugins {
    java
    application
}

group = "dev.magelite"
version = "0.1.0"

repositories { mavenCentral() }

val xmageLib = rootDir.resolve("../vendor/xmage/lib")

dependencies {
    // XMage-Jars unveraendert einbinden (nie neu packen: CardRepository prueft Manifest-Build-Time)
    implementation(fileTree(xmageLib) { include("*.jar") })

    implementation("io.javalin:javalin:6.4.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// slf4j kommt bereits aus den XMage-Jars (slf4j-api 2.0.17 + reload4j); Javalin bringt eigenes slf4j-api mit
configurations.all {
    exclude(group = "org.slf4j", module = "slf4j-simple")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(17)
}

val runDir = layout.projectDirectory.dir("run")
val vendorDir = rootDir.resolve("../vendor/xmage").canonicalPath
val jvmArgsCommon = listOf("-Xmx3g", "-XX:+UseG1GC", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-Dmagelite.vendor=$vendorDir")

application {
    mainClass.set("dev.magelite.Main")
    applicationDefaultJvmArgs = jvmArgsCommon
}

tasks.named<JavaExec>("run") {
    workingDir = runDir.asFile
    args = listOf("--data=${runDir.asFile.absolutePath}", "--port=7317", "--dev")
    doFirst { runDir.asFile.mkdirs() }
}

// Dev-Engine im Server-Modus (Cookie-Login, Konten); Owner-Code aus der Umgebung, sonst DEV-OWNER-CODE
tasks.register<JavaExec>("runServer") {
    group = "magelite"
    description = "Dev-Engine im Server-Modus auf Port 7317 (--server --dev)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.magelite.Main")
    jvmArgs = jvmArgsCommon
    workingDir = runDir.asFile
    args = listOf("--data=${runDir.asFile.absolutePath}", "--port=7317", "--server", "--dev")
    // Dev: getrennte Mitspieler schon nach 5 s "aufgeben lassen" duerfen (Produktion 60 s), fuer e2e-online
    jvmArgs = jvmArgsCommon + "-Dmagelite.kickAfterMs=5000"
    environment("MAGELITE_OWNER_CODE", System.getenv("MAGELITE_OWNER_CODE") ?: "DEV-OWNER-CODE")
    environment("MAGELITE_OWNER_NAME", System.getenv("MAGELITE_OWNER_NAME") ?: "Owner")
    doFirst { runDir.asFile.mkdirs() }
}

// P0a: 4 Bots spielen headless gegeneinander
tasks.register<JavaExec>("spike") {
    group = "magelite"
    description = "Headless-Spike: 4 Bots spielen Commander FFA"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.magelite.spike.BotSpike")
    jvmArgs = jvmArgsCommon
    workingDir = runDir.asFile
    standardInput = System.`in`
    args = (project.findProperty("spikeArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
    doFirst { runDir.asFile.mkdirs() }
}

// P0b: automatischer Test-Mensch gegen 3 Bots ueber die GameHost-API
tasks.register<JavaExec>("humanSpike") {
    group = "magelite"
    description = "Headless-Spike: Test-Mensch (Zufallsstrategie) vs 3 Bots"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.magelite.spike.HumanSpike")
    jvmArgs = jvmArgsCommon
    workingDir = runDir.asFile
    args = (project.findProperty("spikeArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
    doFirst { runDir.asFile.mkdirs() }
}

// Regressionstest: Bot bestimmt per Effekt (Odric & Co.) die Blocker eines anderen Spielers
tasks.register<JavaExec>("blockerSpike") {
    group = "magelite"
    description = "Headless-Spike: Odric-Bot greift an und bestimmt die Blocker (ChooseBlockersEffect)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.magelite.spike.BlockerSpike")
    jvmArgs = jvmArgsCommon
    workingDir = runDir.asFile
    args = (project.findProperty("spikeArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
    doFirst { runDir.asFile.mkdirs() }
}

// KI-Vergleich: zwei Bot-Varianten (je 2 Sitze) spielen gegeneinander, Ergebnis + CSV in run/arena
tasks.register<JavaExec>("botArena") {
    group = "magelite"
    description = "Bot-Arena: KI-Variante A vs B (Siege, Platzierungspunkte, Tempo)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.magelite.spike.BotArena")
    jvmArgs = jvmArgsCommon
    workingDir = runDir.asFile
    args = (project.findProperty("spikeArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
    doFirst { runDir.asFile.mkdirs() }
}

tasks.test {
    useJUnitPlatform()
    workingDir = runDir.asFile
    jvmArgs = jvmArgsCommon
    doFirst { runDir.asFile.mkdirs() }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
