package io.gettrusto.consent

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * Coletor falso para os testes: HTTP/1.1 mínimo sobre `ServerSocket`, uma requisição por
 * conexão (`Connection: close`). O `com.sun.net.httpserver` do JDK não fica visível nos testes
 * de um módulo Android, e o transporte real (`HttpURLConnection`) é o que se quer exercitar.
 */
class FakeCollector(port: Int = 0, private val handler: (Request) -> Pair<Int, String>) {
    class Request(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: String)

    private val socket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))

    /**
     * Parado, nenhuma conexão recebe resposta. Fechar o `ServerSocket` não basta: no Linux do
     * CI, uma conexão já aceita ou na fila do sistema ainda era atendida, e o teste "sem rede"
     * via o registro aceito.
     */
    @Volatile
    private var stopped = false
    val port: Int get() = socket.localPort

    init {
        thread(isDaemon = true, name = "fake-collector") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: SocketException) {
                    break
                }
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    fun stop() {
        stopped = true
        socket.close()
    }

    private fun serve(client: Socket) = client.use {
        if (stopped) return
        val input = BufferedInputStream(it.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val (method, target) = requestLine.split(" ").let { parts -> parts[0] to parts[1] }
        val path = target.substringBefore('?')
        val query = if ('?' in target) target.substringAfter('?') else null
        val (status, text) = handler(Request(method, path, query, headers, String(body, 0, read, Charsets.UTF_8)))
        if (stopped) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        val out = it.getOutputStream()
        out.write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (b == '\n'.code) return buffer.toString("UTF-8").trimEnd('\r')
            buffer.write(b)
        }
    }
}
