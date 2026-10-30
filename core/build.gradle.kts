plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    compileOnly("org.bitcoindevkit:bdk-jvm:3.1.1")
    testImplementation("org.bitcoindevkit:bdk-jvm:3.1.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    compileOnly("org.json:json:20240303")
}
tasks.test { testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }; maxHeapSize = "1g" }
