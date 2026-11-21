package io.gettrusto.consent

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors as JavaExecutors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Consentimento LGPD no aplicativo, sem tela (ADR 0135, modo "sem tela"): o aplicativo
 * desenha a própria tela com o banner publicado e o SDK grava a decisão como prova.
 *
 * ```
 * TrustoConsent.configure(this, "trusto_pk_live_…")
 * TrustoConsent.loadConfig { result -> … }   // ou: val config = TrustoConsent.awaitConfig()
 * if (TrustoConsent.needsConsent) { /* mostrar a tela do aplicativo */ }
 * TrustoConsent.acceptAll()                  // rejectAll(), save(mapOf(...)), revoke()
 * TrustoConsent.addListener { _ -> analytics.enabled = TrustoConsent.isGranted("analytics") }
 * ```
 *
 * A decisão vale no aparelho na hora; o registro vai ao coletor em segundo plano e é
 * reenviado sozinho quando a rede volta. Listeners são chamados na thread principal.
 */
public object TrustoConsent {
    /** Endereço do coletor em produção. Para teste, passe o endereço de staging em `configure`. */
    public const val DEFAULT_ENDPOINT: String = "https://consent.gettrusto.io"

    /** Versão deste SDK, enviada em cada registro. */
    public const val SDK_VERSION: String = BuildConfig.SDK_VERSION

    private val slot = ClientSlot()
    private val client: ConsentClient? get() = slot.current
    private var scheduler: ScheduledExecutorService? = null

    /**
     * Prepara o SDK. Chame uma vez, no `Application.onCreate`. A chave é a publicável do
     * aplicativo (aba Aplicativos, em Consentimentos): não é segredo e pode ir no código.
     * Chamar de novo com a mesma chave e o mesmo endereço não faz nada; com outros, encerra
     * a instância anterior (reenvio agendado e listeners) e começa outra.
     *
     * @throws ConsentException.InvalidKey se a chave não tiver o formato `trusto_pk_…`.
     * @throws ConsentException.InvalidEndpoint se o endereço não for https.
     */
    @JvmStatic
    @JvmOverloads
    @Synchronized
    public fun configure(context: Context, publishableKey: String, endpoint: String = DEFAULT_ENDPOINT) {
        val app = context.applicationContext
        val key = publishableKey.trim()
        // `http` para o aparelho ou o emulador só em app debuggable: em produção, só https.
        val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val worker = scheduler ?: JavaExecutors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "trusto-consent").apply { isDaemon = true }
        }.also { scheduler = it }
        val main = Handler(Looper.getMainLooper())
        val dir = File(app.noBackupFilesDir, "trusto-consent")
        slot.obtain(key, endpoint, debuggable) {
            ConsentClient(
                key = key,
                endpoint = endpoint,
                app = AppInfo(
                    packageName = app.packageName,
                    sdkVersion = SDK_VERSION,
                    os = "Android ${Build.VERSION.RELEASE}",
                    device = Build.MODEL,
                ),
                transport = UrlConnectionTransport(),
                storage = FileStorage(dir),
                clock = Clock { System.currentTimeMillis() },
                executors = Executors(
                    work = worker,
                    callbacks = { task -> main.post(task) },
                    schedule = { delay, task -> worker.schedule(task, delay, TimeUnit.MILLISECONDS) },
                ),
                allowLocalHttp = debuggable,
            )
        }.flush()
    }

    private fun requireClient(): ConsentClient = client ?: throw ConsentException.NotConfigured()

    /** Carrega o banner, em segundo plano; o resultado chega na thread principal. */
    @JvmStatic
    public fun loadConfig(callback: (Result<ConsentConfig>) -> Unit) {
        val current = requireClient()
        val worker = scheduler ?: throw ConsentException.NotConfigured()
        val main = Handler(Looper.getMainLooper())
        worker.execute {
            val result = runCatching { current.loadConfigBlocking() }
            main.post { callback(result) }
        }
    }

    /** O mesmo que `loadConfig`, para quem usa corrotinas (só a stdlib do Kotlin). */
    public suspend fun awaitConfig(): ConsentConfig = suspendCoroutine { continuation ->
        loadConfig { result ->
            result.fold(continuation::resume, continuation::resumeWithException)
        }
    }

    /** O último banner carregado, mesmo sem rede. `null` antes do primeiro `loadConfig`. */
    @JvmStatic
    public val config: ConsentConfig? get() = requireClient().lastConfig

    /** A decisão em vigor neste aparelho, ou `null`. */
    @JvmStatic
    public val decision: ConsentDecision? get() = requireClient().currentDecision

    /** Pedir a decisão: não há, é de outra versão do banner, ou venceu. */
    @JvmStatic
    public val needsConsent: Boolean get() = requireClient().needsConsent

    /** A categoria está concedida? Obrigatórias sempre; opcionais só com decisão dentro do prazo. */
    @JvmStatic
    public fun isGranted(category: String): Boolean = requireClient().isGranted(category)

    @JvmStatic
    public fun acceptAll(): ConsentDecision = requireClient().acceptAll()

    @JvmStatic
    public fun rejectAll(): ConsentDecision = requireClient().rejectAll()

    /** Escolhas por categoria. Obrigatórias ficam concedidas; opcionais não informadas, recusadas. */
    @JvmStatic
    public fun save(choices: Map<String, Boolean>): ConsentDecision = requireClient().save(choices)

    /** Retira o consentimento das categorias opcionais. */
    @JvmStatic
    public fun revoke(): ConsentDecision = requireClient().revoke()

    /**
     * Chamado na thread principal com a decisão atual logo ao registrar, e depois a cada
     * decisão nova, recibo do coletor ou banner novo.
     */
    @JvmStatic
    public fun addListener(listener: (ConsentDecision?) -> Unit) {
        requireClient().addListener(listener)
    }

    @JvmStatic
    public fun removeListener(listener: (ConsentDecision?) -> Unit) {
        requireClient().removeListener(listener)
    }

    /** Tenta enviar agora o que estiver pendente (o SDK já tenta sozinho). */
    @JvmStatic
    public fun flush() {
        requireClient().flush()
    }

    /** Identificador aleatório deste aparelho no consentimento: é o que o titular informa num pedido. */
    @JvmStatic
    public val visitorId: String get() = requireClient().visitorId
}

/**
 * A instância em uso. `configure` repetido com a mesma chave e o mesmo endereço a reaproveita;
 * com outros, a nova entra e a anterior é encerrada, para duas instâncias nunca enviarem a
 * mesma fila.
 */
internal class ClientSlot {
    @Volatile
    var current: ConsentClient? = null
        private set

    @Synchronized
    fun obtain(key: String, endpoint: String, allowLocalHttp: Boolean, create: () -> ConsentClient): ConsentClient {
        current?.let { if (it.sameAs(key, endpoint, allowLocalHttp)) return it }
        val next = create()
        current?.close()
        current = next
        return next
    }
}
