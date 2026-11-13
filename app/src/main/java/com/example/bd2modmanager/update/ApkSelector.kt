package com.example.bd2modmanager.update

/**
 * Selects the matching APK asset for the device's supported ABIs without falling
 * back to universal or incompatible architectures (ported from BD2ToolsBox).
 */
object ApkSelector {

    fun tokensFor(abi: String): List<String> = when (abi.lowercase()) {
        "arm64-v8a", "arm64" -> listOf("arm64-v8a", "arm64")
        "armeabi-v7a", "armeabi" -> listOf("armeabi-v7a", "armeabi")
        "x86_64" -> listOf("x86_64")
        "x86" -> listOf("x86")
        else -> listOf(abi.lowercase()).filter { it.isNotBlank() }
    }

    private fun matches(name: String, token: String): Boolean {
        if (token.isBlank()) return false
        val re = Regex(
            "(?<![a-z0-9_])" + Regex.escape(token) + "(?![a-z0-9_])",
            RegexOption.IGNORE_CASE
        )
        return re.containsMatchIn(name)
    }

    fun select(
        assets: List<UpdateRepository.ApkAsset>,
        supportedAbis: List<String>
    ): UpdateRepository.ApkAsset? {
        for (abi in supportedAbis) {
            for (token in tokensFor(abi)) {
                assets.firstOrNull { matches(it.name, token) }?.let { return it }
            }
        }
        return null
    }
}
