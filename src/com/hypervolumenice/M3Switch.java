package com.hypervolumenice;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;

/** Material 3 风格的开关，纯自绘，避免引入 AppCompat。 */
public class M3Switch extends View {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(M3Switch view, boolean checked);
    }

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mTrack = new RectF();
    private final float mTrackWidth;
    private final float mTrackHeight;
    private final float mThumbOff;
    private final float mThumbOn;

    private final int mColorPrimary;
    private final int mColorOnPrimary;
    private final int mColorTrackOff;
    private final int mColorThumbOff;
    private final int mColorOutline;

    private ValueAnimator mAnimator;
    private OnCheckedChangeListener mListener;
    private boolean mChecked;
    private float mFraction;

    public M3Switch(Context context) {
        this(context, null);
    }

    public M3Switch(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        this.mTrackWidth = 52f * density;
        this.mTrackHeight = 32f * density;
        this.mThumbOn = 24f * density;
        this.mThumbOff = 16f * density;
        this.mColorPrimary = context.getColor(R.color.vf_primary);
        this.mColorOnPrimary = context.getColor(R.color.vf_on_primary);
        this.mColorTrackOff = context.getColor(R.color.vf_switch_track_off);
        this.mColorThumbOff = context.getColor(R.color.vf_switch_thumb_off);
        this.mColorOutline = context.getColor(R.color.vf_outline);
        setClickable(true);
        setFocusable(true);
    }

    public boolean isChecked() {
        return this.mChecked;
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        this.mListener = listener;
    }

    public void toggle() {
        setChecked(!this.mChecked, true);
        if (this.mListener != null) {
            this.mListener.onCheckedChanged(this, this.mChecked);
        }
    }

    public void setChecked(boolean checked, boolean animate) {
        if (this.mChecked == checked && this.mFraction == (checked ? 1f : 0f)) {
            return;
        }
        this.mChecked = checked;
        float target = checked ? 1f : 0f;
        if (this.mAnimator != null) {
            this.mAnimator.cancel();
        }
        if (!animate) {
            this.mFraction = target;
            invalidate();
            return;
        }
        this.mAnimator = ValueAnimator.ofFloat(this.mFraction, target);
        this.mAnimator.setDuration(180L);
        this.mAnimator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        this.mAnimator.addUpdateListener(animation -> {
            this.mFraction = (Float) animation.getAnimatedValue();
            invalidate();
        });
        this.mAnimator.start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(
                resolveSize((int) Math.ceil(this.mTrackWidth), widthMeasureSpec),
                resolveSize((int) Math.ceil(this.mTrackHeight), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float fraction = this.mFraction;
        float width = getWidth();
        float height = getHeight();
        float radius = height / 2f;
        float density = getResources().getDisplayMetrics().density;
        float stroke = Math.max(1f, density * 2f);

        this.mTrack.set(0f, 0f, width, height);
        this.mPaint.setStyle(Paint.Style.FILL);
        this.mPaint.setColor(blend(this.mColorTrackOff, this.mColorPrimary, fraction));
        canvas.drawRoundRect(this.mTrack, radius, radius, this.mPaint);

        if (fraction < 1f) {
            this.mPaint.setStyle(Paint.Style.STROKE);
            this.mPaint.setStrokeWidth(stroke);
            this.mPaint.setColor(withAlpha(this.mColorOutline, (int) ((1f - fraction) * 255f)));
            float inset = stroke / 2f;
            this.mTrack.set(inset, inset, width - inset, height - inset);
            float innerRadius = radius - inset;
            canvas.drawRoundRect(this.mTrack, innerRadius, innerRadius, this.mPaint);
        }

        float thumb = this.mThumbOff + ((this.mThumbOn - this.mThumbOff) * fraction);
        float padding = density * 8f;
        float trailing = density * 4f;
        float start = (this.mThumbOff / 2f) + padding;
        float end = (width - trailing) - (this.mThumbOn / 2f);
        float cx = start + ((end - start) * fraction);

        this.mPaint.setStyle(Paint.Style.FILL);
        this.mPaint.setColor(blend(this.mColorThumbOff, this.mColorOnPrimary, fraction));
        canvas.drawCircle(cx, height / 2f, thumb / 2f, this.mPaint);
    }

    private static int blend(int from, int to, float fraction) {
        return Color.argb(
                (int) (Color.alpha(from) + ((Color.alpha(to) - Color.alpha(from)) * fraction)),
                (int) (Color.red(from) + ((Color.red(to) - Color.red(from)) * fraction)),
                (int) (Color.green(from) + ((Color.green(to) - Color.green(from)) * fraction)),
                (int) (Color.blue(from) + ((Color.blue(to) - Color.blue(from)) * fraction)));
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
