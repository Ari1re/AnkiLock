package io.github.ari1re.kanjilock

import android.content.Context

enum class Source { DICTIONARY, ANKI }

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("kanjilock", Context.MODE_PRIVATE)

    var levels: Set<Int>
        get() = (sp.getStringSet("levels", null) ?: setOf("5")).mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = sp.edit().putStringSet("levels", v.map { it.toString() }.toSet()).apply()

    var types: Set<EntryType>
        get() = (sp.getStringSet("types", null) ?: setOf("kanji", "word", "phrase"))
            .mapNotNull { EntryType.of(it) }.toSet()
        set(v) = sp.edit().putStringSet("types", v.map { it.key }.toSet()).apply()

    /** Where cards come from. ANKI only takes effect when at least one deck is imported and active. */
    var source: Source
        get() = if (sp.getString("source", "dict") == "anki") Source.ANKI else Source.DICTIONARY
        set(v) = sp.edit().putString("source", if (v == Source.ANKI) "anki" else "dict").apply()

    /** Imported Anki deck ids that cards are drawn from. */
    var ankiActive: Set<String>
        get() = sp.getStringSet("ankiActive", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("ankiActive", v).apply()

    /** Minutes between new cards; 0 = a new card every time the screen locks. */
    var intervalMinutes: Int
        get() = sp.getInt("interval", 0)
        set(v) = sp.edit().putInt("interval", v).apply()

    /** false = romaji only under kanji; true = under every word. */
    var romajiAll: Boolean
        get() = sp.getBoolean("romajiAll", false)
        set(v) = sp.edit().putBoolean("romajiAll", v).apply()

    var lockNotification: Boolean
        get() = sp.getBoolean("lockNotif", true)
        set(v) = sp.edit().putBoolean("lockNotif", v).apply()

    /** Optional GitHub token so the updater can read releases of a private repo. */
    var githubToken: String?
        get() = sp.getString("ghToken", null)?.takeIf { it.isNotBlank() }
        set(v) = sp.edit().putString("ghToken", v).apply()

    var currentId: String?
        get() = sp.getString("current", null)
        set(v) = sp.edit().putString("current", v).apply()

    var recent: List<String>
        get() = sp.getString("recent", "")!!.split('\n').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("recent", v.joinToString("\n")).apply()
}
