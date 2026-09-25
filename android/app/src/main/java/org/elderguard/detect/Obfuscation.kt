package org.elderguard.detect

/**
 * Component-name obfuscation check, ported from EGDA (engine/egda.py, ALGORITHM.md 4.2 F-OBF):
 * a token is "nonsense" when it is >= 5 letters and cannot be split into dictionary words (>= 3 letters) or pinyin
 * syllables; a component is nonsense with >= 2 nonsense tokens (class name + package path); the app is obfuscated
 * when >= 30% of its own (non-library) components are nonsense and it has >= 4 of them.
 * Android keeps component names readable in normal apps, so random-word names are a deliberate choice.
 */
class Obfuscation(
    private val vocab: Set<String>,
    private val syllables: Set<String>,
    private val libPrefixes: List<String>,
    private val suffixes: List<String>,
) {
    fun examples(componentNames: Collection<String>): List<String>? {
        val own = componentNames.filter { n -> libPrefixes.none { n.startsWith(it) } }
        if (own.size < 4) return null
        val weird = own.filter { nonsense(it) }
        return if (weird.size.toDouble() / own.size >= 0.3) weird.map { it.substringAfterLast('.') }.take(3) else null
    }

    fun nonsense(name: String): Boolean {
        val parts = name.split('.')
        var simple = parts.last()
        suffixes.firstOrNull { simple.endsWith(it) && simple.length > it.length }?.let { simple = simple.dropLast(it.length) }
        val tokens = Regex("[A-Z]?[a-z]+|[A-Z]+(?![a-z])").findAll(simple).map { it.value }.toList() +
            parts.dropLast(1).drop(1).filter { p -> p.all { it.isLetter() } }
        return tokens.count { it.length >= 5 && !segmentable(it.lowercase()) } >= 2
    }

    private fun segmentable(tok: String): Boolean {
        val ok = BooleanArray(tok.length + 1); ok[0] = true
        for (i in tok.indices) {
            if (!ok[i]) continue
            for (j in i + 1..tok.length) {
                val piece = tok.substring(i, j)
                if ((piece.length >= 3 && piece in vocab) || piece in syllables) ok[j] = true
            }
        }
        return ok[tok.length]
    }
}
