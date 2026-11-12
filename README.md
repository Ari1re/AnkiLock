# KanjiLock

A minimal Japanese study card for your Android lock screen: one kanji, word or phrase at a time, with its
reading, meaning, and an example sentence with **romaji under the kanji**.

## Where it shows up

- **Lock screen card**: a silent, persistent notification styled as a card. It works on every phone.
  Expand it to see the example sentence. The **Next** button skips to a new card.
- **Widget**: home screen widget. On Android 16+ phones with *Settings → Display → Lock screen →
  Widgets on lock screen*, swipe left on the lock screen and add KanjiLock there too.
- A new card appears every 15 min to daily (you choose), or tap → / Next.

## Card sources

- **JLPT dictionary** (built in): choose levels N5–N1 and any mix of **Kanji / Words / Phrases**.
  The data lives in `tools/build_data.py`; run it to validate and regenerate `app/src/main/assets/entries.json`.
  JLPT has had no official lists since 2010, so levels follow the common community lists.
- **My Anki decks**: import any `.apkg` (Anki desktop *File → Export*, or AnkiDroid *Export deck*),
  then switch "Cards from" to *My Anki decks*. Random cards come from the decks you tick.
  - Fields are auto-detected (Expression/Word/Front, Reading, Meaning/Back, Sentence, Sentence translation);
    change them under *Fields…* on each deck.
  - Anki furigana (`日本語[にほんご]`) becomes ruby with romaji underneath.
  - Both new (`collection.anki21b`, zstd) and legacy exports are supported. If an import fails, export again
    with *Support older Anki versions* ticked.

## Updates

Settings → **Updates** checks this repo's rolling `latest` release (on open, and twice a day in the background),
downloads the APK, and installs it with the system installer. KanjiLock relaunches when the update finishes.
The first time, Android asks you to allow KanjiLock to install apps. After the first self-installed update,
later ones usually install without a prompt.

While the repo is private, paste a fine-grained GitHub token with read-only **Contents** access to
`Ari1re/AnkiLock` into Settings → Updates. A public repo needs no token.

## Builds (GitHub Actions)

`.github/workflows/android.yml` builds a signed release APK on every push and PR. Every push to `main`
refreshes the **latest** pre-release, so the newest APK is one tap away on your phone. Push a `v*` tag for a
versioned release.

Signing uses `signing/ci.jks` unless you set the `KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD` secrets. Keep the repo private if you rely on the committed key.
