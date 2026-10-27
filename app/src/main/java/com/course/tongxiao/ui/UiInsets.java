package com.course.tongxiao.ui;

import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Edge-to-edge inset helpers.
 *
 * <p>targetSdk 36 forces edge-to-edge: the app draws under the status bar and the
 * navigation bar, so every screen adds the system-bar insets itself. Without this
 * the toolbar sits underneath the status bar (observed on device).
 */
public final class UiInsets {

    private UiInsets() {
    }

    /** Pads {@code view} top by the status bar and bottom by the nav bar. */
    public static void padSystemBars(View view) {
        padSystemBars(view, 0, 0);
    }

    /** Same as {@link #padSystemBars(View)} plus extra top/bottom dp for breathing room. */
    public static void padSystemBars(View view, int extraTopDp, int extraBottomDp) {
        final float density = view.getResources().getDisplayMetrics().density;
        final int extraTop = Math.round(extraTopDp * density);
        final int extraBottom = Math.round(extraBottomDp * density);
        final int baseLeft = view.getPaddingLeft();
        final int baseTop = view.getPaddingTop();
        final int baseRight = view.getPaddingRight();
        final int baseBottom = view.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(baseLeft, baseTop + bars.top + extraTop,
                    baseRight, baseBottom + bars.bottom + extraBottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /**
     * Pads {@code view} bottom by whichever is larger: the nav bar or the IME, so
     * text fields stay reachable while the keyboard is open.
     */
    public static void padSystemBarsAndIme(View view, int extraBottomDp) {
        final float density = view.getResources().getDisplayMetrics().density;
        final int extraBottom = Math.round(extraBottomDp * density);
        final int baseLeft = view.getPaddingLeft();
        final int baseTop = view.getPaddingTop();
        final int baseRight = view.getPaddingRight();
        final int baseBottom = view.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(baseLeft, baseTop + bars.top, baseRight,
                    baseBottom + Math.max(bars.bottom, ime.bottom) + extraBottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /** Adds the nav-bar inset to a bottom bar so it is not drawn behind the gesture bar. */
    public static void padBottomBar(View view) {
        final int baseLeft = view.getPaddingLeft();
        final int baseTop = view.getPaddingTop();
        final int baseRight = view.getPaddingRight();
        final int baseBottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(baseLeft, baseTop, baseRight, baseBottom + bars.bottom);
            ViewGroup.LayoutParams params = v.getLayoutParams();
            if (params != null) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                v.setLayoutParams(params);
            }
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(view);
    }
}
