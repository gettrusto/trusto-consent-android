package io.gettrusto.consent.sample

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.gettrusto.consent.ConsentConfig
import io.gettrusto.consent.ConsentDecision
import io.gettrusto.consent.ConsentException
import io.gettrusto.consent.TrustoConsent
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tela única do app de teste, no modo sem tela do SDK: o app desenha o banner publicado e
 * chama o SDK nos botões. Montada em código, sem bibliotecas além da plataforma.
 */
class MainActivity : Activity() {
    private lateinit var endpointText: TextView
    private lateinit var bannerText: TextView
    private lateinit var categoriesBox: LinearLayout
    private lateinit var stateText: TextView
    private lateinit var logText: TextView
    private val checks = LinkedHashMap<String, CheckBox>()
    /** Camada por cima da tela, onde o banner aparece na posição publicada. */
    private lateinit var bannerHost: FrameLayout
    private val log = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    private val listener: (ConsentDecision?) -> Unit = { refreshState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(32))
        }
        root.addView(text("Trusto Consent, app de teste", 20f, bold = true))
        endpointText = text("", 12f).also(root::addView)

        root.addView(section("Banner publicado"))
        bannerText = text("Ainda não carregado.", 14f).also(root::addView)
        categoriesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(categoriesBox)

        root.addView(section("Ações"))
        root.addView(row(button("Carregar banner") { loadConfig() }, button("Enviar agora") { flush() }))
        root.addView(row(button("Aceitar tudo") { decide("acceptAll") { TrustoConsent.acceptAll() } },
            button("Recusar tudo") { decide("rejectAll") { TrustoConsent.rejectAll() } }))
        root.addView(row(button("Salvar escolhas") { decide("save") { TrustoConsent.save(choices()) } },
            button("Revogar") { decide("revoke") { TrustoConsent.revoke() } }))
        root.addView(row(button("Preferências de privacidade") { showBanner(preferences = true) }, button("Trocar coletor") { toggleEndpoint() }))

        root.addView(section("Estado"))
        stateText = text("", 13f, mono = true).also(root::addView)

        root.addView(section("Eventos"))
        logText = text("", 12f, mono = true).also(root::addView)

        bannerHost = FrameLayout(this)
        setContentView(FrameLayout(this).apply {
            addView(ScrollView(this@MainActivity).apply { addView(root) })
            addView(bannerHost, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })
        TrustoConsent.addListener(listener)
        TrustoConsent.config?.let(::showConfig)
        refreshState()
        loadConfig()
    }

    override fun onDestroy() {
        TrustoConsent.removeListener(listener)
        super.onDestroy()
    }

    private fun loadConfig() {
        addLog("loadConfig...")
        TrustoConsent.loadConfig { result ->
            result
                .onSuccess { config ->
                    addLog("banner versão ${config.version}, ${config.categories.size} finalidades")
                    showConfig(config)
                    // Como um app de verdade: sem decisão válida para este banner, ele aparece.
                    if (TrustoConsent.needsConsent) showBanner(preferences = false)
                }
                .onFailure { error -> addLog("loadConfig falhou: ${describe(error)}") }
            refreshState()
        }
    }

    private fun decide(name: String, action: () -> ConsentDecision) {
        try {
            val decision = action()
            addLog("$name: ${decision.action}, ${decision.categories}")
            hideBanner()
        } catch (e: ConsentException) {
            addLog("$name falhou: ${describe(e)}")
        }
        refreshState()
    }

    private fun flush() {
        TrustoConsent.flush()
        addLog("flush pedido")
        // O envio roda na thread do SDK; o recibo chega pelo listener.
        endpointText.postDelayed({ refreshState() }, 1500)
    }

    private fun toggleEndpoint() {
        val endpoint = SampleApp.toggleEndpoint(this)
        TrustoConsent.addListener(listener)
        addLog("coletor agora: $endpoint")
        checks.clear()
        categoriesBox.removeAllViews()
        bannerText.text = "Ainda não carregado."
        loadConfig()
    }

    private fun choices(): Map<String, Boolean> = checks.mapValues { it.value.isChecked }

    private fun showBanner(preferences: Boolean) {
        val config = TrustoConsent.config ?: return addLog("banner ainda não carregado")
        bannerHost.removeAllViews()
        bannerHost.addView(
            BannerView(
                context = this,
                config = config,
                current = TrustoConsent.decision?.categories.orEmpty(),
                onAcceptAll = { decide("acceptAll") { TrustoConsent.acceptAll() } },
                onRejectAll = { decide("rejectAll") { TrustoConsent.rejectAll() } },
                onSave = { chosen -> decide("save") { TrustoConsent.save(chosen) } },
                // Reaberto pelas preferências, fecha sem decidir; na primeira vez, só decidindo.
                onClose = if (preferences) ({ hideBanner() }) else null,
                startInPreferences = preferences,
            ),
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
    }

    private fun hideBanner() {
        bannerHost.removeAllViews()
        TrustoConsent.config?.let(::showConfig)
    }

    private fun showConfig(config: ConsentConfig) {
        bannerText.text = buildString {
            append("Versão ").append(config.version)
            append(", prazo de ").append(config.consentExpiryDays).append(" dias")
            append(if (config.blockBeforeConsent) ", bloqueia antes da decisão" else "")
            append("\nPolítica: ").append(config.privacyPolicyUrl)
            config.holderUrl?.let { append("\nCanal do titular: ").append(it) }
        }
        val decided = TrustoConsent.decision?.categories
        checks.clear()
        categoriesBox.removeAllViews()
        for (category in config.categories) {
            val box = CheckBox(this).apply {
                text = buildString {
                    append(category.name)
                    if (category.required) append(" (obrigatória)")
                    append("\n").append(category.description)
                }
                isChecked = category.required || (decided?.get(category.key) ?: category.defaultEnabled)
                isEnabled = !category.required
            }
            checks[category.key] = box
            categoriesBox.addView(box)
        }
    }

    private fun refreshState() {
        endpointText.text = "Coletor: ${SampleApp.currentEndpoint(this)}\nChave: ${BuildConfig.TRUSTO_KEY.take(16)}..."
        val decision = TrustoConsent.decision
        val queue = readQueue()
        stateText.text = buildString {
            append("needsConsent: ").append(TrustoConsent.needsConsent).append('\n')
            append("decisão: ")
            if (decision == null) {
                append("nenhuma\n")
            } else {
                append(decision.action).append(", banner v").append(decision.configVersion).append('\n')
                append("recibo: ").append(decision.consentId ?: "ainda não confirmado").append('\n')
                append("vence: ").append(formatDate(decision.effectiveExpiresAtMs)).append('\n')
            }
            val keys = TrustoConsent.config?.categories?.map { it.key } ?: decision?.categories?.keys?.toList().orEmpty()
            for (key in keys) append("isGranted(").append(key).append("): ").append(TrustoConsent.isGranted(key)).append('\n')
            append("fila no aparelho: ").append(queue).append('\n')
            append("visitorId: ").append(TrustoConsent.visitorId.take(8)).append("...")
        }
    }

    /**
     * Só para diagnóstico no app de teste: conta os registros no arquivo da fila do SDK. O
     * arquivo é interno e o formato pode mudar; nenhum app de verdade deve ler isto.
     */
    private fun readQueue(): String = try {
        val file = File(File(noBackupFilesDir, "trusto-consent"), "queue.json")
        if (!file.exists()) {
            "0 registros"
        } else {
            val items = JSONObject(file.readText()).getJSONArray("data")
            val attempts = (0 until items.length()).joinToString(",") { items.getJSONObject(it).optInt("attempts").toString() }
            "${items.length()} registros" + if (items.length() > 0) " (tentativas: $attempts)" else ""
        }
    } catch (e: Exception) {
        "ilegível"
    }

    private fun describe(error: Throwable): String = "${error.javaClass.simpleName}: ${error.message}"

    private fun addLog(line: String) {
        log.addFirst("${clock.format(Date())} $line")
        while (log.size > 20) log.removeLast()
        if (::logText.isInitialized) logText.text = log.joinToString("\n")
    }

    private fun formatDate(ms: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(ms))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(0x29, 0x25, 0x24))
        typeface = when {
            mono -> Typeface.MONOSPACE
            bold -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun section(title: String) = text(title, 16f, bold = true).apply { setPadding(0, dp(16), 0, dp(4)) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (view in views) addView(view, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }
}
