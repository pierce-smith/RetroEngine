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
    implementation("org.lwjgl", "lwjgl-assimp")

    for (platformNative in lwjglNatives) {
        runtimeOnly("org.lwjgl", "lwjgl", classifier = platformNative)
        runtimeOnly("org.lwjgl", "lwjgl-glfw", classifier = platformNative)
        runtimeOnly("org.lwjgl", "lwjgl-assimp", classifier = platformNative)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

val isMac = System.getProperty("os.name").lowercase().contains("mac")

application {
    mainClass.set("com.retro.engine.Main")
    val defaultArgs = mutableListOf("--enable-native-access=ALL-UNNAMED")
    if (isMac) {
        defaultArgs.add("-XstartOnFirstThread")
    }
    applicationDefaultJvmArgs = defaultArgs
}

tasks.named<JavaExec>("run") {
    if (isMac) {
        jvmArgs("-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
