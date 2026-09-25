package com.example.scopaadvisor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import kotlin.math.abs

data class Slot(val zone: Zone, val left: Float, val top: Float, val right: Float, val bottom: Float)
data class RecognitionResult(val hand: List<Card>, val table: List<Card>, val confidence: Double, val templateCount: Int)

/**
 * Template recognizer for a fixed Scopa-game layout.
 * Put reference PNGs in assets/card_templates using names D_1.png ... B_10.png.
 * Matching uses an average-hash + coarse RGB signature and is intentionally dependency-free.
 */
class CardRecognitionEngine(private val context: Context) {
    private data class Signature(val hash: Long, val rgb: IntArray)
    private data class Template(val card: Card, val sig: Signature)
    private val templates = mutableListOf<Template>()

    // Generic portrait profile: 3 hand slots + central table slots.
    // For a specific game, tune these normalized rectangles in one place.
    private val slots = buildList {
        add(Slot(Zone.HAND, .08f, .73f, .31f, .98f))
        add(Slot(Zone.HAND, .385f, .73f, .615f, .98f))
        add(Slot(Zone.HAND, .69f, .73f, .92f, .98f))
        val xs = listOf(.08f to .29f, .30f to .50f, .51f to .71f, .72f to .92f)
        val ys = listOf(.20f to .38f, .39f to .57f, .58f to .72f)
        for (y in ys) for (x in xs) add(Slot(Zone.TABLE, x.first, y.first, x.second, y.second))
    }

    init { loadTemplates() }

    fun templateCount(): Int = templates.size

    fun recognize(screen: Bitmap): RecognitionResult {
        if (templates.isEmpty()) return RecognitionResult(emptyList(), emptyList(), 0.0, 0)
        val hand = mutableListOf<Card>()
        val table = mutableListOf<Card>()
        val scores = mutableListOf<Double>()
        for (slot in slots) {
            val r = Rect(
                (slot.left * screen.width).toInt().coerceIn(0, screen.width - 1),
                (slot.top * screen.height).toInt().coerceIn(0, screen.height - 1),
                (slot.right * screen.width).toInt().coerceIn(1, screen.width),
                (slot.bottom * screen.height).toInt().coerceIn(1, screen.height)
            )
            if (r.width() < 8 || r.height() < 8) continue
            val crop = Bitmap.createBitmap(screen, r.left, r.top, r.width(), r.height())
            val sig = signature(crop)
            crop.recycle()
            val best = templates.map { it to distance(sig, it.sig) }.minByOrNull { it.second } ?: continue
            // Conservative threshold to avoid inventing cards from empty/background slots.
            if (best.second <= 0.29) {
                val c = best.first.card
                if (slot.zone == Zone.HAND && c !in hand) hand += c
                if (slot.zone == Zone.TABLE && c !in table) table += c
                scores += (1.0 - best.second).coerceIn(0.0, 1.0)
            }
        }
        return RecognitionResult(hand, table, if (scores.isEmpty()) 0.0 else scores.average(), templates.size)
    }

    private fun loadTemplates() {
        val suitKeys = arrayOf("D", "C", "S", "B")
        for (s in 0..3) for (v in 1..10) {
            val name = "card_templates/${suitKeys[s]}_${v}.png"
            try {
                context.assets.open(name).use { input ->
                    val bmp = BitmapFactory.decodeStream(input) ?: return@use
                    templates += Template(Card(s, v), signature(bmp))
                    bmp.recycle()
                }
            } catch (_: Exception) { }
        }
    }

    private fun signature(src: Bitmap): Signature {
        val small = Bitmap.createScaledBitmap(src, 8, 8, true)
        val gray = IntArray(64)
        var sum = 0L
        var rs = 0L; var gs = 0L; var bs = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val p = small.getPixel(x, y)
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            val lum = (r * 30 + g * 59 + b * 11) / 100
            val i = y * 8 + x
            gray[i] = lum; sum += lum; rs += r; gs += g; bs += b
        }
        val avg = (sum / 64).toInt()
        var h = 0L
        for (i in 0 until 64) if (gray[i] >= avg) h = h or (1L shl i)
        small.recycle()
        return Signature(h, intArrayOf((rs/64).toInt(), (gs/64).toInt(), (bs/64).toInt()))
    }

    private fun distance(a: Signature, b: Signature): Double {
        val hd = java.lang.Long.bitCount(a.hash xor b.hash) / 64.0
        val cd = (abs(a.rgb[0]-b.rgb[0]) + abs(a.rgb[1]-b.rgb[1]) + abs(a.rgb[2]-b.rgb[2])) / (255.0 * 3.0)
        return hd * 0.78 + cd * 0.22
    }
}
