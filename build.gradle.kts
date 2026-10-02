plugins {
    java
    application
}

group = "com.retro.engine"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val lwjglVersion = "3.3.4"

val lwjglNatives = listOf(
    "natives-windows",
    "natives-linux",
    "natives-linux-arm64",
    "natives-macos",
    "natives-macos-arm64"
)

dependencies {
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))

    implementation("org.lwjgl", "lwjgl")
    implementation("org.lwjgl", "lwjgl-glfw")
    implementation("org.lwjgl", "lwjgl-vulkan")

    for (platformNative in lwjglNatives) {
        runtimeOnly("org.lwjgl", "lwjgl", classifier = platformNative)
        runtimeOnly("org.lwjgl", "lwjgl-glfw", classifier = platformNative)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

val isMac = System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }

application {
    mainClass.set("com.retro.engine.Main")
    
    val jvmArgsList = mutableListOf(
        "--enable-native-access=ALL-UNNAMED"
    )
    if (isMac) {
        jvmArgsList.add("-XstartOnFirstThread")
    }
    applicationDefaultJvmArgs = jvmArgsList
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
