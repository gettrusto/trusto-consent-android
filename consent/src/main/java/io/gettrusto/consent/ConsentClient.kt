package io.gettrusto.consent

import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** O que o SDK diz do aplicativo e do aparelho em cada registro. Nada que identifique o aparelho. */
internal class AppInfo(
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
 * Toda leitura e escrita de estado em memória passa pelo lock do objeto. Rede e disco rodam
 * na fila de trabalho (`executors.work`), nunca na thread de quem chama, e uma falha de
 * disco nunca sai daqui: a decisão continua valendo em memória.
 */
internal class ConsentClient(
    private val key: String,
    endpoint: String,
    private val app: AppInfo,
    private val transport: Transport,
    storage: Storage,
    private val clock: Clock,
    private val executors: Executors,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    allowLocalHttp: Boolean = false,
) {
    private val endpoint = Rules.normalizeEndpoint(endpoint, allowLocalHttp)
    private val storage = SafeStorage(storage)
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(ConsentDecision?) -> Unit>()

    private var config: CachedConfig? = null
    private var decision: ConsentDecision? = null
    private var queue: MutableList<QueuedRecord> = mutableListOf()
    private var visitor: String? = null
    /** Versão que o coletor disse estar publicada num 409, mais nova que a do cache. */
    private var outdatedVersion: Int? = null
    private var flushing = false
    private var retryScheduled = false

    @Volatile
    private var closed = false

    init {
        if (!Rules.isValidKey(key)) throw ConsentException.InvalidKey()
        synchronized(lock) {
            config = Persisted.read(this.storage, CONFIG)?.let { runCatching { CachedConfig.fromJson(JsonObject(it.asMap())) }.getOrNull() }
            decision = Persisted.read(this.storage, DECISION)?.let { runCatching { ConsentDecision.fromJson(JsonObject(it.asMap())) }.getOrNull() }
            queue = Persisted.read(this.storage, QUEUE)?.let { data ->
                runCatching { (data as List<*>).map { QueuedRecord.fromJson(JsonObject((it as Map<*, *>).asMap())) } }.getOrNull()
            }?.toMutableList() ?: mutableListOf()
            outdatedVersion = this.storage.read(OUTDATED)?.toIntOrNull()
            visitor = this.storage.read(VISITOR)?.trim()?.takeIf { UUID_PATTERN.matches(it) }
        }
    }

    /** Mesma chave e mesmo endereço: o `configure` repetido reaproveita esta instância. */
    fun sameAs(otherKey: String, otherEndpoint: String, allowLocalHttp: Boolean): Boolean =
        key == otherKey && runCatching { Rules.normalizeEndpoint(otherEndpoint, allowLocalHttp) }.getOrNull() == endpoint

    /** Encerra: nenhum envio, reenvio agendado ou aviso sai mais desta instância. */
    fun close() {
        closed = true
        listeners.clear()
    }

    /**
     * Identificador aleatório do visitante neste aparelho, gerado no primeiro uso e guardado
     * em memória: uma falha de disco não gera outro a cada decisão. Nunca o de publicidade.
     */
    val visitorId: String
        get() = synchronized(lock) {
            visitor ?: newId().lowercase().also { id ->
                visitor = id
                persist { storage.write(VISITOR, id) }
            }
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

    /**
     * Obrigatórias, sempre concedidas. Opcionais, só com decisão dentro do prazo: vencida a
     * decisão, o app desliga o que é opcional até a pessoa decidir de novo.
     */
    fun isGranted(category: String): Boolean = synchronized(lock) {
        if (config?.config?.categories?.any { it.key == category && it.required } == true) return true
        val current = decision ?: return false
        if (clock.nowMs() >= current.effectiveExpiresAtMs) return false
        current.categories[category] == true
    }

    /** O listener recebe a decisão atual na hora, e depois cada mudança. */
    fun addListener(listener: (ConsentDecision?) -> Unit) {
        if (closed) return
        listeners.add(listener)
        val current = currentDecision
        executors.callbacks.execute { listener(current) }
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
        } catch (e: Exception) {
            // Rede, DNS, TLS ou resposta acima do teto: o último banner salvo vale.
            return fallback(ConsentException.Unavailable("sem conexão com o coletor"))
        }
        when (response.status) {
            200 -> {
                val fresh = try {
                    ConsentConfig.fromJson(JsonObject.parse(response.body))
                } catch (e: Exception) {
                    return fallback(ConsentException.Unavailable("resposta do coletor fora do formato"))
                }
                synchronized(lock) {
                    val cached = CachedConfig(fresh, now, stale = false)
                    config = cached
                    Persisted.write(storage, CONFIG, cached.toJson())
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
        val current = configForDecision()
        val (action, categories) = Rules.decide(current, choices)
        return record(current, action, categories)
    }

    private fun decideAll(action: ConsentAction, categories: (ConsentConfig) -> Map<String, Boolean>): ConsentDecision {
        val current = configForDecision()
        return record(current, action, categories(current))
    }

    /**
     * O banner sobre o qual a pessoa decide. Depois de um 409 o banner do cache é de outra
     * versão: decidir sobre ele seria gravar uma prova de banner que não está mais no ar.
     */
    private fun configForDecision(): ConsentConfig = synchronized(lock) {
        val cached = config ?: throw ConsentException.ConfigNotLoaded()
        if (cached.stale) throw ConsentException.ConfigNotLoaded()
        cached.config
    }

    private fun record(config: ConsentConfig, action: ConsentAction, categories: Map<String, Boolean>): ConsentDecision {
        if (!Rules.check(config, action, categories)) throw ConsentException.InvalidChoice("decisão incoerente com o banner")
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
        val visitorId = visitorId
        synchronized(lock) {
            decision = made
            queue.add(QueuedRecord(id, visitorId, action, categories, config.version, now, attempts = 0, nextAttemptAtMs = now))
            trimQueue(now)
            val decisionJson = made.toJson()
            val queueJson = queueJson()
            persist {
                Persisted.write(storage, DECISION, decisionJson)
                Persisted.write(storage, QUEUE, queueJson)
            }
        }
        notifyListeners(made)
        flush()
        return made
    }

    /** Envia o que estiver na fila, fora da thread de quem chama. */
    fun flush() {
        if (closed) return
        executors.work.execute { flushBlocking() }
    }

    /** Envia a fila em ordem. Para no primeiro reenviável, que espera o prazo dele. */
    fun flushBlocking() {
        synchronized(lock) {
            if (flushing || closed) return
            flushing = true
        }
        try {
            while (!closed) {
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
                        val delay = if (outcome.long) maxOf(LONG_RETRY_MS, backoff(item.attempts + 1)) else backoff(item.attempts + 1)
                        synchronized(lock) {
                            val index = queue.indexOfFirst { it.id == item.id }
                            if (index >= 0) queue[index] = QueuedRecord(item.id, item.visitorId, item.action, item.categories, item.configVersion, item.createdAtMs, item.attempts + 1, now + delay)
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
                                val updated = current.copy(consentId = outcome.consentId, expiresAtMs = outcome.expiresAtMs, pendingId = null)
                                decision = updated
                                Persisted.write(storage, DECISION, updated.toJson())
                                changed = updated
                            }
                        }
                        changed?.let(::notifyListeners)
                    }
                    is Outcome.Outdated -> {
                        synchronized(lock) {
                            queue.removeAll { it.id == item.id }
                            saveQueue()
                            // O banner mudou: o cache não vale mais, e a pessoa decide de novo.
                            config = config?.let { CachedConfig(it.config, it.fetchedAtMs, stale = true) }?.also { Persisted.write(storage, CONFIG, it.toJson()) }
                            if (outcome.version != null) {
                                val version = maxOf(outdatedVersion ?: 0, outcome.version)
                                outdatedVersion = version
                                storage.write(OUTDATED, version.toString())
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
        } catch (e: Exception) {
            return Outcome.Retry(long = false)
        }
        return when (response.status) {
            201 -> runCatching {
                val json = JsonObject.parse(response.body)
                Outcome.Receipt(json.string("consentId"), parseInstant(json.string("expiresAt")))
            }.getOrElse { Outcome.Done }
            202 -> Outcome.Done
            409 -> Outcome.Outdated(runCatching { JsonObject.parse(response.body).int("version") }.getOrNull())
            429 -> Outcome.Retry(long = false)
            in 500..599 -> Outcome.Retry(long = false)
            // 401: a chave pode estar fora do ar por um tempo (posse em reconferência, banner
            // desligado e religado, reconciliação parada). 404: rota desligada no coletor. A
            // decisão já vale no aparelho; a prova espera, até o teto de 30 dias da fila.
            401, 404 -> Outcome.Retry(long = true)
            // 400, 403, 413: o coletor nunca vai aceitar este pedido. Reenviar só gastaria bateria.
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
            if (retryScheduled || closed) return
            retryScheduled = true
        }
        executors.schedule(delayMs) {
            synchronized(lock) { retryScheduled = false }
            if (!closed) flushBlocking()
        }
    }

    /** Fila com no máximo 50 itens e 30 dias: o mais velho sai primeiro. */
    private fun trimQueue(now: Long) {
        queue.removeAll { now - it.createdAtMs > MAX_QUEUE_AGE_MS }
        while (queue.size > MAX_QUEUE) queue.removeAt(0)
    }

    private fun queueJson(): List<Map<String, Any?>> = queue.map { it.toJson() }

    /** Só na fila de trabalho (dentro do envio), onde a gravação pode esperar o disco. */
    private fun saveQueue() {
        Persisted.write(storage, QUEUE, queueJson())
    }

    /**
     * Gravação a partir da thread de quem chama: vai para a fila de trabalho, em ordem. O
     * conteúdo já foi copiado sob o lock, então a gravação mais nova é sempre a última.
     */
    private fun persist(task: () -> Unit) {
        executors.work.execute(task)
    }

    private fun notifyListeners(decision: ConsentDecision?) {
        if (closed) return
        for (listener in listeners) executors.callbacks.execute { listener(decision) }
    }

    /** Pendências na fila, para diagnóstico e testes. */
    val pendingCount: Int get() = synchronized(lock) { queue.size }

    private sealed class Outcome {
        object Done : Outcome()
        class Retry(val long: Boolean) : Outcome()
        class Receipt(val consentId: String, val expiresAtMs: Long?) : Outcome()
        class Outdated(val version: Int?) : Outcome()
    }

    internal companion object {
        const val CONFIG_TTL_MS = 60_000L
        const val DAY_MS = 86_400_000L
        const val MAX_QUEUE = 50
        const val MAX_QUEUE_AGE_MS = 30 * DAY_MS
        const val MAX_BACKOFF_MS = 60 * 60_000L
        /** Espera mínima depois de 401 ou 404 no envio. */
        const val LONG_RETRY_MS = 60 * 60_000L
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

/**
 * Arquivos persistidos com versão de formato: `{"v":1,"data":…}`. Arquivo corrompido ou de
 * versão que este SDK não conhece é descartado; uma versão futura do SDK migra pelo `v`.
 */
internal object Persisted {
    const val VERSION = 1L

    fun read(storage: Storage, name: String): Any? {
        val text = storage.read(name) ?: return null
        val root = runCatching { Json.parse(text) }.getOrNull() as? Map<*, *> ?: return null
        if ((root["v"] as? Number)?.toLong() != VERSION) return null
        return root["data"]
    }

    fun write(storage: Storage, name: String, data: Any?) {
        storage.write(name, Json.stringify(linkedMapOf("v" to VERSION, "data" to data)))
    }
}

@Suppress("UNCHECKED_CAST")
private fun Any?.asMap(): Map<String, Any?> = this as? Map<String, Any?> ?: throw JsonException("não é objeto")

internal class CachedConfig(val config: ConsentConfig, val fetchedAtMs: Long, val stale: Boolean) {
    fun toJson(): Map<String, Any?> = linkedMapOf("config" to config.toJson(), "fetchedAtMs" to fetchedAtMs, "stale" to stale)

    companion object {
        fun fromJson(json: JsonObject) = CachedConfig(
            ConsentConfig.fromJson(json.obj("config")),
            json.long("fetchedAtMs"),
            json.raw()["stale"] == true,
        )
    }
}

internal class QueuedRecord(
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
