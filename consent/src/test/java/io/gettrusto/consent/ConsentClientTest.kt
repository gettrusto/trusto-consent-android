package io.gettrusto.consent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class ConsentClientTest {
    private val key = "trusto_pk_live_" + "A".repeat(32)
    private val configJson = """
        {"version":3,"bannerPosition":"bottom",
         "bannerStyle":{"primaryColor":"#B45309","textColor":"#292524","backgroundColor":"#FFFFFF","borderRadius":12},
         "privacyPolicyUrl":"https://loja.example/privacidade",
         "categories":[
           {"key":"necessary","name":"Necessários","description":"","required":true,"defaultEnabled":true,"cookies":[]},
           {"key":"analytics","name":"Análise","description":"Métricas","required":false,"defaultEnabled":false,
            "cookies":[{"name":"_ga","provider":"x","purpose":"y","expiry":"1 ano","type":"http"}]},
           {"key":"marketing","name":"Marketing","description":"","required":false,"defaultEnabled":false,"cookies":[]}
         ],
         "consentExpiryDays":365,"blockBeforeConsent":true}
    """.trimIndent()

    private lateinit var server: FakeCollector
    private val requests = CopyOnWriteArrayList<FakeCollector.Request>()
    /** Respostas em ordem por rota; a última se repete. */
    private val responses = mutableMapOf<String, MutableList<Pair<Int, String>>>()
    private lateinit var dir: File
    private var now = 1_800_000_000_000L
    private val scheduled = mutableListOf<Long>()
    private val app = AppInfo("br.com.loja.app", "0.1.0", "Android 15", "Pixel 8")

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("trusto-consent-test").toFile()
        server = FakeCollector { request ->
            requests.add(request)
            val list = responses[request.path]
            when {
                list == null || list.isEmpty() -> 404 to """{"error":"not_found"}"""
                list.size == 1 -> list[0]
                else -> list.removeAt(0)
            }
        }
        respond("/v1/app/config", 200 to configJson)
        respond("/v1/app/records", 201 to """{"consentId":"c0ffee00-0000-4000-8000-000000000000","expiresAt":"2027-11-21T00:00:00.000Z"}""")
    }

    @After
    fun tearDown() {
        server.stop()
        dir.deleteRecursively()
    }

    private fun respond(path: String, vararg list: Pair<Int, String>) {
        responses[path] = list.toMutableList()
    }

    private val endpoint get() = "http://127.0.0.1:${server.port}"

    private fun client(storageDir: File = dir, transport: Transport = UrlConnectionTransport(2_000, 2_000)): ConsentClient =
        ConsentClient(
            key = key,
            endpoint = endpoint,
            app = app,
            transport = transport,
            storage = FileStorage(storageDir),
            clock = Clock { now },
            executors = Executors(work = { it.run() }, callbacks = { it.run() }, schedule = { delay, _ -> scheduled.add(delay) }),
        )

    private fun records() = requests.filter { it.path == "/v1/app/records" }

    @Test
    fun `chave e endereço são conferidos sem rede`() {
        for (bad in listOf("", "trusto_sk_live_" + "A".repeat(43), "trusto_pk_prod_" + "A".repeat(32), "trusto_pk_live_curta", key + "x")) {
            try {
                ConsentClient(bad, endpoint, app, UrlConnectionTransport(), FileStorage(dir), Clock { now }, Executors({ it.run() }, { it.run() }, { _, _ -> }))
                fail("aceitou $bad")
            } catch (e: ConsentException.InvalidKey) {
                // esperado
            }
        }
        assertTrue(Rules.isValidKey("trusto_pk_test_" + "a-_9".repeat(8)))
        try {
            Rules.normalizeEndpoint("http://consent.example")
            fail("aceitou http fora do aparelho")
        } catch (e: IllegalArgumentException) {
            // esperado
        }
        assertEquals("https://consent.example", Rules.normalizeEndpoint("https://consent.example/"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `config pela chave, com plataforma e pacote, e cache de 60 segundos`() {
        val sdk = client()
        val config = sdk.loadConfigBlocking()
        assertEquals(3, config.version)
        assertEquals(listOf("necessary", "analytics", "marketing"), config.categories.map { it.key })
        assertEquals("_ga", config.categories[1].cookies[0].name)
        val request = requests.single()
        assertEquals("GET", request.method)
        assertEquals("platform=android&app=br.com.loja.app", request.query)
        assertEquals("Bearer $key", request.headers["authorization"])
        assertEquals("TrustoConsentAndroid/0.1.0", request.headers["user-agent"])

        now += 59_000
        sdk.loadConfigBlocking()
        assertEquals(1, requests.size)
        now += 2_000
        sdk.loadConfigBlocking()
        assertEquals(2, requests.size)
    }

    @Test
    fun `sem rede, o último banner salvo vale, inclusive em outra instância`() {
        client().loadConfigBlocking()
        server.stop()
        now += 3_600_000
        val offline = client()
        assertEquals(3, offline.loadConfigBlocking().version)
    }

    @Test
    fun `recusas do coletor viram erros tipados`() {
        respond("/v1/app/config", 401 to """{"error":"invalid_app_key"}""")
        expect<ConsentException.KeyRejected> { client().loadConfigBlocking() }
        respond("/v1/app/config", 403 to """{"error":"app_mismatch"}""")
        expect<ConsentException.AppMismatch> { client().loadConfigBlocking() }
        respond("/v1/app/config", 503 to """{"error":"unavailable"}""")
        expect<ConsentException.Unavailable> { client().loadConfigBlocking() }
        expect<ConsentException.ConfigNotLoaded> { client().acceptAll() }
    }

    @Test
    fun `decisões seguem as regras do coletor`() {
        val config = client().loadConfigBlocking()
        assertEquals(ConsentAction.ACCEPT_ALL, Rules.decide(config, mapOf("analytics" to true, "marketing" to true)).first)
        assertEquals(ConsentAction.REJECT_ALL, Rules.decide(config, emptyMap()).first)
        val (action, categories) = Rules.decide(config, mapOf("analytics" to true))
        assertEquals(ConsentAction.PARTIAL, action)
        assertEquals(linkedMapOf("necessary" to true, "analytics" to true, "marketing" to false), categories)
        assertEquals(listOf("necessary", "analytics", "marketing"), categories.keys.toList())
        expect<ConsentException.InvalidChoice> { Rules.decide(config, mapOf("outra" to true)) }
        expect<ConsentException.InvalidChoice> { Rules.decide(config, mapOf("necessary" to false)) }
        assertFalse(Rules.check(config, ConsentAction.ACCEPT_ALL, categories))
        assertFalse(Rules.check(config, ConsentAction.PARTIAL, Rules.all(config, true)))
        assertFalse(Rules.check(config, ConsentAction.REVOKE, Rules.all(config, true)))
        assertTrue(Rules.check(config, ConsentAction.REVOKE, Rules.all(config, false)))
        assertFalse(Rules.check(config, ConsentAction.REJECT_ALL, mapOf("necessary" to true)))
    }

    @Test
    fun `registro tem exatamente o corpo que o coletor aceita, e o recibo volta à decisão`() {
        val sdk = client()
        val heard = mutableListOf<ConsentDecision?>()
        sdk.addListener { heard.add(it) }
        sdk.loadConfigBlocking()
        val made = sdk.save(mapOf("analytics" to true))
        assertEquals(ConsentAction.PARTIAL, made.action)

        val request = records().single()
        assertEquals("POST", request.method)
        assertEquals("Bearer $key", request.headers["authorization"])
        assertEquals("application/json", request.headers["content-type"])
        val body = JsonObject.parse(request.body).raw()
        assertEquals(listOf("visitorId", "action", "categories", "configVersion", "app"), body.keys.toList())
        assertTrue((body["visitorId"] as String).matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")))
        assertEquals("partial", body["action"])
        assertEquals(mapOf("necessary" to true, "analytics" to true, "marketing" to false), body["categories"])
        assertEquals(3L, body["configVersion"])
        assertEquals(
            mapOf("platform" to "android", "identifier" to "br.com.loja.app", "sdkVersion" to "0.1.0", "os" to "Android 15", "device" to "Pixel 8"),
            body["app"],
        )

        val decision = sdk.currentDecision!!
        assertEquals("c0ffee00-0000-4000-8000-000000000000", decision.consentId)
        assertNotNull(decision.expiresAtMs)
        assertNull(decision.pendingId)
        assertEquals(0, sdk.pendingCount)
        // Um aviso pela decisão local, outro pelo recibo.
        assertEquals(2, heard.size)
        assertTrue(sdk.isGranted("analytics"))
        assertFalse(sdk.isGranted("marketing"))

        // A decisão e o recibo sobrevivem à instância.
        val again = client()
        assertEquals("c0ffee00-0000-4000-8000-000000000000", again.currentDecision!!.consentId)
        assertEquals(sdk.visitorId, again.visitorId)
        assertFalse(again.needsConsent)
    }

    @Test
    fun `202 e erros terminais descartam, sem reenviar`() {
        respond("/v1/app/records", 202 to """{"discarded":true}""")
        val sdk = client()
        sdk.loadConfigBlocking()
        sdk.acceptAll()
        assertEquals(0, sdk.pendingCount)
        respond("/v1/app/records", 400 to """{"error":"categories_mismatch"}""")
        sdk.rejectAll()
        assertEquals(0, sdk.pendingCount)
        assertTrue(scheduled.isEmpty())
        // Sem recibo, a decisão local continua valendo.
        assertEquals(ConsentAction.REJECT_ALL, sdk.currentDecision!!.action)
        assertNull(sdk.currentDecision!!.consentId)
    }

    @Test
    fun `409 descarta, invalida o banner e pede a decisão de novo`() {
        val sdk = client()
        sdk.loadConfigBlocking()
        respond("/v1/app/records", 409 to """{"error":"config_outdated","version":4}""")
        sdk.acceptAll()
        assertEquals(0, sdk.pendingCount)
        assertTrue(sdk.needsConsent)
        // O cache invalidado busca de novo mesmo dentro dos 60 s.
        respond("/v1/app/config", 200 to configJson.replace("\"version\":3", "\"version\":4"))
        assertEquals(4, sdk.loadConfigBlocking().version)
        assertTrue(sdk.needsConsent)
        respond("/v1/app/records", 201 to """{"consentId":"c0ffee00-0000-4000-8000-000000000001","expiresAt":"2027-11-21T00:00:00.000Z"}""")
        sdk.acceptAll()
        assertFalse(sdk.needsConsent)
    }

    @Test
    fun `falha passageira fica na fila com backoff e vai quando o prazo vence`() {
        respond("/v1/app/records", 503 to """{"error":"unavailable"}""", 429 to """{"error":"rate_limited"}""", 201 to """{"consentId":"c0ffee00-0000-4000-8000-000000000002","expiresAt":"2027-11-21T00:00:00.000Z"}""")
        val sdk = client()
        sdk.loadConfigBlocking()
        sdk.acceptAll()
        assertEquals(1, sdk.pendingCount)
        assertEquals(listOf(5_000L), scheduled)

        sdk.flushBlocking()
        assertEquals(1, records().size)
        now += 5_000
        sdk.flushBlocking()
        assertEquals(2, records().size)
        assertEquals(1, sdk.pendingCount)
        now += 10_000
        sdk.flushBlocking()
        assertEquals(0, sdk.pendingCount)
        assertEquals("c0ffee00-0000-4000-8000-000000000002", sdk.currentDecision!!.consentId)
    }

    @Test
    fun `a fila sobrevive sem rede e sai na instância seguinte`() {
        val sdk = client()
        sdk.loadConfigBlocking()
        server.stop()
        sdk.acceptAll()
        assertEquals(1, sdk.pendingCount)
        // Sem rede, a decisão já vale.
        assertFalse(sdk.needsConsent)

        server = FakeCollector(port = server.port) { request ->
            requests.add(request)
            201 to """{"consentId":"c0ffee00-0000-4000-8000-000000000003","expiresAt":"2027-11-21T00:00:00.000Z"}"""
        }
        now += 60 * 60_000
        val next = client()
        assertEquals(1, next.pendingCount)
        next.flushBlocking()
        assertEquals(0, next.pendingCount)
        assertEquals("c0ffee00-0000-4000-8000-000000000003", next.currentDecision!!.consentId)
    }

    @Test
    fun `fila com teto de 50 itens e 30 dias`() {
        val sdk = client()
        sdk.loadConfigBlocking()
        server.stop()
        repeat(55) { sdk.acceptAll() }
        assertEquals(50, sdk.pendingCount)
        now += 31L * 86_400_000
        sdk.flushBlocking()
        assertEquals(0, sdk.pendingCount)
    }

    @Test
    fun `needsConsent sem decisão, com banner novo e com a decisão vencida`() {
        val sdk = client()
        assertTrue(sdk.needsConsent)
        sdk.loadConfigBlocking()
        assertTrue(sdk.needsConsent)
        sdk.rejectAll()
        assertFalse(sdk.needsConsent)
        // Recibo vence em 2027-11-21; o relógio de teste está em 2027-01-15.
        now = ConsentClient.parseInstant("2027-11-21T00:00:00.000Z")!!
        assertTrue(sdk.needsConsent)
    }

    @Test
    fun `sem recibo, o prazo é o do banner`() {
        respond("/v1/app/records", 202 to """{"discarded":true}""")
        val sdk = client()
        sdk.loadConfigBlocking()
        sdk.acceptAll()
        now += 364L * 86_400_000
        assertFalse(sdk.needsConsent)
        now += 2L * 86_400_000
        assertTrue(sdk.needsConsent)
    }

    @Test
    fun `sem decisão, só as obrigatórias estão concedidas`() {
        val sdk = client()
        sdk.loadConfigBlocking()
        assertTrue(sdk.isGranted("necessary"))
        assertFalse(sdk.isGranted("analytics"))
        assertFalse(sdk.isGranted("outra"))
    }

    @Test
    fun `sistema e modelo saem dentro do que o coletor aceita`() {
        assertEquals("Android 15 beta", Rules.sanitize("Android 15 <beta>", 40))
        assertEquals("Galaxy S24 Ultra", Rules.sanitize("Galaxy\tS24 ™ Ultra 🚀", 60))
        assertEquals("x".repeat(60), Rules.sanitize("x".repeat(80), 60))
        assertNull(Rules.sanitize("🚀🚀", 60))
        assertNull(Rules.sanitize(null, 60))

        val odd = ConsentClient(key, endpoint, AppInfo("br.com.loja.app", "0.1.0", "Android 15 \u0000", "🚀"), UrlConnectionTransport(), FileStorage(dir), Clock { now }, Executors({ it.run() }, { it.run() }, { _, _ -> }))
        odd.loadConfigBlocking()
        odd.acceptAll()
        val appBody = JsonObject.parse(records().last().body).obj("app").raw()
        assertEquals("Android 15", appBody["os"])
        assertFalse("modelo vazio não vai", appBody.containsKey("device"))
    }

    @Test
    fun `json de ida e volta`() {
        val value = linkedMapOf("a" to "x\"y\\z\n\u0001é", "b" to listOf(1L, 2.5, true, null), "c" to linkedMapOf("d" to false))
        assertEquals(value, Json.parse(Json.stringify(value)))
        expect<JsonException> { Json.parse("{\"a\":1} x") }
        expect<JsonException> { Json.parse("[" .repeat(100)) }
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try {
            block()
            fail("esperava ${T::class.java.simpleName}")
        } catch (e: Throwable) {
            if (e !is T) throw e
        }
    }
}
