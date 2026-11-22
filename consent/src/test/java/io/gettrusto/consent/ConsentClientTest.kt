package io.gettrusto.consent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    /** Sem rede, sem depender do tempo de parada do servidor: toda chamada falha. */
    private val offlineTransport = object : Transport {
        override fun get(url: String, headers: Map<String, String>): HttpResponse = throw java.io.IOException("sem rede")
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse = throw java.io.IOException("sem rede")
    }

    private fun client(
        storageDir: File = dir,
        transport: Transport = UrlConnectionTransport(2_000, 2_000),
        key: String = this.key,
        endpoint: String = this.endpoint,
    ): ConsentClient =
        ConsentClient(
            key = key,
            endpoint = endpoint,
            app = app,
            transport = transport,
            storage = FileStorage(storageDir),
            clock = Clock { now },
            executors = Executors(work = { it.run() }, callbacks = { it.run() }, schedule = { delay, _ -> scheduled.add(delay) }),
            allowLocalHttp = true,
        )

    private fun records() = requests.filter { it.path == "/v1/app/records" }

    @Test
    fun `chave e endereço são conferidos sem rede`() {
        for (bad in listOf("", "trusto_sk_live_" + "A".repeat(43), "trusto_pk_prod_" + "A".repeat(32), "trusto_pk_live_curta", key + "x")) {
            try {
                ConsentClient(bad, endpoint, app, UrlConnectionTransport(), FileStorage(dir), Clock { now }, Executors({ it.run() }, { it.run() }, { _, _ -> }), allowLocalHttp = true)
                fail("aceitou $bad")
            } catch (e: ConsentException.InvalidKey) {
                // esperado
            }
        }
        assertTrue(Rules.isValidKey("trusto_pk_test_" + "a-_9".repeat(8)))
        expect<ConsentException.InvalidEndpoint> { Rules.normalizeEndpoint("http://consent.example", allowLocalHttp = true) }
        // http no aparelho ou no emulador só em app debuggable.
        for (local in listOf("http://127.0.0.1:8080", "http://localhost", "http://10.0.2.2:8080")) {
            expect<ConsentException.InvalidEndpoint> { Rules.normalizeEndpoint(local, allowLocalHttp = false) }
            assertEquals(local, Rules.normalizeEndpoint(local, allowLocalHttp = true))
        }
        expect<ConsentException.InvalidEndpoint> { Rules.normalizeEndpoint("https://u:p@consent.example", allowLocalHttp = false) }
        assertEquals("https://consent.example", Rules.normalizeEndpoint("https://consent.example/", allowLocalHttp = false))
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
        // O aviso de quando o listener entrou (sem decisão), um pela decisão local, outro pelo recibo.
        assertEquals(3, heard.size)
        assertNull(heard[0])
        assertTrue(sdk.isGranted("analytics"))
        assertFalse(sdk.isGranted("marketing"))

        // A decisão e o recibo sobrevivem à instância.
        val again = client()
        assertEquals("c0ffee00-0000-4000-8000-000000000000", again.currentDecision!!.consentId)
        assertEquals(sdk.visitorId, again.visitorId)
        assertFalse(again.needsConsent)
    }

    @Test
    fun `202, 400, 403 e 413 descartam, sem reenviar`() {
        respond("/v1/app/records", 202 to """{"discarded":true}""")
        val sdk = client()
        sdk.loadConfigBlocking()
        sdk.acceptAll()
        assertEquals(0, sdk.pendingCount)
        for ((status, body) in listOf(400 to """{"error":"categories_mismatch"}""", 403 to """{"error":"app_mismatch"}""", 413 to """{"error":"payload_too_large"}""")) {
            respond("/v1/app/records", status to body)
            sdk.rejectAll()
            assertEquals("$status descarta", 0, sdk.pendingCount)
        }
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
    fun `trocar o ambiente da chave descarta banner, decisão e fila do outro`() {
        client().loadConfigBlocking()
        val sdk = client(transport = offlineTransport)
        sdk.acceptAll()
        assertEquals(1, sdk.pendingCount)
        sdk.close()

        val test = client(key = "trusto_pk_test_" + "B".repeat(32))
        assertEquals(0, test.pendingCount)
        assertNull(test.currentDecision)
        assertNull(test.lastConfig)
        assertTrue(test.needsConsent)
    }

    @Test
    fun `trocar o coletor descarta só o banner em cache, e a fila segue para o novo`() {
        client().loadConfigBlocking()
        val sdk = client(transport = offlineTransport)
        sdk.acceptAll()
        sdk.close()

        val other = client(endpoint = "https://consent.example")
        assertNull(other.lastConfig)
        assertEquals(1, other.pendingCount)
        assertNotNull(other.currentDecision)
    }

    @Test
    fun `maiúsculas no host não mudam o coletor`() {
        assertEquals(ConsentClient.collectorOf("https://consent.example/x"), ConsentClient.collectorOf("HTTPS://Consent.Example/x/"))
    }

    @Test
    fun `instância encerrada não regrava a fila depois da troca de ambiente`() {
        val pending = ArrayDeque<Runnable>()
        val manual = Executors(work = { pending.add(it) }, callbacks = { it.run() }, schedule = { _, _ -> })
        fun make(k: String) = ConsentClient(k, endpoint, app, UrlConnectionTransport(2_000, 2_000), FileStorage(dir), Clock { now }, manual, allowLocalHttp = true)
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }

        respond("/v1/app/records", 503 to "{}")
        val testKey = "trusto_pk_test_" + "B".repeat(32)
        val a = make(key)
        a.loadConfigBlocking()
        drain()
        // A nova entra com a limpeza na fila; a anterior ainda grava uma decisão depois dela.
        val b = make(testKey)
        a.acceptAll()
        a.close()
        drain()
        assertEquals(0, b.pendingCount)

        val c = make(testKey)
        assertEquals(0, c.pendingCount)
        assertNull(c.currentDecision)
        assertNull(c.lastConfig)
    }

    @Test
    fun `rotação da chave no mesmo ambiente mantém a fila, enviada com a chave nova`() {
        client().loadConfigBlocking()
        // Sem rede: a decisão fica na fila.
        val offline = client(transport = offlineTransport)
        offline.acceptAll()
        assertEquals(1, offline.pendingCount)
        offline.close()

        val rotated = "trusto_pk_live_" + "C".repeat(32)
        now += 60 * 60_000
        val next = client(key = rotated)
        assertEquals(1, next.pendingCount)
        assertNotNull(next.currentDecision)
        next.flushBlocking()
        assertEquals(0, next.pendingCount)
        assertEquals("c0ffee00-0000-4000-8000-000000000000", next.currentDecision!!.consentId)
        assertEquals("Bearer $rotated", requests.last().headers["authorization"])
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
    fun `banner novo desliga as opcionais até a pessoa decidir de novo`() {
        val sdk = client()
        sdk.loadConfigBlocking()
        sdk.acceptAll()
        assertTrue(sdk.isGranted("analytics"))

        // O banner é republicado na versão 4: a decisão sobre a 3 não libera mais as opcionais.
        respond("/v1/app/config", 200 to configJson.replace("\"version\":3", "\"version\":4"))
        now += 61_000
        sdk.loadConfigBlocking()
        assertTrue(sdk.needsConsent)
        assertFalse(sdk.isGranted("analytics"))
        assertTrue(sdk.isGranted("necessary"))

        sdk.acceptAll()
        assertTrue(sdk.isGranted("analytics"))
    }

    @Test
    fun `409 com versão nova desliga as opcionais antes de recarregar o banner`() {
        val sdk = client()
        sdk.loadConfigBlocking()
        respond("/v1/app/records", 409 to """{"error":"config_outdated","version":5}""")
        sdk.acceptAll()
        assertFalse(sdk.isGranted("analytics"))
    }

    @Test
    fun `decisão vencida concede só as obrigatórias`() {
        respond("/v1/app/records", 202 to """{"discarded":true}""")
        val sdk = client()
        sdk.loadConfigBlocking()
        sdk.acceptAll()
        assertTrue(sdk.isGranted("analytics"))
        now += 366L * 86_400_000
        assertFalse(sdk.isGranted("analytics"))
        assertTrue(sdk.isGranted("necessary"))
    }

    @Test
    fun `401 e 404 no envio esperam no mínimo 1 hora e o item fica na fila`() {
        for (status in listOf(401, 404)) {
            scheduled.clear()
            respond("/v1/app/records", status to """{"error":"x"}""")
            val sdk = client(Files.createTempDirectory("trusto-$status").toFile())
            sdk.loadConfigBlocking()
            sdk.acceptAll()
            assertEquals("$status fica na fila", 1, sdk.pendingCount)
            assertEquals(listOf(ConsentClient.LONG_RETRY_MS), scheduled)
            // Dentro do teto de 30 dias, a chave voltou e a prova sai.
            respond("/v1/app/records", 201 to """{"consentId":"c0ffee00-0000-4000-8000-000000000010","expiresAt":"2027-11-21T00:00:00.000Z"}""")
            now += ConsentClient.LONG_RETRY_MS
            sdk.flushBlocking()
            assertEquals(0, sdk.pendingCount)
        }
    }

    @Test
    fun `401 e 404 seguidos saem da fila no teto de 30 dias`() {
        respond("/v1/app/records", 401 to """{"error":"invalid_app_key"}""")
        val sdk = client()
        sdk.loadConfigBlocking()
        sdk.acceptAll()
        now += 31L * 86_400_000
        sdk.flushBlocking()
        assertEquals(0, sdk.pendingCount)
    }

    @Test
    fun `listener recebe a decisão atual na hora`() {
        val sdk = client()
        val seen = mutableListOf<ConsentDecision?>()
        sdk.addListener { seen.add(it) }
        assertEquals(listOf<ConsentDecision?>(null), seen)
        sdk.loadConfigBlocking()
        sdk.acceptAll()
        val again = mutableListOf<ConsentDecision?>()
        sdk.addListener { again.add(it) }
        assertEquals(ConsentAction.ACCEPT_ALL, again.first()!!.action)
    }

    @Test
    fun `depois de 409, decidir sem recarregar o banner é recusado`() {
        val sdk = client()
        sdk.loadConfigBlocking()
        respond("/v1/app/records", 409 to """{"error":"config_outdated","version":4}""")
        sdk.acceptAll()
        expect<ConsentException.ConfigNotLoaded> { sdk.rejectAll() }
        expect<ConsentException.ConfigNotLoaded> { sdk.save(mapOf("analytics" to true)) }
    }

    @Test
    fun `falha de disco não sai da API e a decisão vale em memória`() {
        val broken = object : Storage {
            override fun read(name: String): String? = throw java.io.IOException("disco")
            override fun write(name: String, value: String) = throw java.io.IOException("disco cheio")
            override fun delete(name: String) = throw java.io.IOException("disco")
        }
        val sdk = ConsentClient(key, endpoint, app, UrlConnectionTransport(2_000, 2_000), broken, Clock { now },
            Executors({ it.run() }, { it.run() }, { delay, _ -> scheduled.add(delay) }), allowLocalHttp = true)
        sdk.loadConfigBlocking()
        val first = sdk.visitorId
        val decision = sdk.acceptAll()
        assertEquals(ConsentAction.ACCEPT_ALL, sdk.currentDecision!!.action)
        assertEquals(decision.configVersion, 3)
        assertEquals("o identificador não muda sem disco", first, sdk.visitorId)
        assertEquals(first, JsonObject.parse(records().last().body).string("visitorId"))
    }

    @Test
    fun `arquivo corrompido ou de versão desconhecida é descartado sem derrubar`() {
        val sdk = client()
        sdk.loadConfigBlocking()
        sdk.acceptAll()
        File(dir, "decision.json").writeText("{corrompido")
        File(dir, "queue.json").writeText("""{"v":99,"data":[]}""")
        File(dir, "config.json").writeText("""{"v":2,"data":{}}""")
        val next = client()
        assertNull(next.currentDecision)
        assertEquals(0, next.pendingCount)
        assertNull(next.lastConfig)
        assertTrue(next.needsConsent)
    }

    @Test
    fun `arquivos persistidos levam a versão de formato`() {
        val sdk = client()
        sdk.loadConfigBlocking()
        sdk.acceptAll()
        for (name in listOf("config", "decision", "queue")) {
            val root = Json.parse(File(dir, "$name.json").readText()) as Map<*, *>
            assertEquals("$name com v", 1L, (root["v"] as Number).toLong())
        }
    }

    @Test
    fun `FileStorage grava por troca de nome e não deixa temporário`() {
        val sub = File(dir, "sub")
        val storage = FileStorage(sub)
        assertFalse("construir não cria nem falha", sub.exists())
        storage.write("decision", "um")
        storage.write("decision", "dois")
        assertEquals("dois", storage.read("decision"))
        assertFalse(File(sub, "decision.tmp").exists())
        storage.delete("decision")
        assertNull(storage.read("decision"))
        expect<IllegalArgumentException> { storage.write("../fora", "x") }
    }

    @Test
    fun `configure repetido reaproveita a instância ou encerra a anterior`() {
        val slot = ClientSlot()
        var created = 0
        val make = { k: String -> { created += 1; ConsentClient(k, endpoint, app, UrlConnectionTransport(2_000, 2_000), FileStorage(dir), Clock { now },
            Executors({ it.run() }, { it.run() }, { delay, _ -> scheduled.add(delay) }), allowLocalHttp = true) } }
        val first = slot.obtain(key, endpoint, true, make(key))
        assertSame(first, slot.obtain(key, "$endpoint/", true, make(key)))
        assertEquals(1, created)
        val otherKey = "trusto_pk_live_" + "B".repeat(32)
        val second = slot.obtain(otherKey, endpoint, true, make(otherKey))
        assertNotSame(first, second)
        // A anterior encerrada não envia mais nada.
        first.loadConfigBlocking()
        val before = records().size
        first.acceptAll()
        first.flushBlocking()
        assertEquals(before, records().size)
    }

    @Test
    fun `sistema e modelo saem dentro do que o coletor aceita`() {
        assertEquals("Android 15 beta", Rules.sanitize("Android 15 <beta>", 40))
        assertEquals("Galaxy S24 Ultra", Rules.sanitize("Galaxy\tS24 ™ Ultra 🚀", 60))
        assertEquals("x".repeat(60), Rules.sanitize("x".repeat(80), 60))
        assertNull(Rules.sanitize("🚀🚀", 60))
        assertNull(Rules.sanitize(null, 60))

        val odd = ConsentClient(key, endpoint, AppInfo("br.com.loja.app", "0.1.0", "Android 15 \u0000", "🚀"), UrlConnectionTransport(), FileStorage(dir), Clock { now }, Executors({ it.run() }, { it.run() }, { _, _ -> }), allowLocalHttp = true)
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
