package com.skyler.scanon

import com.skyler.scanon.data.GroupsStore
import com.skyler.scanon.data.HistoryStore
import com.skyler.scanon.data.VaultStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class StoresTest {

    @TempDir lateinit var dir: File

    @Test fun vaultConcurrentUpdatesAreNotLost() = runBlocking {
        val v = VaultStore(dir)
        (1..20).forEach { v.add("1.1.1.$it") }
        // 2.3: parallel recordCheck calls overwrote each other's changes.
        (1..20).map { i -> async(kotlinx.coroutines.Dispatchers.IO) { v.recordCheck("1.1.1.$i|cloudflare.com|443", true, i.toDouble()) } }.awaitAll()
        val reloaded = VaultStore(dir).load()
        assertEquals(20, reloaded.size)
        assertTrue(reloaded.all { it.lastOk == true && it.lastMs != null && it.recent.size == 1 })
    }

    @Test fun vaultDedupesByIpSniAndPort() = runBlocking {
        val v = VaultStore(dir)
        assertTrue(v.add("1.1.1.1", hostname = "a.com"))
        assertFalse(v.add("1.1.1.1", hostname = "a.com"))
        assertTrue(v.add("1.1.1.1", hostname = "b.com"))
        assertTrue(v.add("1.1.1.1", hostname = "a.com", port = 8443))
        assertEquals(3, v.load().size)
    }

    @Test fun badRecordDoesNotWipeTheFile() = runBlocking {
        File(dir, "vault.json").writeText("""[{"ip":"1.1.1.1"},{"label":"no ip"},{"ip":"8.8.8.8","port":53}]""")
        val list = VaultStore(dir).load()
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), list.map { it.ip })
    }

    @Test fun corruptFileIsKeptAside() = runBlocking {
        File(dir, "groups.json").writeText("{ not json")
        val g = GroupsStore(dir)
        assertTrue(g.load().isEmpty())
        g.upsert(GroupsStore.Group("CF", "ip", listOf("1.1.1.1", " 1.1.1.1 ", "")))
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("groups.json.corrupt-") })
        assertEquals(listOf("1.1.1.1"), GroupsStore(dir).load().single().items)
    }

    @Test fun historyCapAndUndo() = runBlocking {
        val h = HistoryStore(dir, cap = 5)
        repeat(8) { h.add("ip", "run $it", "body") }
        val list = h.load()
        assertEquals(5, list.size)
        assertEquals("run 7", list.first().summary)
        val victim = list.first()
        h.delete(victim.id)
        assertEquals(4, h.load().size)
        h.restore(victim)
        assertEquals(victim.id, h.load().first().id)
    }

    @Test fun groupsRenameRejectsDuplicates() = runBlocking {
        val g = GroupsStore(dir)
        g.upsert(GroupsStore.Group("a", "ip", listOf("1.1.1.1")))
        g.upsert(GroupsStore.Group("b", "ip", listOf("2.2.2.2")))
        g.rename("a", "ip", "B")
        assertEquals(setOf("a", "b"), g.load().map { it.name }.toSet())
        g.rename("a", "ip", "c")
        assertEquals(setOf("c", "b"), g.load().map { it.name }.toSet())
    }
}
