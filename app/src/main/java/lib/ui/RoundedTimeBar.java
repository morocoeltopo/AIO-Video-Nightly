package lib.ui;

import static android.util.TypedValue.COMPLEX_UNIT_DIP;
import static android.util.TypedValue.applyDimension;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.DisplayMetrics;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.DefaultTimeBar;

@UnstableApi
public class RoundedTimeBar extends DefaultTimeBar {
    private final Paint roundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final RectF rectF = new RectF();
    private final float cornerRadius;

    public RoundedTimeBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        cornerRadius = applyDimension(COMPLEX_UNIT_DIP, 10, displayMetrics);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rectF.set(0, 0, w, h);
        clipPath.reset();
        clipPath.addRoundRect(rectF, cornerRadius, cornerRadius, Path.Direction.CW);
    }

    @Override
    public void onDraw(Canvas canvas) {
        canvas.save();
        canvas.clipPath(clipPath);
        super.onDraw(canvas);
        canvas.restore();
    }

    public Paint getRoundPaint() {
        return roundPaint;
    }
}