package com.skyler.scanon

import com.skyler.scanon.engine.DnsEngine
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class DnsWireTest {

    private fun response(id: Int, flags: Int, question: ByteArray, answers: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8); out.write(v and 0xFF) }
        u16(id); u16(flags); u16(1); u16(answers.size); u16(0); u16(0)
        out.write(question)
        answers.forEach { out.write(it) }
        return out.toByteArray()
    }

    private val qExample = byteArrayOf(7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
        'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0,
        0, 1, 0, 1)

    /** Answer RR with a compression pointer to the question name at offset 12. */
    private fun aRecord(a: Int, b: Int, c: Int, d: Int, ttl: Int = 300) =
        byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, (ttl shr 24).toByte(), (ttl shr 16).toByte(), (ttl shr 8).toByte(), ttl.toByte(),
            0, 4, a.toByte(), b.toByte(), c.toByte(), d.toByte())

    @Test fun buildQueryLayout() {
        val q = DnsEngine.buildQuery("example.com", "AAAA", 0x1234)
        assertEquals(0x12, q[0].toInt()); assertEquals(0x34, q[1].toInt())
        assertEquals(1, q[5].toInt())          // QDCOUNT
        assertEquals(1, q[11].toInt())         // ARCOUNT = OPT
        assertArrayEquals(qExample.copyOf(13), q.copyOfRange(12, 25))
        assertEquals(28, ((q[25].toInt() and 0xFF) shl 8) or (q[26].toInt() and 0xFF))
        // OPT pseudo-RR advertising 1232 bytes
        assertEquals(0, q[29].toInt())         // root name
        assertEquals(41, q[31].toInt())        // TYPE = OPT
        assertEquals(1232, ((q[32].toInt() and 0xFF) shl 8) or (q[33].toInt() and 0xFF))
    }

    @Test fun buildQueryRejectsBadNames() {
        assertThrows(IllegalArgumentException::class.java) { DnsEngine.buildQuery("a".repeat(64) + ".com", "A") }
        assertThrows(IllegalArgumentException::class.java) { DnsEngine.buildQuery("a..b", "A") }
        assertThrows(IllegalArgumentException::class.java) { DnsEngine.buildQuery("", "A") }
        assertThrows(IllegalArgumentException::class.java) { DnsEngine.buildQuery(("abcdefghi.".repeat(26)) + "com", "A") }
    }

    @Test fun parsesCompressedAnswers() {
        val data = response(0xBEEF, 0x8180, qExample, listOf(aRecord(93, 184, 216, 34), aRecord(1, 2, 3, 4, 60)))
        val r = DnsEngine.parseResponse(data, 0xBEEF)
        assertEquals("NOERROR", r.rcodeName)
        assertEquals(listOf("93.184.216.34", "1.2.3.4"), r.answers.map { it.data })
        assertEquals(60L, r.answers[1].ttl)
    }

    @Test fun rejectsWrongIdAndQueries() {
        val data = response(0xBEEF, 0x8180, qExample, listOf(aRecord(1, 1, 1, 1)))
        assertThrows(IllegalArgumentException::class.java) { DnsEngine.parseResponse(data, 0x1111) }
        val notResponse = response(0xBEEF, 0x0100, qExample, emptyList())
        assertThrows(IllegalArgumentException::class.java) { DnsEngine.parseResponse(notResponse, 0xBEEF) }
    }

    @Test fun rcodeAndFlags() {
        val r = DnsEngine.parseResponse(response(1, 0x8000 or 0x0200 or 0x0020 or 5, qExample, emptyList()), 1)
        assertEquals("REFUSED", r.rcodeName)
        assertTrue(r.truncated)
        assertTrue(r.dnssecAd)
        assertTrue(!DnsEngine.isUsefulRcode(r.rcode))
        assertTrue(DnsEngine.isUsefulRcode(3))
    }

    /** 2.3 looped forever on a compression pointer that points at itself. */
    @Test @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun compressionLoopTerminates() {
        val data = byteArrayOf(0, 1, 0x81.toByte(), 0x80.toByte(), 0, 0, 0, 1, 0, 0, 0, 0,
            0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 1, 0, 4, 1, 2, 3, 4)
        assertThrows(IllegalArgumentException::class.java) { DnsEngine.readName(data, 12) }
        // A whole response with that loop must not hang either: the bad answer is just dropped.
        val r = runCatching { DnsEngine.parseResponse(data) }
        assertTrue(r.isFailure || r.getOrNull()!!.answers.isEmpty())
    }

    @Test @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun truncatedPointerTerminates() {
        val data = ByteArray(13).also { it[12] = 0xC0.toByte() }
        assertThrows(IllegalArgumentException::class.java) { DnsEngine.readName(data, 12) }
    }

    @Test fun txtMxAndAaaa() {
        val txt = byteArrayOf(0xC0.toByte(), 12, 0, 16, 0, 1, 0, 0, 0, 10, 0, 6, 5, 'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte())
        val mx = byteArrayOf(0xC0.toByte(), 12, 0, 15, 0, 1, 0, 0, 0, 10, 0, 4, 0, 10, 0xC0.toByte(), 12)
        val aaaa = byteArrayOf(0xC0.toByte(), 12, 0, 28, 0, 1, 0, 0, 0, 10, 0, 16, 0x26, 0x06, 0x47, 0, 0x47, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x11, 0x11)
        val r = DnsEngine.parseResponse(response(9, 0x8180, qExample, listOf(txt, mx, aaaa)), 9)
        assertEquals("hello", r.answers[0].data)
        assertEquals("10 example.com", r.answers[1].data)
        assertEquals("2606:4700:4700::1111", r.answers[2].data)
    }

    @Test fun chunkedDohBody() {
        val body = "4\r\nabcd\r\n3\r\nefg\r\n0\r\n\r\n".toByteArray()
        val out = DnsEngine.readHttpBody(body.inputStream(), "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked", ByteArray(0))
        assertEquals("abcdefg", String(out))
        val cl = DnsEngine.readHttpBody("xyz-ignored".toByteArray().inputStream(), "Content-Length: 5", "ab".toByteArray())
        assertEquals("abxyz", String(cl))
        assertThrows(IllegalArgumentException::class.java) {
            DnsEngine.readHttpBody(ByteArray(0).inputStream(), "Content-Length: 99999999", ByteArray(0))
        }
    }
}
