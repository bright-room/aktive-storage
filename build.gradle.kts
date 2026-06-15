// 規約は build-logic の precompiled script plugin に集約。
// ABI 互換チェック（BCV）はビルド全体への単一の関心事のためルートに適用する。
plugins {
    alias(libs.plugins.binary.compatibility.validator)
}

apiValidation {
    ignoredProjects += listOf("integration-tests", "bom")
}
