package io.gettrusto.consent.sample

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.gettrusto.consent.ConsentConfig

/**
 * O banner como um app de verdade desenharia no modo sem tela do SDK: cores, cantos e posição
 * vêm do banner publicado; os textos são do app, como no widget do site. Duas vistas: a
 * primeira camada (aceitar, recusar, personalizar) e as preferências por finalidade.
 */
class BannerView(
    context: Context,
    private val config: ConsentConfig,
    /** Escolhas atuais, para abrir as preferências como estão. */
    private val current: Map<String, Boolean>,
    private val onAcceptAll: () -> Unit,
    private val onRejectAll: () -> Unit,
    private val onSave: (Map<String, Boolean>) -> Unit,
    private val onClose: (() -> Unit)?,
    startInPreferences: Boolean = false,
) : FrameLayout(context) {
    private val primary = parse(config.bannerStyle.primaryColor, Color.rgb(0xB4, 0x53, 0x09))
    private val fgColor = parse(config.bannerStyle.textColor, Color.rgb(0x29, 0x25, 0x24))
    private val bgColor = parse(config.bannerStyle.backgroundColor, Color.WHITE)
    private val radius = dp(config.bannerStyle.borderRadius.coerceIn(0, 32)).toFloat()
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(16))
        background = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = radius
        }
        elevation = dp(12).toFloat()
    }

    init {
        // No centro, o banner é modal: o fundo escurece e bloqueia o app até a decisão.
        val position = config.bannerPosition
        if (position == "center") setBackgroundColor(Color.argb(0x88, 0, 0, 0))
        isClickable = position == "center"
        val gravity = when (position) {
            "top" -> Gravity.TOP
            "center" -> Gravity.CENTER
            "bottom-left" -> Gravity.BOTTOM or Gravity.START
            "bottom-right" -> Gravity.BOTTOM or Gravity.END
            else -> Gravity.BOTTOM
        }
        val corner = position == "bottom-left" || position == "bottom-right"
        addView(card, LayoutParams(if (corner) dp(340) else MATCH_PARENT, WRAP_CONTENT, gravity).apply { setMargins(dp(12), dp(12), dp(12), dp(12)) })
        if (startInPreferences) showPreferences() else showFirstLayer()
    }

    private fun showFirstLayer() {
        card.removeAllViews()
        card.addView(text("Sua privacidade", 18f, bold = true))
        card.addView(text(
            "Usamos seus dados para o aplicativo funcionar e, com a sua permissão, para medir o uso, " +
                "personalizar e exibir anúncios. Você pode aceitar, recusar ou escolher por finalidade, " +
                "e mudar de ideia quando quiser.",
            14f,
        ))
        card.addView(link("Política de privacidade", config.privacyPolicyUrl))
        card.addView(row(outlined("Recusar todos", onRejectAll), filled("Aceitar todos", onAcceptAll)))
        card.addView(plain("Personalizar") { showPreferences() })
    }

    private fun showPreferences() {
        card.removeAllViews()
        card.addView(text("Preferências de privacidade", 18f, bold = true))
        card.addView(text("Escolha para que o aplicativo pode usar os seus dados.", 14f))
        val switches = LinkedHashMap<String, Switch>()
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (category in config.categories) {
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val toggle = Switch(context).apply {
                text = category.name + if (category.required) "  (sempre ativa)" else ""
                textSize = 15f
                setTextColor(fgColor)
                typeface = Typeface.DEFAULT_BOLD
                isChecked = category.required || (current[category.key] ?: category.defaultEnabled)
                isEnabled = !category.required
                // A cor do banner publicado, não a do tema do aparelho.
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = android.content.res.ColorStateList(states, intArrayOf(primary, Color.rgb(0xF5, 0xF5, 0xF4)))
                trackTintList = android.content.res.ColorStateList(states, intArrayOf(withAlpha(primary, 0x66), Color.rgb(0xD6, 0xD3, 0xD1)))
            }
            switches[category.key] = toggle
            item.addView(toggle)
            if (category.description.isNotBlank()) item.addView(text(category.description, 13f, muted = true))
            list.addView(item)
        }
        card.addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        card.addView(row(outlined("Recusar todos", onRejectAll), filled("Salvar preferências") { onSave(switches.mapValues { it.value.isChecked }) }))
        card.addView(plain(if (onClose != null) "Fechar" else "Voltar") { onClose?.invoke() ?: showFirstLayer() })
    }

    private fun text(value: String, size: Float, bold: Boolean = false, muted: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(if (muted) withAlpha(fgColor, 0xB0) else fgColor)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun link(label: String, url: String) = text(label, 14f).apply {
        setTextColor(primary)
        paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        setOnClickListener { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    }

    private fun filled(label: String, onClick: () -> Unit) = button(label, onClick).apply {
        setTextColor(Color.WHITE)
        background = shape(fill = primary, stroke = primary)
    }

    private fun outlined(label: String, onClick: () -> Unit) = button(label, onClick).apply {
        setTextColor(primary)
        background = shape(fill = Color.TRANSPARENT, stroke = primary)
    }

    private fun plain(label: String, onClick: () -> Unit) = button(label, onClick).apply {
        setTextColor(fgColor)
        background = null
    }

    private fun button(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun shape(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(2), stroke)
        cornerRadius = minOf(radius, dp(12).toFloat())
    }

    private fun row(vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(12), 0, 0)
        views.forEachIndexed { index, view ->
            addView(view, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (index > 0) marginStart = dp(8) })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        fun parse(hex: String, fallback: Int): Int = runCatching { Color.parseColor(hex) }.getOrDefault(fallback)
        fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
