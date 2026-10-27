// 测试模块：只依赖 core 与 adapters，不依赖任何界面
dependencies {
    testImplementation(project(":core:domain"))
    testImplementation(project(":adapters:memory"))
}
