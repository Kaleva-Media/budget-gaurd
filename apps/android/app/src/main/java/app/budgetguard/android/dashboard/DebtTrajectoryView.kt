package app.budgetguard.android.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class DebtTrajectoryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val actualPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2E7650.toInt()
        strokeWidth = density(3f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val forecastPaint = Paint(actualPaint).apply {
        color = 0xFF17201B.toInt()
        pathEffect = DashPathEffect(floatArrayOf(density(8f), density(6f)), 0f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE4E2D9.toInt()
        strokeWidth = density(1f)
    }
    private val milestonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE56F51.toInt()
        style = Paint.Style.FILL
    }
    private var actualBalances: List<Long> = emptyList()
    private var forecast: List<DebtTrajectoryPoint> = emptyList()
    private var milestones: List<DebtMilestone> = emptyList()

    init {
        minimumHeight = density(190f).toInt()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setData(
        actualBalances: List<Long>,
        forecast: List<DebtTrajectoryPoint>,
        milestones: List<DebtMilestone>,
    ) {
        this.actualBalances = actualBalances
        this.forecast = forecast
        this.milestones = milestones
        val current = actualBalances.lastOrNull() ?: forecast.firstOrNull()?.remainingBalanceCents ?: 0
        val final = forecast.lastOrNull()?.remainingBalanceCents ?: current
        contentDescription = "Debt balance chart. Current balance ${formatZar(current)}. Projected final balance ${formatZar(final)} after ${forecast.lastOrNull()?.month ?: 0} months. Solid line is actual check-ins; dashed line is the forecast."
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + density(8f)
        val right = width - paddingRight - density(8f)
        val top = paddingTop + density(10f)
        val bottom = height - paddingBottom - density(12f)
        if (right <= left || bottom <= top) return

        for (step in 0..4) {
            val y = top + (bottom - top) * step / 4f
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        val maximum = max(
            1L,
            max(actualBalances.maxOrNull() ?: 0L, forecast.maxOfOrNull(DebtTrajectoryPoint::remainingBalanceCents) ?: 0L),
        )
        val actualSlots = max(1, actualBalances.size - 1)
        val forecastSlots = max(1, forecast.lastOrNull()?.month ?: 1)
        val actualWidth = if (actualBalances.size > 1) (right - left) * 0.28f else 0f
        val forecastStart = left + actualWidth
        fun y(value: Long) = bottom - (bottom - top) * value.toFloat() / maximum.toFloat()

        if (actualBalances.size > 1) {
            val path = Path()
            actualBalances.forEachIndexed { index, balance ->
                val x = left + actualWidth * index / actualSlots
                if (index == 0) path.moveTo(x, y(balance)) else path.lineTo(x, y(balance))
            }
            canvas.drawPath(path, actualPaint)
        }

        if (forecast.isNotEmpty()) {
            val path = Path()
            forecast.forEachIndexed { index, point ->
                val x = forecastStart + (right - forecastStart) * point.month / forecastSlots
                if (index == 0) path.moveTo(x, y(point.remainingBalanceCents)) else path.lineTo(x, y(point.remainingBalanceCents))
            }
            canvas.drawPath(path, forecastPaint)
            milestones.forEach { milestone ->
                val point = forecast.firstOrNull { it.month == milestone.month } ?: return@forEach
                val x = forecastStart + (right - forecastStart) * point.month / forecastSlots
                canvas.drawCircle(x, y(point.remainingBalanceCents), density(4.5f), milestonePaint)
            }
        }
    }

    private fun density(value: Float): Float = value * resources.displayMetrics.density
}
