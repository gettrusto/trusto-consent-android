package io.gettrusto.consent

import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** O que o SDK diz do aplicativo e do aparelho em cada registro. Nada que identifique o aparelho. */
internal data class AppInfo(
    val packageName: String,
    val sdkVersion: String,
    /** "Android 15". */
    val os: String,
    /** Modelo genérico ("Pixel 8"), opcional. */
    val device: String?,
)

/**
 * O núcleo do SDK, sem nada do Android: banner em cache, decisão local, fila de envio. A
 * fachada `TrustoConsent` monta este objeto com o transporte, a pasta e as threads da
 * plataforma; os testes, com um servidor local e uma pasta temporária.
 *
 * Toda leitura e escrita de estado passa pelo lock do objeto. A rede roda na fila de
 * trabalho (`executors.work`), nunca na thread de quem chama.
 */
internal class ConsentClient(
    private val key: String,
    endpoint: String,
    private val app: AppInfo,
    private val transport: Transport,
    private val storage: Storage,
    private val clock: Clock,
    private val executors: Executors,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val endpoint = Rules.normalizeEndpoint(endpoint)
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(ConsentDecision?) -> Unit>()

    private var config: CachedConfig? = null
    private var decision: ConsentDecision? = null
    private var queue: MutableList<QueuedRecord> = mutableListOf()
    /** Versão que o coletor disse estar publicada num 409, mais nova que a do cache. */
    private var outdatedVersion: Int? = null
    private var flushing = false
    private var retryScheduled = false

    init {
        if (!Rules.isValidKey(key)) throw ConsentException.InvalidKey()
        synchronized(lock) {
            config = storage.read(CONFIG)?.let { runCatching { CachedConfig.fromJson(JsonObject.parse(it)) }.getOrNull() }
            decision = storage.read(DECISION)?.let { runCatching { ConsentDecision.fromJson(JsonObject.parse(it)) }.getOrNull() }
            queue = storage.read(QUEUE)?.let { text ->
                runCatching { (Json.parse(text) as List<*>).map { QueuedRecord.fromJson(JsonObject(it as Map<String, Any?>)) } }.getOrNull()
            }?.toMutableList() ?: mutableListOf()
            outdatedVersion = storage.read(OUTDATED)?.toIntOrNull()
        }
    }

    /** Identificador aleatório do visitante neste aparelho, gerado no primeiro uso. Nunca o de publicidade. */
    val visitorId: String
        get() = synchronized(lock) {
            storage.read(VISITOR)?.takeIf { UUID_PATTERN.matches(it) } ?: newId().lowercase().also { storage.write(VISITOR, it) }
        }

    val currentDecision: ConsentDecision? get() = synchronized(lock) { decision }

    /** O último banner conhecido, mesmo vencido no cache. */
    val lastConfig: ConsentConfig? get() = synchronized(lock) { config?.config }

    /**
     * Pede a decisão: sem decisão, decisão de outra versão do banner (a última que o SDK
     * conhece, inclusive a que o coletor disse num 409) ou decisão vencida.
     */
    val needsConsent: Boolean
        get() = synchronized(lock) {
            val current = decision ?: return true
            val known = maxOf(config?.config?.version ?: current.configVersion, outdatedVersion ?: 0)
            current.configVersion != known || clock.nowMs() >= current.effectiveExpiresAtMs
        }

    fun isGranted(category: String): Boolean = synchronized(lock) {
        decision?.categories?.get(category) ?: (config?.config?.categories?.any { it.key == category && it.required } == true)
    }

    fun addListener(listener: (ConsentDecision?) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (ConsentDecision?) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * O banner do aplicativo. Do cache se tiver menos de 60 s; senão do coletor; sem rede ou
     * com o coletor fora, o último salvo. Bloqueia: chame fora da thread principal (a
     * fachada faz isso).
     */
    @Throws(ConsentException::class)
    fun loadConfigBlocking(): ConsentConfig {
        val now = clock.nowMs()
        synchronized(lock) {
            val cached = config
            if (cached != null && !cached.stale && now - cached.fetchedAtMs < CONFIG_TTL_MS) return cached.config
        }
        val url = "$endpoint/v1/app/config?platform=android&app=${URLEncoder.encode(app.packageName, "UTF-8")}"
        val response = try {
            transport.get(url, headers())
        } catch (e: IOException) {
            return fallback(ConsentException.Unavailable("sem conexão com o coletor"))
        }
        when (response.status) {
            200 -> {
                val fresh = try {
                    ConsentConfig.fromJson(JsonObject.parse(response.body))
                } catch (e: JsonException) {
                    return fallback(ConsentException.Unavailable("resposta do coletor fora do formato"))
                }
                synchronized(lock) {
                    config = CachedConfig(fresh, now, stale = false)
                    storage.write(CONFIG, Json.stringify(config!!.toJson()))
                    if ((outdatedVersion ?: 0) <= fresh.version) {
                        outdatedVersion = null
                        storage.delete(OUTDATED)
                    }
                }
                return fresh
            }
            401 -> throw ConsentException.KeyRejected()
            403 -> throw ConsentException.AppMismatch()
            else -> return fallback(ConsentException.Unavailable("coletor respondeu ${response.status}"))
        }
    }

    private fun fallback(error: ConsentException): ConsentConfig = synchronized(lock) { config?.config } ?: throw error

    /** Aceita tudo. Vale no aparelho na hora; o registro vai ao coletor em seguida. */
    fun acceptAll(): ConsentDecision = decideAll(ConsentAction.ACCEPT_ALL) { Rules.all(it, granted = true) }

    /** Recusa tudo que é opcional. */
    fun rejectAll(): ConsentDecision = decideAll(ConsentAction.REJECT_ALL) { Rules.all(it, granted = false) }

    /** Retira o consentimento das opcionais, com o registro `revoke`. */
    fun revoke(): ConsentDecision = decideAll(ConsentAction.REVOKE) { Rules.all(it, granted = false) }

    /** Escolhas por categoria. Obrigatórias ficam concedidas; opcionais não informadas, recusadas. */
    fun save(choices: Map<String, Boolean>): ConsentDecision {
        val current = lastConfig ?: throw ConsentException.ConfigNotLoaded()
        val (action, categories) = Rules.decide(current, choices)
        return record(current, action, categories)
    }

    private fun decideAll(action: ConsentAction, categories: (ConsentConfig) -> Map<String, Boolean>): ConsentDecision {
        val current = lastConfig ?: throw ConsentException.ConfigNotLoaded()
        return record(current, action, categories(current))
    }

    private fun record(config: ConsentConfig, action: ConsentAction, categories: Map<String, Boolean>): ConsentDecision {
        check(Rules.check(config, action, categories)) { "decisão incoerente com o banner" }
        val now = clock.nowMs()
        val id = newId()
        val made = ConsentDecision(
            action = action,
            categories = categories,
            configVersion = config.version,
            decidedAtMs = now,
            localExpiresAtMs = now + config.consentExpiryDays * DAY_MS,
            consentId = null,
            expiresAtMs = null,
            pendingId = id,
        )
        val visitor = visitorId
        synchronized(lock) {
            decision = made
            storage.write(DECISION, Json.stringify(made.toJson()))
            queue.add(QueuedRecord(id, visitor, action, categories, config.version, now, attempts = 0, nextAttemptAtMs = now))
            trimQueue(now)
            saveQueue()
        }
        notifyListeners(made)
        flush()
        return made
    }

    /** Envia o que estiver na fila, fora da thread de quem chama. */
    fun flush() {
        executors.work.execute { flushBlocking() }
    }

    /** Envia a fila em ordem. Para no primeiro reenviável, que espera o prazo dele. */
    fun flushBlocking() {
        synchronized(lock) {
            if (flushing) return
            flushing = true
        }
        try {
            while (true) {
                val now = clock.nowMs()
                val item = synchronized(lock) {
                    trimQueue(now)
                    queue.firstOrNull()
                } ?: return
                if (item.nextAttemptAtMs > now) {
                    scheduleRetry(item.nextAttemptAtMs - now)
                    return
                }
                when (val outcome = send(item)) {
                    is Outcome.Done -> synchronized(lock) {
                        queue.removeAll { it.id == item.id }
                        saveQueue()
                    }
                    is Outcome.Retry -> {
                        val delay = backoff(item.attempts + 1)
                        synchronized(lock) {
                            val index = queue.indexOfFirst { it.id == item.id }
                            if (index >= 0) queue[index] = item.copy(attempts = item.attempts + 1, nextAttemptAtMs = now + delay)
                            saveQueue()
                        }
                        scheduleRetry(delay)
                        return
                    }
                    is Outcome.Receipt -> {
                        var changed: ConsentDecision? = null
                        synchronized(lock) {
                            queue.removeAll { it.id == item.id }
                            saveQueue()
                            val current = decision
                            if (current != null && current.pendingId == item.id) {
                                changed = current.copy(consentId = outcome.consentId, expiresAtMs = outcome.expiresAtMs, pendingId = null)
                                decision = changed
                                storage.write(DECISION, Json.stringify(changed!!.toJson()))
                            }
                        }
                        changed?.let(::notifyListeners)
                    }
                    is Outcome.Outdated -> {
                        synchronized(lock) {
                            queue.removeAll { it.id == item.id }
                            saveQueue()
                            // O banner mudou: o cache não vale mais, e a pessoa decide de novo.
                            config = config?.copy(stale = true)
                            if (outcome.version != null) {
                                outdatedVersion = maxOf(outdatedVersion ?: 0, outcome.version)
                                storage.write(OUTDATED, outdatedVersion.toString())
                            }
                        }
                        notifyListeners(currentDecision)
                    }
                }
            }
        } finally {
            synchronized(lock) { flushing = false }
        }
    }

    private fun send(item: QueuedRecord): Outcome {
        val body = linkedMapOf<String, Any?>(
            "visitorId" to item.visitorId,
            "action" to item.action.wire,
            "categories" to LinkedHashMap(item.categories),
            "configVersion" to item.configVersion,
            "app" to appJson(),
        )
        val response = try {
            transport.post("$endpoint/v1/app/records", headers() + ("Content-Type" to "application/json"), Json.stringify(body))
        } catch (e: IOException) {
            return Outcome.Retry
        }
        return when (response.status) {
            201 -> runCatching {
                val json = JsonObject.parse(response.body)
                Outcome.Receipt(json.string("consentId"), parseInstant(json.string("expiresAt")))
            }.getOrElse { Outcome.Done }
            202 -> Outcome.Done
            409 -> Outcome.Outdated(runCatching { JsonObject.parse(response.body).int("version") }.getOrNull())
            429 -> Outcome.Retry
            in 500..599 -> Outcome.Retry
            // 400, 401, 403, 404, 413: o coletor nunca vai aceitar este pedido. Reenviar só gastaria bateria.
            else -> Outcome.Done
        }
    }

    private fun appJson(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "platform" to "android",
        "identifier" to app.packageName,
        "sdkVersion" to app.sdkVersion,
        "os" to (Rules.sanitize(app.os, Rules.MAX_OS) ?: "Android"),
    ).also { json -> Rules.sanitize(app.device, Rules.MAX_DEVICE)?.let { json["device"] = it } }

    private fun headers(): Map<String, String> = mapOf(
        "Authorization" to "Bearer $key",
        "Accept" to "application/json",
        "User-Agent" to "TrustoConsentAndroid/${app.sdkVersion}",
    )

    private fun scheduleRetry(delayMs: Long) {
        synchronized(lock) {
            if (retryScheduled) return
            retryScheduled = true
        }
        executors.schedule(delayMs) {
            synchronized(lock) { retryScheduled = false }
            flushBlocking()
        }
    }

    /** Fila com no máximo 50 itens e 30 dias: o mais velho sai primeiro. */
    private fun trimQueue(now: Long) {
        queue.removeAll { now - it.createdAtMs > MAX_QUEUE_AGE_MS }
        while (queue.size > MAX_QUEUE) queue.removeAt(0)
    }

    private fun saveQueue() {
        storage.write(QUEUE, Json.stringify(queue.map { it.toJson() }))
    }

    private fun notifyListeners(decision: ConsentDecision?) {
        for (listener in listeners) executors.callbacks.execute { listener(decision) }
    }

    /** Pendências na fila, para diagnóstico e testes. */
    val pendingCount: Int get() = synchronized(lock) { queue.size }

    private sealed class Outcome {
        object Done : Outcome()
        object Retry : Outcome()
        data class Receipt(val consentId: String, val expiresAtMs: Long?) : Outcome()
        data class Outdated(val version: Int?) : Outcome()
    }

    internal companion object {
        const val CONFIG_TTL_MS = 60_000L
        const val DAY_MS = 86_400_000L
        const val MAX_QUEUE = 50
        const val MAX_QUEUE_AGE_MS = 30 * DAY_MS
        const val MAX_BACKOFF_MS = 60 * 60_000L
        private const val BASE_BACKOFF_MS = 5_000L
        private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

        const val VISITOR = "visitor"
        const val CONFIG = "config"
        const val DECISION = "decision"
        const val QUEUE = "queue"
        const val OUTDATED = "outdated"

        /** 5 s, 10 s, 20 s... até 1 h. */
        fun backoff(attempts: Int): Long {
            val shift = (attempts - 1).coerceIn(0, 20)
            return minOf(BASE_BACKOFF_MS shl shift, MAX_BACKOFF_MS)
        }

        fun parseInstant(iso: String): Long? = runCatching {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            format.parse(iso)!!.time
        }.getOrNull()
    }
}

internal data class CachedConfig(val config: ConsentConfig, val fetchedAtMs: Long, val stale: Boolean) {
    fun toJson(): Map<String, Any?> = linkedMapOf("config" to config.toJson(), "fetchedAtMs" to fetchedAtMs, "stale" to stale)

    companion object {
        fun fromJson(json: JsonObject) = CachedConfig(
            ConsentConfig.fromJson(json.obj("config")),
            json.long("fetchedAtMs"),
            json.raw()["stale"] == true,
        )
    }
}

internal data class QueuedRecord(
    val id: String,
    val visitorId: String,
    val action: ConsentAction,
    val categories: Map<String, Boolean>,
    val configVersion: Int,
    val createdAtMs: Long,
    val attempts: Int,
    val nextAttemptAtMs: Long,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "visitorId" to visitorId,
        "action" to action.wire,
        "categories" to LinkedHashMap(categories),
        "configVersion" to configVersion,
        "createdAtMs" to createdAtMs,
        "attempts" to attempts,
        "nextAttemptAtMs" to nextAttemptAtMs,
    )

    companion object {
        fun fromJson(json: JsonObject) = QueuedRecord(
            id = json.string("id"),
            visitorId = json.string("visitorId"),
            action = ConsentAction.fromWire(json.string("action")),
            categories = json.obj("categories").raw().mapValues { it.value == true },
            configVersion = json.int("configVersion"),
            createdAtMs = json.long("createdAtMs"),
            attempts = json.int("attempts"),
            nextAttemptAtMs = json.long("nextAttemptAtMs"),
        )
    }
}
