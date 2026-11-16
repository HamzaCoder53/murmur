package com.bitchat.desktop.platform

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * mDNS / DNS-SD over JmDNS: the desktop counterpart of Android's NsdManager. Service types are
 * given the Android way ("_skeinlink._tcp.") and qualified with "local." here, so phones and
 * desktops see each other's adverts.
 *
 * One responder is bound to [bindAddress] (default: the first up, non-loopback, site-local IPv4
 * address) and created lazily on first use, so a machine without a LAN pays nothing.
 */
class JmdnsLocalDiscovery(
    private val bindAddress: () -> InetAddress? = ::defaultLanAddress,
) : LocalDiscovery, AutoCloseable {

    private val lock = Any()
    private var jmdns: JmDNS? = null

    private fun responder(): JmDNS = synchronized(lock) {
        jmdns ?: run {
            val addr = bindAddress()
            (if (addr != null) JmDNS.create(addr, "skein-desktop") else JmDNS.create()).also { jmdns = it }
        }
    }

    override fun advertise(serviceType: String, name: String, port: Int): AutoCloseable {
        require(port in 1..65535) { "bad port" }
        val info = ServiceInfo.create(qualify(serviceType), name, port, "")
        val mdns = responder()
        mdns.registerService(info)
        return AutoCloseable {
            try { mdns.unregisterService(info) } catch (_: Exception) { }
        }
    }

    override fun browse(
        serviceType: String,
        onFound: (DiscoveredService) -> Unit,
        onLost: (String) -> Unit,
    ): AutoCloseable {
        val type = qualify(serviceType)
        val mdns = responder()
        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                // Ask for SRV/A records; the answer arrives in serviceResolved.
                mdns.requestServiceInfo(event.type, event.name, true)
            }

            override fun serviceRemoved(event: ServiceEvent) = onLost(event.name)

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info ?: return
                val host = info.inet4Addresses.firstOrNull()?.hostAddress
                    ?: info.inetAddresses.firstOrNull()?.hostAddress
                    ?: return
                if (info.port !in 1..65535) return
                onFound(DiscoveredService(name = info.name, host = host, port = info.port))
            }
        }
        mdns.addServiceListener(type, listener)
        return AutoCloseable {
            try { mdns.removeServiceListener(type, listener) } catch (_: Exception) { }
        }
    }

    override fun close() {
        val m = synchronized(lock) { jmdns.also { jmdns = null } } ?: return
        try {
            m.unregisterAllServices()
            m.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** "_skeinlink._tcp." / "_skeinlink._tcp" -> "_skeinlink._tcp.local." */
        fun qualify(serviceType: String): String {
            val t = serviceType.trim().trimEnd('.')
            return if (t.endsWith(".local")) "$t." else "$t.local."
        }

        fun defaultLanAddress(): InetAddress? = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
        } catch (_: Exception) {
            null
        }
    }
}
