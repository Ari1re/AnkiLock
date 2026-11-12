package io.github.ari1re.kanjilock

import android.content.Context

/** Picks the current card and pushes it to every surface (widgets + lock screen card). */
object Cards {
    /** True when the Anki source is selected and there's something to draw from. */
    fun usingAnki(ctx: Context, p: Prefs = Prefs(ctx)) = p.source == Source.ANKI && AnkiStore.hasDecks(ctx)

    fun pool(ctx: Context, p: Prefs = Prefs(ctx)): List<Entry> {
        if (usingAnki(ctx, p)) {
            val anki = AnkiStore.entries(ctx, p)
            if (anki.isNotEmpty()) return anki
        }
        val all = Deck.all(ctx)
        return all.filter { it.level in p.levels && it.type in p.types }.ifEmpty { all }
    }

    @Synchronized
    fun current(ctx: Context): Entry {
        val p = Prefs(ctx)
        val pool = pool(ctx, p)
        return pool.firstOrNull { it.id == p.currentId } ?: pick(p, pool)
    }

    @Synchronized
    fun advance(ctx: Context): Entry {
        val p = Prefs(ctx)
        val e = pick(p, pool(ctx, p))
        refresh(ctx, e)
        return e
    }

    fun refresh(ctx: Context, e: Entry = current(ctx)) {
        KanjiWidget.updateAll(ctx, e)
        LockCard.show(ctx, e)
    }

    private fun pick(p: Prefs, pool: List<Entry>): Entry {
        val recent = p.recent
        val recentSet = recent.toHashSet()
        var candidates = pool.filter { it.id !in recentSet && it.id != p.currentId }
        if (candidates.isEmpty()) candidates = pool.filter { it.id != p.currentId }.ifEmpty { pool }
        val e = candidates.random()
        p.currentId = e.id
        val keep = minOf(80, pool.size - 1).coerceAtLeast(0)
        p.recent = (recent + e.id).takeLast(keep)
        return e
    }
}
