package com.cameralivepro.app.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

data class FoundReceiver(val name: String, val host: String, val port: Int)

object Discovery {
    /** Envia "CLP?" por broadcast UDP e coleta respostas "CLP1|nome|porta" por [timeoutMs]. Bloqueante. */
    fun search(timeoutMs: Int = 1500): List<FoundReceiver> {
        val found = LinkedHashMap<String, FoundReceiver>()
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                s.soTimeout = 300
                val msg = "CLP?".toByteArray(Charsets.US_ASCII)
                s.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), Ports.DISCOVERY))
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(256)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (e: SocketTimeoutException) { continue }
                    val parts = String(p.data, 0, p.length, Charsets.UTF_8).split('|')
                    val port = parts.getOrNull(2)?.toIntOrNull()
                    if (parts.size == 3 && parts[0] == "CLP1" && port != null && port in 1..65535) {
                        val host = p.address.hostAddress ?: continue
                        found[host] = FoundReceiver(parts[1].take(48), host, port)
                    }
                }
            }
        } catch (e: Exception) {
            // Sem Wi-Fi ou broadcast bloqueado: o usuário ainda pode digitar o IP manualmente.
        }
        return found.values.toList()
    }
}
