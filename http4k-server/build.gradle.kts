plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.shadow)
    alias(libs.plugins.graalvm.native)
    application
}

group = "com.benchmark.mcp"
version = "1.0.0"

application {
    mainClass.set("com.benchmark.mcp.BenchmarkMcpServerKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(libs.http4k.bom))
    implementation(libs.http4k.core)
    implementation(libs.http4k.config)
    implementation(libs.http4k.mcp.sdk)
    implementation(libs.http4k.server.undertow)
    implementation(libs.http4k.format.moshi)
    implementation(libs.kotshi.api)
    implementation(libs.lettuce)
    implementation(kotlin("reflect"))

    ksp(libs.kotshi.compiler)
}

kotlin {
    jvmToolchain(21)
}

graalvmNative {
    binaries {
        named("main") {
            mainClass.set("com.benchmark.mcp.BenchmarkMcpServerKt")
            imageName.set("benchmark-mcp-http4k-server")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
