package io.gettrusto.consent

/** O banner publicado do aplicativo, como o coletor devolve (`GET /v1/app/config`). */
public data class ConsentConfig(
    /** Versão do banner. Toda decisão leva a versão que a pessoa viu. */
    val version: Int,
    val bannerPosition: String,
    val bannerStyle: BannerStyle,
    val privacyPolicyUrl: String,
    val categories: List<ConsentCategory>,
    val consentExpiryDays: Int,
    val blockBeforeConsent: Boolean,
    /** Canal do titular da organização, quando publicado. */
    val holderUrl: String?,
) {
    internal fun toJson(): Map<String, Any?> = linkedMapOf(
        "version" to version,
        "bannerPosition" to bannerPosition,
        "bannerStyle" to linkedMapOf(
            "primaryColor" to bannerStyle.primaryColor,
            "textColor" to bannerStyle.textColor,
            "backgroundColor" to bannerStyle.backgroundColor,
            "borderRadius" to bannerStyle.borderRadius,
        ),
        "privacyPolicyUrl" to privacyPolicyUrl,
        "categories" to categories.map { it.toJson() },
        "consentExpiryDays" to consentExpiryDays,
        "blockBeforeConsent" to blockBeforeConsent,
        "holderUrl" to holderUrl,
    )

    internal companion object {
        fun fromJson(json: JsonObject): ConsentConfig {
            val style = json.obj("bannerStyle")
            return ConsentConfig(
                version = json.int("version"),
                bannerPosition = json.string("bannerPosition"),
                bannerStyle = BannerStyle(
                    primaryColor = style.string("primaryColor"),
                    textColor = style.string("textColor"),
                    backgroundColor = style.string("backgroundColor"),
                    borderRadius = style.int("borderRadius"),
                ),
                privacyPolicyUrl = json.string("privacyPolicyUrl"),
                categories = json.objects("categories").map(ConsentCategory::fromJson),
                consentExpiryDays = json.int("consentExpiryDays"),
                blockBeforeConsent = json.bool("blockBeforeConsent"),
                holderUrl = json.stringOrNull("holderUrl"),
            )
        }
    }
}

public data class BannerStyle(
    val primaryColor: String,
    val textColor: String,
    val backgroundColor: String,
    val borderRadius: Int,
)

public data class ConsentCategory(
    /** Chave da finalidade (`necessary`, `analytics`). É o que `isGranted` recebe. */
    val key: String,
    val name: String,
    val description: String,
    /** Obrigatória: sempre concedida, o banner diz que não pode ser desativada. */
    val required: Boolean,
    val defaultEnabled: Boolean,
    val cookies: List<ConsentCookie>,
) {
    internal fun toJson(): Map<String, Any?> = linkedMapOf(
        "key" to key,
        "name" to name,
        "description" to description,
        "required" to required,
        "defaultEnabled" to defaultEnabled,
        "cookies" to cookies.map {
            linkedMapOf("name" to it.name, "provider" to it.provider, "purpose" to it.purpose, "expiry" to it.expiry, "type" to it.type)
        },
    )

    internal companion object {
        fun fromJson(json: JsonObject): ConsentCategory = ConsentCategory(
            key = json.string("key"),
            name = json.string("name"),
            description = json.string("description"),
            required = json.bool("required"),
            defaultEnabled = json.bool("defaultEnabled"),
            cookies = json.objects("cookies").map {
                ConsentCookie(it.string("name"), it.string("provider"), it.string("purpose"), it.string("expiry"), it.string("type"))
            },
        )
    }
}

public data class ConsentCookie(
    val name: String,
    val provider: String,
    val purpose: String,
    val expiry: String,
    val type: String,
)

/** O que a pessoa decidiu. O valor de `wire` é o que vai ao coletor. */
public enum class ConsentAction(internal val wire: String) {
    ACCEPT_ALL("accept_all"),
    REJECT_ALL("reject_all"),
    PARTIAL("partial"),
    REVOKE("revoke"),
    ;

    internal companion object {
        fun fromWire(value: String): ConsentAction =
            entries.firstOrNull { it.wire == value } ?: throw JsonException("ação desconhecida: $value")
    }
}

/**
 * A decisão em vigor no aparelho. Vale localmente assim que é tomada; `consentId` e
 * `expiresAt` chegam quando o coletor confirma o registro.
 */
public data class ConsentDecision(
    val action: ConsentAction,
    /** Todas as categorias do banner, na ordem dele. */
    val categories: Map<String, Boolean>,
    val configVersion: Int,
    val decidedAtMs: Long,
    /** Prazo do consentimento quando ainda não há recibo: `decidedAt + consentExpiryDays`. */
    val localExpiresAtMs: Long,
    /** Recibo do coletor: o que o titular apresenta num pedido. */
    val consentId: String?,
    val expiresAtMs: Long?,
    internal val pendingId: String?,
) {
    /** Prazo que vale: o do recibo, senão o calculado no aparelho. */
    val effectiveExpiresAtMs: Long get() = expiresAtMs ?: localExpiresAtMs

    internal fun toJson(): Map<String, Any?> = linkedMapOf(
        "action" to action.wire,
        "categories" to LinkedHashMap(categories),
        "configVersion" to configVersion,
        "decidedAtMs" to decidedAtMs,
        "localExpiresAtMs" to localExpiresAtMs,
        "consentId" to consentId,
        "expiresAtMs" to expiresAtMs,
        "pendingId" to pendingId,
    )

    internal companion object {
        fun fromJson(json: JsonObject): ConsentDecision = ConsentDecision(
            action = ConsentAction.fromWire(json.string("action")),
            categories = json.obj("categories").raw().mapValues { it.value == true },
            configVersion = json.int("configVersion"),
            decidedAtMs = json.long("decidedAtMs"),
            localExpiresAtMs = json.long("localExpiresAtMs"),
            consentId = json.stringOrNull("consentId"),
            expiresAtMs = json.longOrNull("expiresAtMs"),
            pendingId = json.stringOrNull("pendingId"),
        )
    }
}

/** Falhas que o aplicativo pode tratar. */
public sealed class ConsentException(message: String) : Exception(message) {
    /** A chave não tem o formato `trusto_pk_{test|live}_…`. Erro de integração, conferido sem rede. */
    public class InvalidKey : ConsentException("chave publicável inválida")

    /** O coletor recusou a chave: retirada, de outro ambiente, ou o app ainda sem posse verificada. */
    public class KeyRejected : ConsentException("chave publicável recusada pelo coletor")

    /** A chave é de outro aplicativo ou de outra plataforma. */
    public class AppMismatch : ConsentException("a chave é de outro aplicativo")

    /** `configure` ainda não foi chamado. */
    public class NotConfigured : ConsentException("TrustoConsent.configure não foi chamado")

    /** A decisão pede o banner, e nenhum foi carregado ainda (`loadConfig`). */
    public class ConfigNotLoaded : ConsentException("banner não carregado: chame loadConfig antes")

    /** Categoria que o banner não tem, ou obrigatória recusada. */
    public class InvalidChoice(message: String) : ConsentException(message)

    /** Sem rede, coletor fora do ar ou limite de chamadas, e nenhuma cópia salva do banner. */
    public class Unavailable(message: String) : ConsentException(message)
}
