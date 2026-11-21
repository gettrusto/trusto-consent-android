package io.gettrusto.consent

/**
 * Regras que o coletor confere de novo (`checkDecision` e `appRecordRequestSchema` do
 * contrato): o SDK monta só pedido que o coletor aceita, para nenhuma decisão ser recusada
 * depois de já valer no aparelho.
 */
internal object Rules {
    val KEY_PATTERN = Regex("^trusto_pk_(test|live)_[A-Za-z0-9_-]{32}$")
    private val ALLOWED_TEXT = Regex("[^\\p{L}\\p{N} ._()\\-/,+]")
    private val SPACES = Regex(" {2,}")
    const val MAX_OS = 40
    const val MAX_DEVICE = 60

    fun isValidKey(key: String): Boolean = KEY_PATTERN.matches(key)

    /** O endereço do coletor: https, ou http só para o próprio aparelho (testes e desenvolvimento local). */
    fun normalizeEndpoint(raw: String): String {
        val url = try {
            java.net.URI(raw.trim())
        } catch (e: Exception) {
            throw IllegalArgumentException("endereço do coletor inválido")
        }
        val local = url.host == "127.0.0.1" || url.host == "localhost" || url.host == "10.0.2.2"
        require(url.scheme == "https" || (url.scheme == "http" && local)) { "o coletor precisa ser https" }
        require(!url.host.isNullOrEmpty() && url.rawQuery == null && url.rawFragment == null && url.rawUserInfo == null) {
            "endereço do coletor inválido"
        }
        return raw.trim().trimEnd('/')
    }

    /**
     * Texto que o coletor aceita: letras, dígitos, espaço e `._()-/,+`. O resto vira espaço,
     * espaços repetidos viram um, e o tamanho é cortado no teto. Vazio vira `null`.
     */
    fun sanitize(raw: String?, max: Int): String? {
        if (raw == null) return null
        val cleaned = SPACES.replace(ALLOWED_TEXT.replace(raw, " "), " ").trim()
        val cut = if (cleaned.length > max) cleaned.substring(0, max).trimEnd() else cleaned
        return cut.ifEmpty { null }
    }

    /**
     * Decisão a partir das escolhas da pessoa: obrigatórias sempre concedidas, opcionais que
     * faltarem ficam recusadas, e a ação sai do que foi escolhido.
     */
    fun decide(config: ConsentConfig, choices: Map<String, Boolean>): Pair<ConsentAction, Map<String, Boolean>> {
        val keys = config.categories.map { it.key }.toSet()
        val unknown = choices.keys - keys
        if (unknown.isNotEmpty()) throw ConsentException.InvalidChoice("categoria que o banner não tem: ${unknown.sorted().joinToString()}")
        val refused = config.categories.filter { it.required && choices[it.key] == false }
        if (refused.isNotEmpty()) throw ConsentException.InvalidChoice("categoria obrigatória não pode ser recusada: ${refused.joinToString { it.key }}")
        val categories = LinkedHashMap<String, Boolean>()
        for (category in config.categories) categories[category.key] = category.required || choices[category.key] == true
        val optional = config.categories.filter { !it.required }
        val granted = optional.count { categories[it.key] == true }
        val action = when (granted) {
            optional.size -> ConsentAction.ACCEPT_ALL
            0 -> ConsentAction.REJECT_ALL
            else -> ConsentAction.PARTIAL
        }
        return action to categories
    }

    fun all(config: ConsentConfig, granted: Boolean): Map<String, Boolean> =
        LinkedHashMap<String, Boolean>().apply { config.categories.forEach { put(it.key, it.required || granted) } }

    /** Confere a decisão como o coletor (`checkDecision`). */
    fun check(config: ConsentConfig, action: ConsentAction, categories: Map<String, Boolean>): Boolean {
        val expected = config.categories.map { it.key }
        if (categories.size != expected.size || !expected.all { categories.containsKey(it) }) return false
        if (config.categories.any { it.required && categories[it.key] != true }) return false
        val optional = config.categories.filter { !it.required }
        val granted = optional.count { categories[it.key] == true }
        return when (action) {
            ConsentAction.ACCEPT_ALL -> granted == optional.size
            ConsentAction.REJECT_ALL, ConsentAction.REVOKE -> granted == 0
            ConsentAction.PARTIAL -> granted > 0 && granted < optional.size
        }
    }
}
