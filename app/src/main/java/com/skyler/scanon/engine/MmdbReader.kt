package com.skyler.scanon.engine

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal MaxMind DB (MMDB v2) reader over a read-only buffer (memory-mapped asset or file).
 *
 * Every read is bounds-checked and pointer/container recursion is depth-limited, so a corrupt or truncated
 * database produces [MmdbException] (caught by [GeoDb]) instead of an IndexOutOfBounds crash in the UI.
 * Only absolute reads are used, so one reader can serve many threads at once.
 */
class MmdbReader private constructor(private val buf: ByteBuffer) {

    class MmdbException(msg: String) : RuntimeException(msg)

    val databaseType: String
    val nodeCount: Int
    val recordSize: Int
    val ipVersion: Int
    private val nodeByteSize: Int
    private val searchTreeSize: Long
    private val dataBase: Long
    private val ipv4Start: Int

    init {
        buf.order(ByteOrder.BIG_ENDIAN)
        val meta = findMetadata()
        databaseType = meta["database_type"] as? String ?: "unknown"
        nodeCount = (meta["node_count"] as? Number)?.toInt() ?: throw MmdbException("metadata without node_count")
        recordSize = (meta["record_size"] as? Number)?.toInt() ?: 28
        ipVersion = (meta["ip_version"] as? Number)?.toInt() ?: 6
        if (recordSize !in setOf(24, 28, 32)) throw MmdbException("unsupported record size $recordSize")
        nodeByteSize = recordSize / 4
        searchTreeSize = nodeCount.toLong() * nodeByteSize
        dataBase = searchTreeSize + 16
        if (dataBase > buf.capacity()) throw MmdbException("search tree larger than file")
        // IPv4 addresses live under ::/96 in an IPv6 tree; walk the 96 zero bits once instead of on every lookup.
        var node = 0
        if (ipVersion == 6) {
            for (i in 0 until 96) {
                if (node >= nodeCount) break
                node = readNode(node, 0)
            }
        }
        ipv4Start = node
    }

    private fun checkRange(off: Long, len: Int) {
        if (off < 0 || len < 0 || off + len > buf.capacity()) throw MmdbException("read out of bounds at $off+$len")
    }

    private fun u8(off: Long): Int {
        checkRange(off, 1)
        return buf.get(off.toInt()).toInt() and 0xFF
    }

    private fun bytes(off: Long, len: Int): ByteArray {
        checkRange(off, len)
        val b = ByteArray(len)
        for (i in 0 until len) b[i] = buf.get(off.toInt() + i)
        return b
    }

    private fun uint(off: Long, len: Int): Long {
        checkRange(off, len)
        var v = 0L
        for (i in 0 until len) v = (v shl 8) or (buf.get(off.toInt() + i).toLong() and 0xFF)
        return v
    }

    /** The metadata marker is searched backwards from the end: the *last* occurrence is the real one. */
    private fun findMetadata(): Map<String, Any?> {
        val marker = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()) + "MaxMind.com".toByteArray(Charsets.US_ASCII)
        val size = buf.capacity()
        val stop = maxOf(0, size - 128 * 1024)
        var i = size - marker.size
        while (i >= stop) {
            var match = true
            for (j in marker.indices) if (buf.get(i + j) != marker[j]) { match = false; break }
            if (match) {
                val start = (i + marker.size).toLong()
                @Suppress("UNCHECKED_CAST")
                return Decoder(start).decode(start, 0).first as? Map<String, Any?>
                    ?: throw MmdbException("metadata is not a map")
            }
            i--
        }
        throw MmdbException("mmdb metadata marker not found")
    }

    private fun readNode(node: Int, bit: Int): Int {
        val off = node.toLong() * nodeByteSize
        return when (recordSize) {
            24 -> {
                val b = if (bit == 0) off else off + 3
                ((u8(b) shl 16) or (u8(b + 1) shl 8) or u8(b + 2))
            }
            28 -> {
                val middle = u8(off + 3)
                if (bit == 0) ((middle and 0xF0) shl 20) or (u8(off) shl 16) or (u8(off + 1) shl 8) or u8(off + 2)
                else ((middle and 0x0F) shl 24) or (u8(off + 4) shl 16) or (u8(off + 5) shl 8) or u8(off + 6)
            }
            else -> uint(if (bit == 0) off else off + 4, 4).toInt()
        }
    }

    /** Looks an IP literal up. Returns the record map, or null when the address is not in the database. */
    fun lookup(ip: String): Map<String, Any?>? {
        val v4 = IpUtils.parseIpv4(ip)
        val bits: ByteArray
        var node: Int
        val bitCount: Int
        if (v4 != null) {
            bits = byteArrayOf((v4 shr 24).toByte(), (v4 shr 16).toByte(), (v4 shr 8).toByte(), v4.toByte())
            node = if (ipVersion == 6) ipv4Start else 0
            bitCount = 32
        } else {
            if (ipVersion == 4) return null
            bits = IpUtils.parseIpv6(ip) ?: return null
            node = 0
            bitCount = 128
        }
        var i = 0
        while (i < bitCount && node < nodeCount) {
            val bit = (bits[i / 8].toInt() shr (7 - (i % 8))) and 1
            node = readNode(node, bit)
            i++
        }
        if (node <= nodeCount) return null           // == nodeCount means "no data"
        val offset = searchTreeSize + (node - nodeCount).toLong()
        if (offset >= buf.capacity()) throw MmdbException("data pointer out of bounds")
        @Suppress("UNCHECKED_CAST")
        return Decoder(dataBase).decode(offset, 0).first as? Map<String, Any?>
    }

    private inner class Decoder(private val pointerBase: Long) {
        fun decode(offset: Long, depth: Int): Pair<Any?, Long> {
            if (depth > 32) throw MmdbException("data structure nested too deep")
            val ctrl = u8(offset)
            var type = ctrl shr 5
            var pos = offset + 1
            if (type == 1) {
                val ptrSize = (ctrl shr 3) and 3
                val n = ptrSize + 1
                val ptr = when (ptrSize) {
                    0 -> ((ctrl and 7).toLong() shl 8) or uint(pos, 1)
                    1 -> (((ctrl and 7).toLong() shl 16) or uint(pos, 2)) + 2048
                    2 -> (((ctrl and 7).toLong() shl 24) or uint(pos, 3)) + 526336
                    else -> uint(pos, 4)
                }
                val target = decode(pointerBase + ptr, depth + 1).first
                return target to pos + n
            }
            if (type == 0) {
                type = u8(pos) + 7
                pos++
            }
            val sizeField = ctrl and 31
            if (type == 14) return (sizeField != 0) to pos
            val size: Long = when {
                sizeField < 29 -> sizeField.toLong()
                sizeField == 29 -> (u8(pos) + 29).toLong().also { pos += 1 }
                sizeField == 30 -> (uint(pos, 2) + 285).also { pos += 2 }
                else -> (uint(pos, 3) + 65821).also { pos += 3 }
            }
            if (size > Int.MAX_VALUE) throw MmdbException("size overflow")
            val len = size.toInt()
            return when (type) {
                2 -> String(bytes(pos, len), Charsets.UTF_8) to pos + len
                3 -> { checkRange(pos, 8); buf.getDouble(pos.toInt()) to pos + 8 }
                4 -> bytes(pos, len) to pos + len
                5, 6 -> { if (len > 8) throw MmdbException("uint too wide"); uint(pos, len) to pos + len }
                9 -> { if (len > 8) throw MmdbException("uint64 too wide"); uint(pos, len) to pos + len }
                7 -> {
                    val map = LinkedHashMap<String, Any?>()
                    var p = pos
                    repeat(len) {
                        val (k, p1) = decode(p, depth + 1)
                        val (v, p2) = decode(p1, depth + 1)
                        map[k as? String ?: throw MmdbException("map key is not a string")] = v
                        p = p2
                    }
                    map to p
                }
                8 -> {
                    if (len > 4) throw MmdbException("int32 too wide")
                    var v = 0
                    for (k in 0 until len) v = (v shl 8) or u8(pos + k)
                    // Per the MMDB spec a shortened int32 is zero-padded (positive); only the 4-byte form carries a sign.
                    v to pos + len
                }
                10 -> BigInteger(1, bytes(pos, len)) to pos + len
                11 -> {
                    val list = ArrayList<Any?>(minOf(len, 1024))
                    var p = pos
                    repeat(len) {
                        val (v, p2) = decode(p, depth + 1)
                        list += v; p = p2
                    }
                    list to p
                }
                15 -> { checkRange(pos, 4); buf.getFloat(pos.toInt()).toDouble() to pos + 4 }
                else -> null to pos + len
            }
        }
    }

    companion object {
        fun open(buffer: ByteBuffer): MmdbReader = MmdbReader(buffer)
    }
}
