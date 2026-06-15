// 規約は build-logic の precompiled script plugin に集約。
// ABI 互換チェック（BCV）はビルド全体への単一の関心事のためルートに適用する。
plugins {
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
}

apiValidation {
    ignoredProjects += listOf("integration-tests", "bom")
}
