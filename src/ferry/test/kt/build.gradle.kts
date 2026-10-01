plugins { kotlin("jvm") version "2.0.21"; application }
repositories { mavenCentral() }
dependencies { implementation("org.json:json:20240303") }
kotlin { jvmToolchain(21) }
sourceSets["main"].kotlin.srcDir("../../android/app/src/main/java/dev/onyxbox/ferry/core")
application { mainClass.set("HarnessKt") }
