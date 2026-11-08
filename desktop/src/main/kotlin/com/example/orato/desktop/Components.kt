package com.example.orato.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.Timer
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Köşeleri yuvarlatılmış, rengi temadan okunan panel. */
open class RoundedPanel(
    layout: LayoutManager,
    private val arc: Int = 28,
    private val fill: () -> Color = { BejeTheme.card },
    private val outline: () -> Color? = { null },
    private val outlineWidth: () -> Float = { 1f }
) : JPanel(layout) {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = fill()
        g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        outline()?.let { color ->
            val stroke = outlineWidth()
            g2.color = color
            g2.stroke = BasicStroke(stroke)
            val inset = stroke / 2
            g2.draw(java.awt.geom.RoundRectangle2D.Float(inset, inset, width - 1 - stroke, height - 1 - stroke, arc.toFloat(), arc.toFloat()))
        }
        g2.dispose()
        super.paintComponent(g)
    }
}

/** Dil kodunu gösteren yuvarlak rozet (TR, FA, AR). */
class CodeBadge(text: String) : JLabel(text.uppercase(), SwingConstants.CENTER) {
    init {
        preferredSize = Dimension(40, 40)
        minimumSize = preferredSize
        font = font.deriveFont(java.awt.Font.BOLD, 13f)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = BejeTheme.tonal
        g2.fillOval(0, 0, width - 1, height - 1)
        g2.dispose()
        foreground = BejeTheme.onTonal
        super.paintComponent(g)
    }
}

/**
 * Büyük yuvarlak mikrofon düğmesi. Dinlerken arkasında ses seviyesine göre büyüyen halkalar
 * ve simgedeki güneşi andıran 21 ışın çizer.
 */
class MicButton(private val onClick: () -> Unit) : JComponent() {
    private var targetLevel = 0f
    private var displayLevel = 0f
    private var hover = false
    private var pressed = false
    private var startTime = System.currentTimeMillis()
    private val timer = Timer(16) { repaint() }

    var active = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                startTime = System.currentTimeMillis()
                timer.start()
            } else {
                targetLevel = 0f
            }
            repaint()
        }

    var dimmed = false
        set(value) {
            field = value
            repaint()
        }

    init {
        preferredSize = Dimension(176, 176)
        minimumSize = preferredSize
        isFocusable = true
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; pressed = false; repaint() }
            override fun mousePressed(e: MouseEvent) {
                if (insideButton(e)) { pressed = true; requestFocusInWindow(); repaint() }
            }
            override fun mouseReleased(e: MouseEvent) {
                val click = pressed && insideButton(e)
                pressed = false
                repaint()
                if (click && isEnabled) onClick()
            }
        })
        listOf(KeyEvent.VK_SPACE, KeyEvent.VK_ENTER).forEach { key ->
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), "press")
        }
        actionMap.put("press", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { if (isEnabled) onClick() }
        })
    }

    fun setLevel(level: Float) {
        targetLevel = level.coerceIn(0f, 1f)
    }

    private val buttonRadius get() = 44f

    private fun insideButton(e: MouseEvent): Boolean {
        val dx = e.x - width / 2f
        val dy = e.y - height / 2f
        return dx * dx + dy * dy <= buttonRadius * buttonRadius
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = min(width, height) / 2f
        val base = buttonRadius
        val room = (maxRadius - base).coerceAtLeast(1f)

        displayLevel += (targetLevel - displayLevel) * 0.25f
        val animating = active || displayLevel > 0.01f
        if (!animating) {
            displayLevel = 0f
            timer.stop()
        }

        if (animating) {
            val time = (System.currentTimeMillis() - startTime) / 1000f
            for (index in 0 until 3) {
                val phase = ((time * 0.6f) + index / 3f) % 1f
                val radius = base + room * phase * (0.45f + 0.55f * displayLevel)
                val alpha = ((1f - phase) * (0.10f + 0.30f * displayLevel) * 255).toInt().coerceIn(0, 255)
                g2.color = Color(BejeTheme.brandRed.red, BejeTheme.brandRed.green, BejeTheme.brandRed.blue, alpha)
                g2.fill(Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2))
            }
            val rayStart = base + room * 0.14f
            g2.stroke = BasicStroke(base * 0.07f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val sun = BejeTheme.brandSun
            g2.color = Color(sun.red, sun.green, sun.blue, (90 + 165 * displayLevel).toInt().coerceIn(0, 255))
            for (index in 0 until 21) {
                val angle = index * 2 * Math.PI / 21 - Math.PI / 2
                val wobble = 0.5f + 0.5f * sin(time * 7f + index * 1.7f)
                val length = room * (0.06f + 0.42f * displayLevel * (0.55f + 0.45f * wobble))
                val c = cos(angle).toFloat()
                val s = sin(angle).toFloat()
                g2.draw(java.awt.geom.Line2D.Float(cx + c * rayStart, cy + s * rayStart, cx + c * (rayStart + length), cy + s * (rayStart + length)))
            }
        }

        // Gölge
        g2.color = Color(0, 0, 0, if (BejeTheme.isDark) 90 else 45)
        g2.fill(Ellipse2D.Float(cx - base + 1, cy - base + 4, base * 2, base * 2))

        val fill = when {
            !isEnabled -> BejeTheme.brandRed
            pressed || active -> BejeTheme.brandRedDark
            hover -> Color(0xD7, 0x2E, 0x3C)
            else -> BejeTheme.brandRed
        }
        val alpha = if (dimmed || !isEnabled) 140 else 255
        g2.color = Color(fill.red, fill.green, fill.blue, alpha)
        g2.fill(Ellipse2D.Float(cx - base, cy - base, base * 2, base * 2))

        if (isFocusOwner) {
            g2.color = BejeTheme.activeOutline
            g2.stroke = BasicStroke(2f)
            g2.draw(Ellipse2D.Float(cx - base - 4, cy - base - 4, base * 2 + 8, base * 2 + 8))
        }

        val icon = BejeTheme.icon(if (active) "stop" else "mic", 38) { Color.WHITE }
        icon.paintIcon(this, g2, (cx - 19).toInt(), (cy - 19).toInt())
        g2.dispose()
    }
}
