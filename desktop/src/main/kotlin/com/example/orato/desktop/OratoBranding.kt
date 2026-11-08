package com.example.orato.desktop

import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.ImageIcon

object OratoBranding {
    fun createAppIcon(size: Int = 128): Image {
        val fullIcon = loadFullIcon()
        if (fullIcon != null) {
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.drawImage(fullIcon.getScaledInstance(size, size, Image.SCALE_SMOOTH), 0, 0, null)
            graphics.dispose()
            return image
        }

        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        paintBackground(graphics, size)
        paintWordmark(graphics, size)
        graphics.dispose()
        return image
    }

    fun createSwingIcon(size: Int = 128): ImageIcon {
        return ImageIcon(createAppIcon(size))
    }

    // Android uygulamasıyla aynı simge (branding/generate_icons.py ile üretilir).
    private fun loadFullIcon(): BufferedImage? {
        return runCatching {
            OratoBranding::class.java.getResourceAsStream("/icons/beje_icon_full.png")?.use {
                ImageIO.read(it)
            }
        }.getOrNull()
    }

    private fun paintBackground(graphics: Graphics2D, size: Int) {
        val inset = (size * 0.02f).toInt().coerceAtLeast(1)
        val arc = (size * 0.24f).toInt()
        graphics.color = Color(0xEA, 0x36, 0x46)
        graphics.fillRoundRect(inset, inset, size - inset * 2, size - inset * 2, arc, arc)
    }

    private fun paintWordmark(graphics: Graphics2D, size: Int) {
        graphics.color = Color(0xFF, 0xD6, 0x1C)
        graphics.fillOval((size * 0.11f).toInt(), (size * 0.10f).toInt(), (size * 0.78f).toInt(), (size * 0.80f).toInt())

        graphics.color = Color(0x2A, 0xA7, 0x18)
        val text = "Bêje"
        val fontSize = (size * 0.26f).toInt().coerceAtLeast(18)
        graphics.font = Font("DejaVu Sans", Font.BOLD, fontSize)
        val metrics: FontMetrics = graphics.fontMetrics
        val textWidth = metrics.stringWidth(text)
        val textX = ((size - textWidth) / 2.0).toInt()
        val textY = ((size * 0.56f) + (metrics.ascent / 2.8f)).toInt()
        graphics.drawString(text, textX, textY)
    }
}
