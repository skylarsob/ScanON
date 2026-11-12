package com.skyler.scanon

import com.skyler.scanon.engine.DnsAnswer
import com.skyler.scanon.engine.DnsCatalog
import com.skyler.scanon.engine.DnsEngine
import com.skyler.scanon.engine.DnsReport
import com.skyler.scanon.engine.DnsResponse
import com.skyler.scanon.engine.Exporters
import com.skyler.scanon.engine.ExportFormat
import com.skyler.scanon.engine.Fmt
import com.skyler.scanon.engine.MmdbReader
import com.skyler.scanon.engine.NetEngine
import com.skyler.scanon.engine.ScanConfig
import com.skyler.scanon.engine.Stats
import com.skyler.scanon.engine.TargetReport
import com.skyler.scanon.engine.TestResult
import com.skyler.scanon.ui.vm.IpScanViewModel
import com.skyler.scanon.ui.vm.ResultFilter
import com.skyler.scanon.ui.vm.SortKey
import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale

class MmdbAndStatsTest {

    private fun open(name: String): MmdbReader {
        val f = File("src/main/assets/$name")
        RandomAccessFile(f, "r").use { raf ->
            return MmdbReader.open(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()))
        }
    }

    @Test fun asnLookups() {
        val db = open("GeoLite2-ASN.mmdb")
        assertEquals("GeoLite2-ASN", db.databaseType)
        val cf = db.lookup("1.1.1.1")
        assertEquals(13335L, (cf?.get("autonomous_system_number") as Number).toLong())
        val google = db.lookup("8.8.8.8")
        assertEquals(15169L, (google?.get("autonomous_system_number") as Number).toLong())
        val v6 = db.lookup("2606:4700:4700::1111")
        assertEquals(13335L, (v6?.get("autonomous_system_number") as Number).toLong())
        assertNull(db.lookup("10.0.0.1"))       // private space has no record
        assertNull(db.lookup("not-an-ip"))
    }

    @Test fun cityLookup() {
        val db = open("GeoLite2-City.mmdb")
        val rec = db.lookup("8.8.8.8")
        assertNotNull(rec)
        val country = (rec!!["country"] ?: rec["registered_country"]) as Map<*, *>
        assertEquals("US", country["iso_code"])
    }

    @Test fun corruptDatabaseFailsCleanly() {
        assertThrows(MmdbReader.MmdbException::class.java) { MmdbReader.open(ByteBuffer.wrap(ByteArray(4096))) }
        // A real file cut in half: the metadata (at the end) is gone.
        val f = File("src/main/assets/GeoLite2-ASN.mmdb").readBytes()
        assertThrows(MmdbReader.MmdbException::class.java) { MmdbReader.open(ByteBuffer.wrap(f.copyOf(f.size / 2))) }
    }

    @Test fun stats() {
        assertEquals(2.5, Stats.median(listOf(4.0, 1.0, 2.0, 3.0)))
        assertEquals(3.0, Stats.median(listOf(5.0, 1.0, 3.0)))
        assertNull(Stats.median(emptyList()))
        assertEquals(9.0, Stats.percentile((1..10).map { it.toDouble() }, 0.9))
        assertEquals(0.0, Stats.stddev(listOf(5.0)))
        assertEquals(2.0, Stats.stddev(listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0))!!, 1e-9)
        assertEquals(listOf(2.0, 3.0, 4.0), Stats.dropWarmup(listOf(100.0, 2.0, 3.0, 4.0)))
        assertEquals(listOf(100.0, 2.0, 3.0), Stats.dropWarmup(listOf(100.0, 2.0, 3.0)))
    }

    @Test fun formattingIgnoresLocale() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("fa-IR"))
            assertEquals("12.3", Fmt.num(12.34))
            assertEquals("50%", Fmt.pct(50.0))
        } finally { Locale.setDefault(old) }
    }

    @Test fun aggregateAndScore() {
        val results = listOf(
            TestResult(true, connectMs = 10.0, tlsMs = 20.0, ttfbMs = 5.0, totalMs = 400.0),   // warm-up, dropped
            TestResult(true, connectMs = 12.0, tlsMs = 20.0, ttfbMs = 5.0, totalMs = 100.0),
            TestResult(true, connectMs = 11.0, tlsMs = 20.0, ttfbMs = 5.0, totalMs = 110.0),
            TestResult(true, connectMs = 11.0, tlsMs = 20.0, ttfbMs = 5.0, totalMs = 120.0),
            TestResult(false, failure = "timeout", detail = "x"),
        )
        val r = NetEngine.aggregate("1.1.1.1", "https", results)
        assertEquals(5, r.tests); assertEquals(4, r.successes)
        assertEquals(110.0, r.totalMedian)
        assertEquals(listOf(100.0, 110.0, 120.0), r.totalSamples)
        assertEquals(mapOf("timeout" to 1), r.failures)
        assertEquals(20.0, r.loss, 1e-9)
        assertEquals("partial", r.reliability)
        assertTrue(r.score() > 0)
    }

    @Test fun icmpAggregateUsesPingCounts() {
        val r = NetEngine.aggregate("1.1.1.1", "icmp", listOf(TestResult(true, connectMs = 20.0, icmpSent = 5, icmpReceived = 4)))
        assertEquals(5, r.tests); assertEquals(4, r.successes)
        assertEquals(20.0, r.lossPct!!, 1e-9)
    }

    @Test fun configNormalization() {
        val c = ScanConfig(methods = listOf("bogus"), ports = listOf(0, 70000), timeoutMs = 1, attempts = 99, workers = 0).normalized()
        assertEquals(listOf("tcp"), c.methods)
        assertEquals(listOf(443), c.ports)
        assertEquals(300, c.timeoutMs); assertEquals(20, c.attempts); assertEquals(1, c.workers)
        // tcp on 3 ports + icmp once per IP
        assertEquals(4L, NetEngine.workCount(1, ScanConfig(methods = listOf("tcp", "icmp"), ports = listOf(443, 8443, 2053))))
    }

    @Test fun readHeadParsesStatus() {
        val h = NetEngine.readHead("HTTP/1.1 301 Moved\r\nLocation: x\r\n\r\nbody".byteInputStream(), 0.0)
        assertEquals(301, h.status)
        val bad = NetEngine.readHead("garbage".byteInputStream(), 0.0)
        assertNull(bad.status)
        val h2 = NetEngine.readHead("HTTP/2 200\r\n\r\n".byteInputStream(), 0.0)
        assertEquals(200, h2.status)
    }

    @Test fun csvEscapingAndInjection() {
        assertEquals("\"a,b\"", Exporters.csvField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Exporters.csvField("say \"hi\""))
        assertEquals("'=1+1", Exporters.csvField("=1+1"))
        assertEquals("-5", Exporters.csvField("-5"))
    }

    @Test fun jsonExportIsValid() {
        val reps = listOf(TargetReport("1.1.1.1", "https", tests = 3, successes = 3, totalMedian = 50.0, totalSamples = listOf(50.0, 51.0)))
        val arr = JSONArray(Exporters.ips(reps, ExportFormat.JSON))
        assertEquals("1.1.1.1", arr.getJSONObject(0).getString("ip"))
        val csv = Exporters.ips(reps, ExportFormat.CSV).lines()
        assertTrue(csv[0].startsWith("ip,port,method"))
        assertTrue(csv[1].startsWith("1.1.1.1,443,https"))
    }

    @Test fun filterAndSort() {
        val list = listOf(
            TargetReport("9.9.9.9", "https", tests = 2, successes = 2, totalMedian = 300.0),
            TargetReport("1.1.1.1", "https", tests = 2, successes = 2, totalMedian = 50.0),
            TargetReport("8.8.8.8", "https", tests = 2, successes = 0),
        )
        val byLatency = IpScanViewModel.applyFilter(list, ResultFilter(sort = SortKey.LATENCY))
        assertEquals(listOf("1.1.1.1", "9.9.9.9", "8.8.8.8"), byLatency.map { it.ip })
        val working = IpScanViewModel.applyFilter(list, ResultFilter(workingOnly = true, maxLatencyMs = 100))
        assertEquals(listOf("1.1.1.1"), working.map { it.ip })
        val byIp = IpScanViewModel.applyFilter(list, ResultFilter(sort = SortKey.IP))
        assertEquals(listOf("1.1.1.1", "8.8.8.8", "9.9.9.9"), byIp.map { it.ip })
        val q = IpScanViewModel.applyFilter(list, ResultFilter(query = "9.9"))
        assertEquals(listOf("9.9.9.9"), q.map { it.ip })
    }

    @Test fun madJitter() {
        // MAD: the typical spread, immune to one wild run (stddev of this set is ~316).
        assertEquals(10.0, Stats.mad(listOf(90.0, 100.0, 110.0, 1000.0))!!, 0.001)
        assertEquals(0.0, Stats.mad(listOf(42.0))!!, 0.001)
        assertNull(Stats.mad(emptyList()))
    }

    @Test fun hijackClassification() {
        fun resp(rcode: Int, vararg answers: DnsAnswer) =
            DnsResponse(1, rcode, "", answers.toList(), dnssecAd = false, truncated = false)
        assertTrue(DnsEngine.isHijackResponse(resp(0, DnsAnswer("A", "203.0.113.7", 60))))
        assertTrue(!DnsEngine.isHijackResponse(resp(3)))
        assertTrue(!DnsEngine.isHijackResponse(resp(0, DnsAnswer("TXT", "hello", 60))))
        assertTrue(!DnsEngine.isHijackResponse(null))
    }

    @Test fun mergeReportsPoolsSamples() {
        val a = DnsReport(server = "1.1.1.1", protocol = "udp", queries = 3, successes = 3,
            samples = listOf(10.0, 20.0, 30.0), medianMs = 20.0)
        val b = DnsReport(server = "1.1.1.1", protocol = "udp", queries = 2, successes = 1, timeouts = 1,
            samples = listOf(40.0, 50.0), medianMs = 45.0, hijack = true)
        val m = DnsCatalog.mergeReports(a, b)
        assertEquals(5, m.queries)
        assertEquals(4, m.successes)
        assertEquals(30.0, m.medianMs!!, 0.001)
        assertEquals(listOf(10.0, 20.0, 30.0, 40.0, 50.0), m.samples)
        assertTrue(m.hijack)
    }
}
