package io.github.ari1re.kanjilock

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LocaleSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.util.Locale

/** Builds the RemoteViews shared by the widget and the lock screen notification. */
object CardViews {
    const val ACTION_NEXT = "io.github.ari1re.kanjilock.NEXT"

    /** Japanese locale span so CJK glyphs render with Japanese (not Chinese) forms. */
    fun ja(s: String): CharSequence = SpannableString(s).apply {
        setSpan(LocaleSpan(Locale.JAPANESE), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun nextIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1,
        Intent(ctx, NextReceiver::class.java).setAction(ACTION_NEXT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun widget(ctx: Context, e: Entry, romajiAll: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_card)
        bindHeader(rv, e, big = true)
        bindSentence(ctx, rv, e, romajiAll, R.layout.ruby_token_widget, budget = 15f)
        rv.setOnClickPendingIntent(R.id.root, openApp(ctx))
        rv.setOnClickPendingIntent(R.id.next, nextIntent(ctx))
        return rv
    }

    fun notifSmall(ctx: Context, e: Entry): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.notif_small)
        rv.setTextViewText(R.id.main, ja(e.text))
        rv.setTextViewTextSize(R.id.main, TypedValue.COMPLEX_UNIT_SP, when {
            e.text.length <= 1 -> 30f
            e.text.length <= 3 -> 24f
            else -> 18f
        })
        rv.setTextViewText(R.id.meaning, e.meaning)
        val sub = listOf(e.reading, e.romaji).filter { it.isNotBlank() }.joinToString("  ·  ")
        rv.setTextViewText(R.id.reading, ja(sub))
        rv.setViewVisibility(R.id.reading, if (sub.isBlank()) View.GONE else View.VISIBLE)
        return rv
    }

    fun notifBig(ctx: Context, e: Entry, romajiAll: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.notif_big)
        bindHeader(rv, e, big = false)
        bindSentence(ctx, rv, e, romajiAll, R.layout.ruby_token_notif, budget = 17f)
        return rv
    }

    private fun bindHeader(rv: RemoteViews, e: Entry, big: Boolean) {
        val wide = e.text.length > 4
        rv.setViewVisibility(R.id.main, if (wide) View.GONE else View.VISIBLE)
        rv.setViewVisibility(R.id.main_wide, if (wide) View.VISIBLE else View.GONE)
        rv.setTextViewText(R.id.main, ja(e.text))
        rv.setTextViewText(R.id.main_wide, ja(e.text))
        val size = when {
            e.text.length <= 1 -> if (big) 48f else 40f
            e.text.length <= 2 -> if (big) 36f else 30f
            else -> if (big) 28f else 24f
        }
        rv.setTextViewTextSize(R.id.main, TypedValue.COMPLEX_UNIT_SP, size)
        setOrHide(rv, R.id.reading, e.reading, japanese = true)
        setOrHide(rv, R.id.romaji, e.romaji)
        setOrHide(rv, R.id.meaning, e.meaning)
    }

    private fun setOrHide(rv: RemoteViews, id: Int, text: String, japanese: Boolean = false) {
        rv.setTextViewText(id, if (japanese) ja(text) else text)
        rv.setViewVisibility(id, if (text.isBlank()) View.GONE else View.VISIBLE)
    }

    private fun bindSentence(ctx: Context, rv: RemoteViews, e: Entry, romajiAll: Boolean, tokenLayout: Int, budget: Float) {
        rv.removeAllViews(R.id.sentence)
        val rows = rubyRows(ctx, e.example, romajiAll, tokenLayout, budget)
        rows.forEach { rv.addView(R.id.sentence, it) }
        rv.setViewVisibility(R.id.sentence, if (rows.isEmpty()) View.GONE else View.VISIBLE)
        setOrHide(rv, R.id.en, e.exampleEn)
    }

    /**
     * Lays tokens out as ruby (word on top, romaji underneath), wrapping into rows by an
     * approximate width budget measured in "kanji widths".
     */
    private fun rubyRows(ctx: Context, tokens: List<Token>, romajiAll: Boolean, tokenLayout: Int, budget: Float): List<RemoteViews> {
        val rows = mutableListOf<RemoteViews>()
        var row = RemoteViews(ctx.packageName, R.layout.ruby_row)
        var used = 0f
        var any = false
        for (t in tokens) {
            val show = t.romaji.isNotEmpty() && (romajiAll || t.hasKanji)
            val romaji = if (show) t.romaji else ""
            val w = maxOf(t.surface.length.toFloat(), romaji.length * 0.36f) + 0.3f
            val punctuation = t.romaji.isEmpty() && !t.hasKanji && t.surface.length == 1
            if (any && used + w > budget && !punctuation) {
                rows += row
                row = RemoteViews(ctx.packageName, R.layout.ruby_row)
                used = 0f
                any = false
            }
            val tok = RemoteViews(ctx.packageName, tokenLayout)
            tok.setTextViewText(R.id.token_surface, ja(t.surface))
            tok.setTextViewText(R.id.token_romaji, if (show) romaji else " ")
            row.addView(R.id.ruby_row, tok)
            used += w
            any = true
        }
        if (any) rows += row
        return rows.take(4)
    }
}
