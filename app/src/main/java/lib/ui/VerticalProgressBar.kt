package lib.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.aio.R

class VerticalProgressBar @JvmOverloads constructor(context: Context,
	attrs: AttributeSet? = null, defStyleAttr: Int = 0) : View(context, attrs, defStyleAttr) {

	private var progress = 0
	private var max = 100

	private val backgroundPaint = Paint().apply {
		color = ContextCompat.getColor(context, R.color.color_primary_variant)
		style = Paint.Style.FILL
		isAntiAlias = true
	}

	private val progressPaint = Paint().apply {
		color = ContextCompat.getColor(context, R.color.color_secondary)
		style = Paint.Style.FILL
		isAntiAlias = true
	}

	fun setProgress(value: Int) {
		progress = value.coerceIn(0, max)
		invalidate()
	}

	fun setMax(value: Int) {
		max = value
		invalidate()
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val desiredWidth = dpToPx(20)
		val desiredHeight = dpToPx(200)
		val width = resolveSize(desiredWidth, widthMeasureSpec)
		val height = resolveSize(desiredHeight, heightMeasureSpec)
		setMeasuredDimension(width, height)
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)

		// Draw background
		canvas.drawRoundRect(
			0f, 0f, width.toFloat(), height.toFloat(),
			dpToPx(10).toFloat(), dpToPx(10).toFloat(), backgroundPaint
		)

		// Draw progress
		val progressHeight = (height * progress / max.toFloat())
		canvas.drawRoundRect(
			0f,
			height - progressHeight,
			width.toFloat(),
			height.toFloat(),
			dpToPx(10).toFloat(),
			dpToPx(10).toFloat(),
			progressPaint
		)
	}

	private fun dpToPx(dp: Int): Int {
		return (dp * Resources.getSystem().displayMetrics.density).toInt()
	}
}
