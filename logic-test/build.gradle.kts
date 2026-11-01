// Plain JVM project that compiles the Android-free logic package and runs its checks.
// Run with:  cd logic-test && gradle run
plugins {
    kotlin("jvm") version "2.0.21"
    application
}

sourceSets {
    main {
        kotlin.srcDir("../app/src/main/java/com/glassesalerts/logic")
        kotlin.srcDir("../logic-tests")
    }
}

application {
    mainClass.set("com.glassesalerts.logic.LogicTestsKt")
}
