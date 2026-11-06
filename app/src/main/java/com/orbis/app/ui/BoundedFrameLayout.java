package com.orbis.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import com.orbis.app.R;

/**
 * A frame that refuses to grow past a readable measure.
 *
 * <p>Every screen is a column of body text, and a line that runs the full width
 * of a tablet, an unfolded phone or a phone whose display size has been turned
 * down is tight to read. The Compose build capped its content at 640dp and
 * centred it; this is the same cap for the View build.
 *
 * <p>A custom view rather than ConstraintLayout's {@code layout_constraintWidth_max}:
 * that would mean adding back a dependency this project dropped, for one
 * attribute on one container. The cap is applied once here and every fragment
 * inherits it.
 */
public class BoundedFrameLayout extends FrameLayout {

    public BoundedFrameLayout(Context context) {
        this(context, null);
    }

    public BoundedFrameLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BoundedFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int max = getResources().getDimensionPixelSize(R.dimen.content_max_width);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (max > 0 && width > max) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(max, MeasureSpec.getMode(widthMeasureSpec));
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
