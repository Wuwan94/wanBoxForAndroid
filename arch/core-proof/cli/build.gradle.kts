plugins { application }
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":adapters:memory"))
}
application { mainClass.set("io.wanbox.cli.MainKt") }
