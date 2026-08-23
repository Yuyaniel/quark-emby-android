package com.quarkemby.app.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import com.quarkemby.app.R

/**
 * Small design-system helper for the programmatic UI parts:
 *  - three button variants (primary / secondary / danger) with unified radius + ripple
 *  - scrim dialogs with window blur behind (Android 12+) and dim fallback
 *  - icon chip + text-level helpers used by menus and lists
 */
object Ui {

    // ---------- buttons ----------
    fun primaryBtn(ctx: Context, label: String, onClick: () -> Unit): AppCompatButton =
        build(ctx, label, R.drawable.bg_btn_primary, R.color.on_primary, onClick)

    fun secondaryBtn(ctx: Context, label: String, onClick: () -> Unit): AppCompatButton =
        build(ctx, label, R.drawable.bg_btn_secondary, R.color.ink, onClick)

    fun dangerBtn(ctx: Context, label: String, onClick: () -> Unit): AppCompatButton =
        build(ctx, label, R.drawable.bg_btn_danger, R.color.danger, onClick)

    private fun build(ctx: Context, label: String, bg: Int, textColor: Int, onClick: () -> Unit) =
        AppCompatButton(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            stateListAnimator = null
            isClickable = true
            isFocusable = true
            setTextColor(ContextCompat.getColor(ctx, textColor))
            setBackgroundResource(bg)
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setPadding(0, 0, 0, 0)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, dp(ctx, 8), 0, dp(ctx, 8))
            layoutParams = lp
            setOnClickListener { onClick() }
        }

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    // ---------- scrim / blur dialogs ----------
    /**
     * Makes a Dialog float over a soft scrim: dim always, real background blur
     * on Android 12+ so the surface can stay translucent (bg_scrim_dialog).
     */
    fun applyScrim(d: Dialog, dimAmount: Float = 0.45f, blurRadius: Int = 20) {
        val w = d.window ?: return
        val lp = w.attributes
        lp.dimAmount = dimAmount
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            lp.blurBehindRadius = blurRadius
        }
        w.attributes = lp
    }

    /** Centered dialog window sizing with unified width ratio. */
    fun centerWindow(d: Dialog, widthRatio: Float = 0.9f) {
        val w = d.window ?: return
        w.setGravity(Gravity.CENTER)
        val lp = WindowManager.LayoutParams().apply {
            copyFrom(w.attributes)
            width = (w.context.resources.displayMetrics.widthPixels * widthRatio).toInt()
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }
        w.attributes = lp
    }

    // ---------- menu pieces ----------
    /** Rounded square chip that carries a menu-row icon. */
    fun iconChip(ctx: Context, icon: String): TextView = TextView(ctx).apply {
        text = icon
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(ctx, R.color.brand_primary))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, 10).toFloat()
            setColor(ContextCompat.getColor(ctx, R.color.surface_container_high))
        }
        val size = dp(ctx, 40)
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            marginEnd = dp(ctx, 12)
        }
    }

    /** Title-level text. */
    fun title(ctx: Context, text: String, size: Float = 16f): TextView = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(ctx, R.color.ink))
    }

    /** Subtitle-level text. */
    fun subtitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(ContextCompat.getColor(ctx, R.color.muted))
    }

    /** Helper-level text. */
    fun helper(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(ContextCompat.getColor(ctx, R.color.muted))
    }

    /** Simple vertical spacer. */
    fun spacer(ctx: Context, h: Int = 6): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, h))
    }
}