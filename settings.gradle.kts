include(":app")
include(":core-contracts", ":core-domain", ":core-testing")
rootProject.name = "NB4A"

// phase2: Macrobenchmark / Baseline Profile module, opt-in only (-PwanboxBenchmark=true) so the
// default, CI and release builds never depend on benchmark artifacts.
if (startParameter.projectProperties["wanboxBenchmark"] == "true") include(":benchmark")
