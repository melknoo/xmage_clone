plugins {
    java
    application
}

group = "dev.magelite"

// Eine Versionsquelle fuer App, Installer, Engine und /api/health: desktop/package.json (scripts/release.ps1 erhoeht sie)
val appVersion: String = rootDir.resolve("../desktop/package.json").let { f ->
    if (!f.isFile) "dev" else Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(f.readText())?.groupValues?.get(1) ?: "dev"
}
version = appVersion

repositories { mavenCentral() }

// Forge-Engine: vendor/forge entsteht nur durch scripts/import-forge.ps1 (Commit aus vendor/forge/FORGE_COMMIT)
val forgeDir = rootDir.resolve("../vendor/forge")
val forgeCommit: String = forgeDir.resolve("FORGE_COMMIT").let { if (it.isFile) it.readText().trim() else "" }

dependencies {
    implementation(fileTree(forgeDir.resolve("lib")) { include("*.jar") })
    // Kamen frueher ueber die XMage-Jars: Logging (log4j-API in vielen Klassen, Javalin via slf4j) und jsoup (RichText)
    implementation("ch.qos.reload4j:reload4j:1.2.25")
    implementation("org.slf4j:slf4j-reload4j:2.0.17")
    implementation("org.jsoup:jsoup:1.21.2")

    implementation("io.javalin:javalin:6.4.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// slf4j-Provider ist reload4j; Forges slf4j-tinylog ist beim Import ausgeschlossen
configurations.all {
    exclude(group = "org.slf4j", module = "slf4j-simple")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(17)
}

// Fester Jar-Name (Dockerfile/Electron setzen ihn vor lib/* auf den Classpath, harte Regel 10)
tasks.jar {
    archiveFileName.set("magelite-engine.jar")
    manifest { attributes("Implementation-Version" to appVersion) }
}

// Version fuer Main.VERSION (auch bei `gradlew run` ohne Jar); forgeCommit prueft ForgeBoot gegen vendor/forge/manifest.json
tasks.processResources {
    inputs.property("appVersion", appVersion)
    inputs.property("forgeCommit", forgeCommit)
    filesMatching("magelite-version.properties") { expand("version" to appVersion, "forgeCommit" to forgeCommit) }
}

// Uebergang (Forge-Umbau): XMage-gebundener Code und alles, was davon abhaengt, bleibt aus dem Build, bis Kern
// (Phase 1) und Deck-Layer (Phase 2) portiert sind. Liste in forge-transition.excludes; Zeilen beim Portieren loeschen,
// am Ende die Datei samt diesem Block.
val transitionExcludes: List<String> = file("forge-transition.excludes").let { f ->
    if (!f.isFile) emptyList() else f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
}
sourceSets {
    main { java { exclude(transitionExcludes.filter { !it.startsWith("test:") }) } }
    test { java { exclude(transitionExcludes.filter { it.startsWith("test:") }.map { it.removePrefix("test:") }) } }
}

val runDir = layout.projectDirectory.dir("run")
val forgeHome = forgeDir.canonicalPath
val jvmArgsCommon = listOf("-Xmx3g", "-XX:+UseG1GC", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-Dmagelite.forge=$forgeHome")

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

// Forge booten (gleiche Pfade wie `run`) und Kennzahlen ausgeben: Karten, Editionen, Boot-Zeit, Heap
tasks.register<JavaExec>("forgeCheck") {
    group = "magelite"
    description = "Forge-Boot pruefen: Karten > 25k, Editionen > 500, Boot-Zeit, Heap"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.magelite.boot.ForgeBoot")
    jvmArgs = jvmArgsCommon
    workingDir = runDir.asFile
    args = listOf("--data=${runDir.asFile.absolutePath}", "--forge=$forgeHome")
    doFirst { runDir.asFile.mkdirs() }
}

tasks.test {
    useJUnitPlatform()
    workingDir = runDir.asFile
    jvmArgs = jvmArgsCommon
    doFirst { runDir.asFile.mkdirs() }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
