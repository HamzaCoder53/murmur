package com.bitchat.desktop.platform

import java.awt.Color
import java.awt.Graphics2D
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/**
 * AWT [SystemTray]-backed [TrayController] for macOS menu bar and Windows notification area.
 */
class AwtTrayController : TrayController {
    private var icon: TrayIcon? = null
    private var statusItem: MenuItem? = null

    override val isSupported: Boolean
        get() = try {
            !java.awt.GraphicsEnvironment.isHeadless() && SystemTray.isSupported()
        } catch (_: Throwable) {
            false
        }

    override fun show(tooltip: String, onOpen: () -> Unit, onQuit: () -> Unit) {
        if (!isSupported) return
        SwingUtilities.invokeLater {
            if (icon != null) return@invokeLater
            val menu = PopupMenu()
            val status = MenuItem("Skein — Ready").apply { isEnabled = false }
            val openItem = MenuItem("Open Skein").apply { addActionListener { onOpen() } }
            val quitItem = MenuItem("Quit").apply { addActionListener { onQuit() } }
            menu.add(status)
            menu.addSeparator()
            menu.add(openItem)
            menu.add(quitItem)

            val trayIcon = TrayIcon(renderBadgeImage(0), tooltip, menu).apply {
                isImageAutoSize = true
                addActionListener { onOpen() }
            }
            try {
                SystemTray.getSystemTray().add(trayIcon)
                icon = trayIcon
                statusItem = status
            } catch (_: Exception) {
            }
        }
    }

    override fun setUnreadCount(count: Int) {
        val current = icon ?: return
        SwingUtilities.invokeLater {
            statusItem?.label = if (count > 0) "Skein — $count unread" else "Skein — Ready"
            current.image = renderBadgeImage(count)
        }
    }

    fun displayMessage(title: String, body: String) {
        val current = icon ?: return
        SwingUtilities.invokeLater {
            current.displayMessage(title, body, TrayIcon.MessageType.INFO)
        }
    }

    override fun hide() {
        val current = icon ?: return
        icon = null
        statusItem = null
        SwingUtilities.invokeLater {
            try { SystemTray.getSystemTray().remove(current) } catch (_: Exception) { }
        }
    }

    private fun renderBadgeImage(unread: Int): BufferedImage {
        val size = 32
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x1E, 0x88, 0xE5)
        g.fillRoundRect(2, 2, size - 4, size - 4, 8, 8)
        g.color = Color.WHITE
        g.drawOval(8, 8, size - 16, size - 16)
        if (unread > 0) {
            g.color = Color(0xE5, 0x39, 0x35)
            g.fillOval(size - 12, 2, 10, 10)
        }
        g.dispose()
        return img
    }
}
