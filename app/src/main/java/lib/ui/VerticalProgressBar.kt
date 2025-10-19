package lib.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.aio.R

// Custom vertical progress bar view
class VerticalProgressBar @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

	private var progress = 0     // Current progress value
	private var max = 100        // Maximum progress value

	// Paint for the background bar
	private val backgroundPaint = Paint().apply {
		color = ContextCompat.getColor(context, R.color.color_primary_variant)
		style = Paint.Style.FILL
		isAntiAlias = true
	}

	// Paint for the progress fill
	private val progressPaint = Paint().apply {
		color = ContextCompat.getColor(context, R.color.color_secondary)
		style = Paint.Style.FILL
		isAntiAlias = true
	}

	// Set current progress and redraw
	fun setProgress(value: Int) {
		progress = value.coerceIn(0, max)
		invalidate()
	}

	// Set maximum value and redraw
	fun setMax(value: Int) {
		max = value
		invalidate()
	}

	// Measure view size
	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val desiredWidth = dpToPx(10)   // Default width
		val desiredHeight = dpToPx(200) // Default height
		val width = resolveSize(desiredWidth, widthMeasureSpec)
		val height = resolveSize(desiredHeight, heightMeasureSpec)
		setMeasuredDimension(width, height)
	}

	// Draw the vertical progress bar
	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)

		// Draw the background rectangle
		canvas.drawRoundRect(
			0f, 0f, width.toFloat(), height.toFloat(),
			dpToPx(10).toFloat(), dpToPx(10).toFloat(),
			backgroundPaint
		)

		// Draw the progress fill from bottom to top
		val progressHeight = (height * progress / max.toFloat())
		canvas.drawRoundRect(
			0f,
			height - progressHeight,   // Start from bottom
			width.toFloat(),
			height.toFloat(),
			dpToPx(10).toFloat(),
			dpToPx(10).toFloat(),
			progressPaint
		)
	}

	// Convert dp to pixels
	private fun dpToPx(dp: Int): Int {
		return (dp * Resources.getSystem().displayMetrics.density).toInt()
	}
}