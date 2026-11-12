package io.github.ari1re.kanjilock

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import com.github.luben.zstd.ZstdInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/** Which note field feeds which part of the card. -1 = none. */
data class FieldMap(val front: Int, val reading: Int, val meaning: Int, val sentence: Int, val sentenceEn: Int) {
    fun toJson() = JSONArray(listOf(front, reading, meaning, sentence, sentenceEn))
    fun with(role: Role, idx: Int) = when (role) {
        Role.FRONT -> copy(front = idx)
        Role.READING -> copy(reading = idx)
        Role.MEANING -> copy(meaning = idx)
        Role.SENTENCE -> copy(sentence = idx)
        Role.SENTENCE_EN -> copy(sentenceEn = idx)
    }
    fun get(role: Role) = when (role) {
        Role.FRONT -> front
        Role.READING -> reading
        Role.MEANING -> meaning
        Role.SENTENCE -> sentence
        Role.SENTENCE_EN -> sentenceEn
    }

    companion object {
        fun fromJson(a: JSONArray) = FieldMap(a.optInt(0, 0), a.optInt(1, -1), a.optInt(2, -1), a.optInt(3, -1), a.optInt(4, -1))

        /** Guess from field names (Core 2k/6k, Kaishi, JLPT decks, plain Basic…). */
        fun guess(fields: List<String>): FieldMap {
            val n = fields.map { it.lowercase() }
            fun find(pred: (String) -> Boolean) = n.indexOfFirst(pred)
            val media = Regex("audio|sound|image|picture|pic|img|furigana-?kana")
            val isSentence = { s: String -> ("sentence" in s || "example" in s) && !media.containsMatchIn(s) }
            val isTrans = { s: String -> Regex("trans|english|meaning|\\ben\\b|eng").containsMatchIn(s) }

            val sentenceEn = find { isSentence(it) && isTrans(it) }
            val sentence = find { isSentence(it) && !isTrans(it) && "furigana" in it }.takeIf { it >= 0 }
                ?: find { isSentence(it) && !isTrans(it) && !Regex("kana|reading|romaji").containsMatchIn(it) }
            val reading = find { !isSentence(it) && !media.containsMatchIn(it) && Regex("reading|kana|furigana|yomi|pronunciation").containsMatchIn(it) }
            val meaning = find { !isSentence(it) && Regex("meaning|definition|english|gloss|translation|back|answer").containsMatchIn(it) }
            var front = find {
                !isSentence(it) && !media.containsMatchIn(it) &&
                    Regex("expression|vocab|word|kanji|front|term|japanese|target|question|key").containsMatchIn(it) &&
                    !Regex("reading|kana|meaning|english").containsMatchIn(it)
            }
            if (front < 0) front = (fields.indices).firstOrNull { it != meaning && it != reading } ?: 0
            val meaningFinal = if (meaning >= 0) meaning else fields.indices.firstOrNull { it != front && it != reading && it != sentence } ?: -1
            return FieldMap(front, reading, meaningFinal, sentence, sentenceEn)
        }
    }
}

enum class Role(val label: String) {
    FRONT("Card (front)"), READING("Reading"), MEANING("Meaning"), SENTENCE("Example sentence"), SENTENCE_EN("Sentence translation")
}

data class AnkiNoteType(val id: Long, val name: String, val fields: List<String>, val map: FieldMap, val noteCount: Int)

data class AnkiDeckMeta(val id: String, val name: String, val noteCount: Int, val noteTypes: List<AnkiNoteType>)

object AnkiStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "anki").apply { mkdirs() }
    private fun metaFile(ctx: Context, id: String) = File(dir(ctx), "$id.meta.json")
    private fun notesFile(ctx: Context, id: String) = File(dir(ctx), "$id.notes.json")

    @Volatile private var entryCache: Pair<String, List<Entry>>? = null

    fun decks(ctx: Context): List<AnkiDeckMeta> =
        dir(ctx).listFiles { f -> f.name.endsWith(".meta.json") }.orEmpty()
            .mapNotNull { runCatching { readMeta(it) }.getOrNull() }
            .sortedBy { it.name.lowercase() }

    fun hasDecks(ctx: Context) = dir(ctx).listFiles { f -> f.name.endsWith(".meta.json") }.orEmpty().isNotEmpty()

    private fun readMeta(f: File): AnkiDeckMeta {
        val o = JSONObject(f.readText())
        val nts = o.getJSONArray("noteTypes")
        return AnkiDeckMeta(
            id = o.getString("id"),
            name = o.getString("name"),
            noteCount = o.getInt("count"),
            noteTypes = (0 until nts.length()).map { i ->
                val t = nts.getJSONObject(i)
                val fa = t.getJSONArray("fields")
                AnkiNoteType(
                    id = t.getLong("id"),
                    name = t.getString("name"),
                    fields = (0 until fa.length()).map { fa.getString(it) },
                    map = FieldMap.fromJson(t.getJSONArray("map")),
                    noteCount = t.optInt("count", 0),
                )
            },
        )
    }

    fun saveMeta(ctx: Context, meta: AnkiDeckMeta) {
        val o = JSONObject()
            .put("id", meta.id).put("name", meta.name).put("count", meta.noteCount)
            .put("noteTypes", JSONArray(meta.noteTypes.map { t ->
                JSONObject().put("id", t.id).put("name", t.name).put("fields", JSONArray(t.fields))
                    .put("map", t.map.toJson()).put("count", t.noteCount)
            }))
        metaFile(ctx, meta.id).writeText(o.toString())
        entryCache = null
    }

    fun setMapping(ctx: Context, deckId: String, noteTypeId: Long, map: FieldMap) {
        val meta = decks(ctx).firstOrNull { it.id == deckId } ?: return
        saveMeta(ctx, meta.copy(noteTypes = meta.noteTypes.map { if (it.id == noteTypeId) it.copy(map = map) else it }))
    }

    fun delete(ctx: Context, id: String) {
        metaFile(ctx, id).delete()
        notesFile(ctx, id).delete()
        entryCache = null
    }

    /** Cards from the active decks (all decks if none are explicitly active). */
    fun entries(ctx: Context, prefs: Prefs): List<Entry> {
        val decks = decks(ctx)
        val active = prefs.ankiActive
        val chosen = decks.filter { active.isEmpty() || it.id in active }.ifEmpty { decks }
        val key = chosen.joinToString("|") { it.id + ":" + metaFile(ctx, it.id).lastModified() }
        entryCache?.let { if (it.first == key) return it.second }
        val list = chosen.flatMap { deck -> loadEntries(ctx, deck) }
        entryCache = key to list
        return list
    }

    private fun loadEntries(ctx: Context, deck: AnkiDeckMeta): List<Entry> {
        val f = notesFile(ctx, deck.id)
        if (!f.exists()) return emptyList()
        val types = deck.noteTypes.associateBy { it.id }
        val arr = JSONArray(f.readText())
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val n = arr.getJSONArray(i)
            val noteId = n.getLong(0)
            val nt = types[n.getLong(1)] ?: continue
            val fa = n.getJSONArray(2)
            val fields = (0 until fa.length()).map { fa.getString(it) }
            toEntry(deck, noteId, fields, nt.map)?.let(out::add)
        }
        return out
    }

    private fun toEntry(deck: AnkiDeckMeta, noteId: Long, fields: List<String>, map: FieldMap): Entry? {
        fun field(i: Int) = fields.getOrNull(i)?.trim().orEmpty()
        val frontRaw = field(map.front)
        val text = Furigana.plain(frontRaw).lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (text.isEmpty()) return null
        val readingRaw = field(map.reading)
        val reading = when {
            readingRaw.isNotEmpty() -> if (Furigana.hasRuby(readingRaw)) Furigana.kana(readingRaw) else readingRaw
            Furigana.hasRuby(frontRaw) -> Furigana.kana(frontRaw)
            text.all { isKana(it) } -> text
            else -> ""
        }.replace('\n', ' ').trim()
        val romaji = if (reading.isNotEmpty() && reading.all { isKana(it) || it in " ・、,/" })
            Kana.toRomaji(reading.replace("・", ", ").replace("、", ", ")) else ""
        val meaning = Furigana.plain(field(map.meaning)).replace(Regex("\\s*\n+\\s*"), "; ").take(160)
        val sentence = field(map.sentence).replace('\n', ' ')
        return Entry(
            id = "anki:${deck.id}:$noteId",
            type = EntryType.ANKI,
            level = 0,
            text = text.take(40),
            reading = reading.take(60),
            romaji = romaji,
            meaning = meaning,
            example = if (sentence.isBlank()) emptyList() else Furigana.tokens(sentence.take(120)),
            exampleEn = Furigana.plain(field(map.sentenceEn)).replace('\n', ' ').take(160),
            tag = deck.name,
        )
    }

    // ---------------------------------------------------------------- import

    class ImportException(msg: String) : Exception(msg)

    /** Imports an .apkg / .colpkg. Runs blocking — call off the main thread. */
    fun import(ctx: Context, uri: Uri): AnkiDeckMeta {
        val work = File(ctx.cacheDir, "anki-import").apply { deleteRecursively(); mkdirs() }
        try {
            val found = mutableMapOf<String, File>()
            ctx.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.name in setOf("collection.anki21b", "collection.anki21", "collection.anki2")) {
                            val f = File(work, e.name)
                            f.outputStream().use { zip.copyTo(it) }
                            found[e.name] = f
                        }
                    }
                }
            } ?: throw ImportException("Couldn't open that file.")

            val db = when {
                "collection.anki21b" in found -> File(work, "col.db").also { out ->
                    try {
                        ZstdInputStream(found.getValue("collection.anki21b").inputStream().buffered()).use { zin ->
                            out.outputStream().use { zin.copyTo(it) }
                        }
                    } catch (t: Throwable) {
                        throw ImportException("Couldn't decompress this deck. In Anki, export again with \"Support older Anki versions\" ticked.")
                    }
                }
                "collection.anki21" in found -> found.getValue("collection.anki21")
                "collection.anki2" in found -> found.getValue("collection.anki2")
                else -> throw ImportException("That isn't an Anki deck (.apkg) — no collection inside.")
            }

            val name = displayName(ctx, uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Anki deck"
            val id = UUID.randomUUID().toString().take(8)
            val meta = readCollection(ctx, db, id, name)
            return meta
        } finally {
            work.deleteRecursively()
        }
    }

    private fun readCollection(ctx: Context, dbFile: File, id: String, name: String): AnkiDeckMeta {
        val db = SQLiteDatabase.openDatabase(
            dbFile.path, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        db.use {
            val types = readNoteTypes(db)
            val counts = mutableMapOf<Long, Int>()
            val notes = JSONArray()
            db.rawQuery("SELECT id, mid, flds FROM notes", null).use { c ->
                while (c.moveToNext()) {
                    val mid = c.getLong(1)
                    val fields = c.getString(2).split('\u001f').map(::cleanField)
                    if (fields.all { it.isBlank() }) continue
                    notes.put(JSONArray().put(c.getLong(0)).put(mid).put(JSONArray(fields)))
                    counts[mid] = (counts[mid] ?: 0) + 1
                }
            }
            if (notes.length() == 0) throw ImportException("No notes found in that deck.")
            // Legacy placeholder Anki writes into collection.anki2 when the real data is in anki21b.
            if (notes.length() == 1 && notes.getJSONArray(0).getJSONArray(2).toString().contains("update to the latest Anki", ignoreCase = true))
                throw ImportException("This export needs a newer importer. In Anki, export again with \"Support older Anki versions\" ticked.")

            val used = types.filter { (counts[it.id] ?: 0) > 0 }.map { it.copy(noteCount = counts[it.id] ?: 0) }
            notesFile(ctx, id).writeText(notes.toString())
            val meta = AnkiDeckMeta(id, name, notes.length(), used)
            saveMeta(ctx, meta)
            return meta
        }
    }

    private fun readNoteTypes(db: SQLiteDatabase): List<AnkiNoteType> {
        // Anki ≥2.1.28 (schema 18): notetypes + fields tables.
        val modern = runCatching {
            val names = mutableMapOf<Long, String>()
            db.rawQuery("SELECT id, name FROM notetypes", null).use { c ->
                while (c.moveToNext()) names[c.getLong(0)] = c.getString(1)
            }
            val fields = mutableMapOf<Long, MutableList<Pair<Int, String>>>()
            db.rawQuery("SELECT ntid, ord, name FROM fields", null).use { c ->
                while (c.moveToNext()) fields.getOrPut(c.getLong(0)) { mutableListOf() } += c.getInt(1) to c.getString(2)
            }
            names.map { (ntid, n) ->
                val fl = fields[ntid].orEmpty().sortedBy { it.first }.map { it.second }
                AnkiNoteType(ntid, n, fl, FieldMap.guess(fl), 0)
            }
        }.getOrNull()
        if (!modern.isNullOrEmpty()) return modern

        // Older collections: JSON in col.models.
        db.rawQuery("SELECT models FROM col", null).use { c ->
            if (!c.moveToFirst()) return emptyList()
            val models = JSONObject(c.getString(0))
            return models.keys().asSequence().map { key ->
                val m = models.getJSONObject(key)
                val flds = m.getJSONArray("flds")
                val fl = (0 until flds.length()).map { flds.getJSONObject(it) }
                    .sortedBy { it.optInt("ord") }.map { it.getString("name") }
                AnkiNoteType(m.optLong("id", key.toLong()), m.optString("name", "Note"), fl, FieldMap.guess(fl), 0)
            }.toList()
        }
    }

    private val sound = Regex("""\[sound:[^\]]*]""")
    private val blockBreak = Regex("""(?i)<br\s*/?>|</div>|</p>|</li>""")

    /** HTML → text, keeping Anki furigana `[...]` and line breaks. */
    fun cleanField(raw: String): String {
        if (raw.isBlank()) return ""
        val s = raw.replace(sound, "").replace(blockBreak, "\n")
        val text = Html.fromHtml(s.replace("\n", "<br>"), Html.FROM_HTML_MODE_LEGACY).toString()
        return text.replace("\uFFFC", "").replace('\u00A0', ' ')
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun displayName(ctx: Context, uri: Uri): String? =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
