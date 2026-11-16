package com.bitchat.android.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanPeerTableTest {

    // Synthetic values from documentation ranges; not real devices or peers.
    private val hostA = "192.0.2.10"
    private val hostB = "192.0.2.11"
    private val peerA = "aaaaaaaaaaaaaaaa"

    @Test
    fun `connections get distinct link addresses that are never reused`() {
        val table = LanPeerTable<String>(maxConnections = 4)
        val first = table.add(hostA, "one")!!
        table.remove(first)
        val second = table.add(hostA, "two")!!

        assertTrue(LanPeerTable.isLinkAddress(first))
        assertNotEquals(first, second)
        assertNull(table.get(first))
        assertEquals("two", table.get(second)!!.value)
    }

    @Test
    fun `the cap refuses new connections until one closes`() {
        val table = LanPeerTable<Int>(maxConnections = 2)
        val first = table.add(hostA, 1)
        assertNotNull(table.add(hostB, 2))
        assertTrue(table.isFull())
        assertNull(table.add(hostB, 3))

        table.remove(first!!)
        assertFalse(table.isFull())
        assertNotNull(table.add(hostB, 4))
        assertEquals(2, table.size)
    }

    @Test
    fun `binding maps a peer to its newest connection and clears on disconnect`() {
        val table = LanPeerTable<Int>()
        val older = table.add(hostA, 1)!!
        val newer = table.add(hostA, 2)!!

        assertTrue(table.bind(older, peerA))
        assertTrue(table.bind(newer, peerA))
        assertEquals(newer, table.linkForPeer(peerA))
        assertEquals(mapOf(older to peerA, newer to peerA), table.peerMapping())

        table.remove(newer)
        assertEquals(older, table.linkForPeer(peerA))
        table.remove(older)
        assertNull(table.linkForPeer(peerA))
        assertTrue(table.peerMapping().isEmpty())
    }

    @Test
    fun `binding an unknown link fails`() {
        val table = LanPeerTable<Int>()
        assertFalse(table.bind("lan:999", peerA))
        assertNull(table.peerFor("lan:999"))
    }

    @Test
    fun `hosts are tracked so a reached host is not dialed again`() {
        val table = LanPeerTable<Int>()
        val link = table.add(hostA, 1)!!
        assertTrue(table.hasHost(hostA))
        assertFalse(table.hasHost(hostB))
        table.remove(link)
        assertFalse(table.hasHost(hostA))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive cap is a programming error`() {
        LanPeerTable<Int>(maxConnections = 0)
    }
}
