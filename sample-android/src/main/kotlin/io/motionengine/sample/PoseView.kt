package io.motionengine.sample

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import io.motionengine.core.PoseFrame
import kotlin.math.min

class PoseView(context: Context) : View(context) {
    var frame: PoseFrame? = null
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 210, 170); strokeWidth = 5f }
    private val connections = listOf(11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16, 11 to 23, 12 to 24,
        23 to 24, 23 to 25, 25 to 27, 24 to 26, 26 to 28)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val f = frame ?: return
        val scale = min(width.toFloat() / f.imageWidth, height.toFloat() / f.imageHeight)
        val dx = (width - f.imageWidth * scale) / 2; val dy = (height - f.imageHeight * scale) / 2
        connections.forEach { (a, b) ->
            val p = f.landmarks[a]; val q = f.landmarks[b]
            if (p.visibility >= 0.5 && q.visibility >= 0.5)
                canvas.drawLine(dx + p.x * f.imageWidth * scale, dy + p.y * f.imageHeight * scale,
                    dx + q.x * f.imageWidth * scale, dy + q.y * f.imageHeight * scale, paint)
        }
    }
}
