package com.pebblemirror.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.List;

/** Transparent full-screen view that draws the phone-side touch trail. */
final class TouchTrailView extends View implements TouchTrailOverlay.Listener {
    private final Paint blackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private final Runnable expiryRunnable = this::invalidate;

    TouchTrailView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        setWillNotDraw(false);

        blackPaint.setStyle(Paint.Style.STROKE);
        blackPaint.setStrokeCap(Paint.Cap.ROUND);
        blackPaint.setStrokeJoin(Paint.Join.ROUND);
        blackPaint.setStrokeWidth(14f);
        blackPaint.setColor(Color.BLACK);

        whitePaint.setStyle(Paint.Style.STROKE);
        whitePaint.setStrokeCap(Paint.Cap.ROUND);
        whitePaint.setStrokeJoin(Paint.Join.ROUND);
        whitePaint.setStrokeWidth(7f);
        whitePaint.setColor(Color.WHITE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        TouchTrailOverlay.addListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        TouchTrailOverlay.removeListener(this);
        removeCallbacks(expiryRunnable);
        super.onDetachedFromWindow();
    }

    @Override
    public void onTrailChanged() {
        postInvalidateOnAnimation();
        removeCallbacks(expiryRunnable);
        postDelayed(expiryRunnable, TouchTrailOverlay.TRAIL_MS + 100);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        List<TouchTrailOverlay.Point> points = TouchTrailOverlay.snapshot();
        if (points.isEmpty()) {
            return;
        }

        int[] location = new int[2];
        getLocationOnScreen(location);
        int offsetX = location[0];
        int offsetY = location[1];

        path.reset();
        TouchTrailOverlay.Point first = points.get(0);
        path.moveTo(first.x - offsetX, first.y - offsetY);
        for (int i = 1; i < points.size(); i++) {
            TouchTrailOverlay.Point point = points.get(i);
            path.lineTo(point.x - offsetX, point.y - offsetY);
        }

        if (points.size() > 1) {
            canvas.drawPath(path, blackPaint);
            canvas.drawPath(path, whitePaint);
        }

        TouchTrailOverlay.Point last = points.get(points.size() - 1);
        blackPaint.setStyle(Paint.Style.FILL);
        whitePaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(last.x - offsetX, last.y - offsetY, 17f, blackPaint);
        canvas.drawCircle(last.x - offsetX, last.y - offsetY, 9f, whitePaint);
        blackPaint.setStyle(Paint.Style.STROKE);
        whitePaint.setStyle(Paint.Style.STROKE);
    }
}
