package io.gettrusto.consent

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor

/** Uma resposta HTTP já lida. */
internal class HttpResponse(val status: Int, val body: String)

/** O transporte até o coletor. Injetável: os testes usam um servidor local. */
internal interface Transport {
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): HttpResponse

    @Throws(IOException::class)
    fun post(url: String, headers: Map<String, String>, body: String): HttpResponse
}

/** `HttpURLConnection` da plataforma, com prazos curtos e corpo limitado. */
internal class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) : Transport {
    override fun get(url: String, headers: Map<String, String>): HttpResponse = request("GET", url, headers, null)

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
        request("POST", url, headers, body.toByteArray(Charsets.UTF_8))

    private fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.use { input ->
                val bytes = input.readNBytesCompat(MAX_RESPONSE_BYTES)
                String(bytes, Charsets.UTF_8)
            } ?: ""
            return HttpResponse(status, text)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        /** O maior banner publicado cabe com folga; acima disto a resposta não é do coletor. */
        const val MAX_RESPONSE_BYTES = 256 * 1024
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw IOException("resposta acima de $limit bytes")
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/** Onde o SDK guarda o que precisa entre execuções. Chave e valor em texto. */
internal interface Storage {
    fun read(name: String): String?
    fun write(name: String, value: String)
    fun delete(name: String)
}

/**
 * Um arquivo por nome, numa pasta só do SDK. No Android a pasta fica em `noBackupFilesDir`:
 * privada do aplicativo e fora do backup, então o identificador do visitante não vai para
 * outro aparelho. Escrita atômica por arquivo temporário e troca de nome. A pasta é criada
 * na primeira gravação: construir nunca falha.
 */
internal class FileStorage(private val dir: File) : Storage {
    override fun read(name: String): String? = file(name).takeIf { it.isFile }?.readText(Charsets.UTF_8)

    override fun write(name: String, value: String) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("pasta do SDK não criada")
        val target = file(name)
        val temp = File(dir, "$name.tmp")
        temp.writeText(value, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IOException("não foi possível gravar $name")
            }
        }
    }

    override fun delete(name: String) {
        file(name).delete()
    }

    private fun file(name: String): File {
        require(name.matches(Regex("[a-z_]+"))) { "nome inválido" }
        return File(dir, "$name.json")
    }
}

/**
 * O armazenamento como o núcleo o usa: nenhuma falha de disco (cheio, sem permissão, erro de
 * I/O) sai daqui e derruba o aplicativo. Leitura que falha conta como ausente; gravação que
 * falha deixa o estado só em memória até a próxima gravação.
 */
internal class SafeStorage(private val inner: Storage) : Storage {
    override fun read(name: String): String? = try {
        inner.read(name)
    } catch (e: Exception) {
        null
    }

    override fun write(name: String, value: String) {
        try {
            inner.write(name, value)
        } catch (e: Exception) {
            // Sem disco, a decisão segue em memória; a próxima gravação tenta de novo.
        }
    }

    override fun delete(name: String) {
        try {
            inner.delete(name)
        } catch (e: Exception) {
            // Idem.
        }
    }
}

/** Relógio injetável, para os testes de prazo. */
internal fun interface Clock {
    fun nowMs(): Long
}

/** Onde rodam o trabalho de rede e os avisos ao aplicativo. */
internal class Executors(
    /** Fila única de trabalho: rede e disco, sempre fora da thread principal no Android. */
    val work: Executor,
    /** Onde os listeners do aplicativo são chamados (thread principal no Android). */
    val callbacks: Executor,
    /** Agenda um reenvio. */
    val schedule: (delayMs: Long, task: Runnable) -> Unit,
)
