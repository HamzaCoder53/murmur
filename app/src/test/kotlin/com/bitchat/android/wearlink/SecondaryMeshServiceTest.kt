package com.bitchat.android.wearlink

import com.bitchat.android.mesh.MeshDelegate
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.mesh.PrivateMediaPreparation
import com.bitchat.android.model.BitchatFilePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SecondaryMeshServiceTest {

    private val watchPeer = "1111111111111111"
    private val wifiPeer = "3333333333333333"

    private val wifi: MeshService = mock()
    private val wear: MeshService = mock()
    private var wifiRunning = true
    private var wearRunning = true
    private val secondary = SecondaryMeshService(
        wifi = { wifi.takeIf { wifiRunning } },
        wear = { wear.takeIf { wearRunning } }
    )

    private fun connected(service: MeshService, peerID: String, session: Boolean = false) {
        val info: PeerInfo = mock { on { isConnected } doReturn true }
        whenever(service.getPeerInfo(peerID)).thenReturn(info)
        whenever(service.hasEstablishedSession(peerID)).thenReturn(session)
    }

    @Test
    fun `reports nothing available when neither transport is running`() {
        wifiRunning = false
        wearRunning = false

        assertFalse(secondary.hasAny())
        assertNull(secondary.getPeerInfo(watchPeer))
        assertFalse(secondary.hasEstablishedSession(watchPeer))
    }

    @Test
    fun `a private message goes to the transport that reaches the peer`() {
        connected(wear, watchPeer, session = true)
        connected(wifi, wifiPeer, session = true)

        secondary.sendPrivateMessage("hi", watchPeer, "watch", "m1")
        secondary.sendPrivateMessage("hi", wifiPeer, "friend", "m2")

        verify(wear).sendPrivateMessage("hi", watchPeer, "watch", "m1")
        verify(wifi).sendPrivateMessage("hi", wifiPeer, "friend", "m2")
        verify(wifi, never()).sendPrivateMessage("hi", watchPeer, "watch", "m1")
    }

    @Test
    fun `a transport with a session is preferred over one that only sees the peer`() {
        connected(wear, watchPeer, session = false)
        connected(wifi, watchPeer, session = true)

        secondary.sendReadReceipt("m1", watchPeer, "me")

        verify(wifi).sendReadReceipt("m1", watchPeer, "me")
        verify(wear, never()).sendReadReceipt(any(), any(), any())
    }

    @Test
    fun `session and peer info for a peer are answered by the same transport`() {
        connected(wear, watchPeer, session = true)
        whenever(wifi.hasEstablishedSession(watchPeer)).thenReturn(false)

        assertTrue(secondary.hasEstablishedSession(watchPeer))
        assertSame(wear.getPeerInfo(watchPeer), secondary.getPeerInfo(watchPeer))
    }

    @Test
    fun `a handshake is started on the transport connected to the peer`() {
        connected(wear, watchPeer)

        secondary.initiateNoiseHandshake(watchPeer)

        verify(wear).initiateNoiseHandshake(watchPeer)
        verify(wifi, never()).initiateNoiseHandshake(any())
    }

    @Test
    fun `an unknown peer falls back to wifi aware then the watch link`() {
        secondary.sendPrivateMessage("hi", "9999999999999999", "x", null)
        verify(wifi).sendPrivateMessage("hi", "9999999999999999", "x", null)

        wifiRunning = false
        secondary.sendPrivateMessage("yo", "9999999999999999", "x", null)
        verify(wear).sendPrivateMessage("yo", "9999999999999999", "x", null)
    }

    @Test
    fun `a broadcast originates on a single transport`() {
        secondary.sendMessage("hello", emptyList(), null)

        verify(wifi).sendMessage("hello", emptyList(), null)
        verify(wear, never()).sendMessage(any(), any(), any())
    }

    @Test
    fun `a broadcast uses the watch link when it is the only secondary transport`() {
        wifiRunning = false

        secondary.sendMessage("hello", emptyList(), null)

        verify(wear).sendMessage("hello", emptyList(), null)
    }

    @Test
    fun `every transport announces for itself`() {
        secondary.sendBroadcastAnnounce()

        verify(wifi).sendBroadcastAnnounce()
        verify(wear).sendBroadcastAnnounce()
    }

    @Test
    fun `peer nicknames are merged across transports`() {
        whenever(wifi.getPeerNicknames()).thenReturn(mapOf(wifiPeer to "friend"))
        whenever(wear.getPeerNicknames()).thenReturn(mapOf(watchPeer to "watch"))

        assertEquals(mapOf(wifiPeer to "friend", watchPeer to "watch"), secondary.getPeerNicknames())
    }

    @Test
    fun `setting the delegate reaches both transports`() {
        val delegate: MeshDelegate = mock()

        secondary.delegate = delegate

        verify(wifi).delegate = delegate
        verify(wear).delegate = delegate
    }

    @Test
    fun `delegate reads as unset while any running transport lacks it`() {
        val delegate: MeshDelegate = mock()
        whenever(wifi.delegate).thenReturn(delegate)
        whenever(wear.delegate).thenReturn(null)

        assertNull(secondary.delegate)

        whenever(wear.delegate).thenReturn(delegate)
        assertSame(delegate, secondary.delegate)
    }

    @Test
    fun `a transport that already has the delegate is not reassigned`() {
        val delegate: MeshDelegate = mock()
        whenever(wifi.delegate).thenReturn(delegate)

        secondary.delegate = delegate

        verify(wifi, never()).delegate = delegate
        verify(wear).delegate = delegate
    }

    @Test
    fun `private file preparation is rejected when no transport is running`() {
        wifiRunning = false
        wearRunning = false
        val file: BitchatFilePacket = mock()

        val result = secondary.prepareFilePrivate(watchPeer, file, "t1", allowLegacyFallback = false)

        assertTrue(result is PrivateMediaPreparation.Rejected)
    }

    @Test
    fun `encryption data counts as cleared only if every transport cleared`() {
        whenever(wifi.clearAllEncryptionData()).thenReturn(true)
        whenever(wear.clearAllEncryptionData()).thenReturn(false)

        assertFalse(secondary.clearAllEncryptionData())

        whenever(wear.clearAllEncryptionData()).thenReturn(true)
        assertTrue(secondary.clearAllEncryptionData())
    }

    @Test
    fun `the local Wi-Fi mesh joins the secondary transports when running`() {
        val lan: MeshService = mock()
        val lanPeer = "4444444444444444"
        val withLan = SecondaryMeshService(wifi = { null }, wear = { null }, lan = { lan })
        connected(lan, lanPeer, session = true)

        assertTrue(withLan.hasAny())
        withLan.sendPrivateMessage("hi", lanPeer, "friend", "m3")
        withLan.sendMessage("public", emptyList(), null)

        verify(lan).sendPrivateMessage("hi", lanPeer, "friend", "m3")
        verify(lan).sendMessage("public", emptyList(), null)
    }

    @Test
    fun `start and stop do not touch the transports`() {
        secondary.startServices()
        secondary.stopServices()

        verify(wifi, never()).startServices()
        verify(wear, never()).stopServices()
    }
}
