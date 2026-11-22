package io.gettrusto.consent.sample

import android.app.Application
import android.content.Context
import io.gettrusto.consent.TrustoConsent

/** Configura o SDK no `Application`, como o README pede, com o coletor escolhido na tela. */
class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TrustoConsent.configure(this, BuildConfig.TRUSTO_KEY, currentEndpoint(this))
    }

    companion object {
        private const val PREFS = "sample"
        private const val USE_LOCAL = "use_local"

        fun usesLocal(context: Context): Boolean =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(USE_LOCAL, false)

        fun currentEndpoint(context: Context): String =
            if (usesLocal(context) || BuildConfig.TRUSTO_ENDPOINT.isEmpty()) BuildConfig.LOCAL_ENDPOINT else BuildConfig.TRUSTO_ENDPOINT

        /** Troca entre o coletor configurado e o falso da máquina local. */
        fun toggleEndpoint(context: Context): String {
            val next = !usesLocal(context)
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(USE_LOCAL, next).apply()
            val endpoint = currentEndpoint(context)
            TrustoConsent.configure(context, BuildConfig.TRUSTO_KEY, endpoint)
            return endpoint
        }
    }
}
