// `:lint:detekt` — project-specific detekt rules. NOT an Android lint module
// (those use the AGP `Lint` framework and live elsewhere). This is a plain JVM
// module that plugs into detekt's `RuleSetProvider` SPI; the convention plugin
// `openptv.detekt` pulls it in via `detektPlugins(project(":lint:detekt"))`.
//
// Why a separate module: rule sources need detekt's `detekt-api` on their
// compile classpath, but no consumer module should — keeping this isolated
// avoids leaking detekt internals into production code.
plugins {
    id("openptv.jvm.library")
}

dependencies {
    compileOnly(libs.detekt.api)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
