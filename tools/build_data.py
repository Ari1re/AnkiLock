#!/usr/bin/env python3
"""Builds app/src/main/assets/entries.json — the built-in JLPT dictionary.

Each entry: (level, text, reading, romaji, meaning, example, example_en)
Example sentences are tokenised:  surface[romaji]  separated by spaces; a token with no
[brackets] is punctuation (no romaji). The app shows romaji under kanji tokens (or every
token, per settings).

JLPT has no official kanji/vocab lists since 2010 — levels follow the common community lists.
Run:  python3 tools/build_data.py   (validates, then writes the JSON)
"""
import json, re, sys
from pathlib import Path

TOKEN = re.compile(r"([^\s\[\]]+)(?:\[([^\]]*)\])?")

KANJI = [
    # ---- N5 ----
    (5, "日", "ニチ・ジツ / ひ・か", "nichi, jitsu / hi, ka", "day, sun", "今日[kyou] は[wa] いい[ii] 天気[tenki] です[desu] ね[ne] 。", "The weather is nice today, isn't it?"),
    (5, "月", "ゲツ・ガツ / つき", "getsu, gatsu / tsuki", "moon, month", "来月[raigetsu] 日本[nihon] に[ni] 行きます[ikimasu] 。", "I'm going to Japan next month."),
    (5, "火", "カ / ひ", "ka / hi", "fire", "火曜日[kayoubi] に[ni] 会いましょう[aimashou] 。", "Let's meet on Tuesday."),
    (5, "水", "スイ / みず", "sui / mizu", "water", "水[mizu] を[o] 一杯[ippai] ください[kudasai] 。", "A glass of water, please."),
    (5, "木", "モク・ボク / き", "moku, boku / ki", "tree, wood", "公園[kouen] に[ni] 大きい[ookii] 木[ki] が[ga] あります[arimasu] 。", "There is a big tree in the park."),
    (5, "金", "キン・コン / かね", "kin, kon / kane", "gold, money", "お金[okane] が[ga] ありません[arimasen] 。", "I don't have any money."),
    (5, "土", "ド・ト / つち", "do, to / tsuchi", "earth, soil", "土曜日[doyoubi] は[wa] 休み[yasumi] です[desu] 。", "Saturday is my day off."),
    (5, "人", "ジン・ニン / ひと", "jin, nin / hito", "person", "あの[ano] 人[hito] は[wa] 先生[sensei] です[desu] 。", "That person is a teacher."),
    (5, "山", "サン / やま", "san / yama", "mountain", "山[yama] に[ni] 登りたい[noboritai] です[desu] 。", "I want to climb a mountain."),
    (5, "川", "セン / かわ", "sen / kawa", "river", "川[kawa] で[de] 泳ぎました[oyogimashita] 。", "I swam in the river."),
    (5, "口", "コウ・ク / くち", "kou, ku / kuchi", "mouth", "口[kuchi] を[o] 開けて[akete] ください[kudasai] 。", "Please open your mouth."),
    (5, "目", "モク / め", "moku / me", "eye", "目[me] が[ga] 痛い[itai] です[desu] 。", "My eyes hurt."),
    (5, "手", "シュ / て", "shu / te", "hand", "手[te] を[o] 洗って[aratte] ください[kudasai] 。", "Please wash your hands."),
    (5, "大", "ダイ・タイ / おお(きい)", "dai, tai / oo(kii)", "big", "この[kono] 部屋[heya] は[wa] 大きい[ookii] です[desu] 。", "This room is big."),
    (5, "小", "ショウ / ちい(さい)・こ", "shou / chii(sai), ko", "small", "小さい[chiisai] 猫[neko] が[ga] います[imasu] 。", "There's a small cat."),
    (5, "中", "チュウ / なか", "chuu / naka", "middle, inside", "かばん[kaban] の[no] 中[naka] に[ni] 本[hon] が[ga] あります[arimasu] 。", "There's a book in the bag."),
    (5, "上", "ジョウ / うえ・あ(がる)", "jou / ue, a(garu)", "up, above", "机[tsukue] の[no] 上[ue] に[ni] ペン[pen] が[ga] あります[arimasu] 。", "There's a pen on the desk."),
    (5, "下", "カ・ゲ / した・さ(がる)", "ka, ge / shita, sa(garu)", "down, below", "いす[isu] の[no] 下[shita] に[ni] 犬[inu] が[ga] います[imasu] 。", "There's a dog under the chair."),
    (5, "本", "ホン / もと", "hon / moto", "book, origin", "毎晩[maiban] 本[hon] を[o] 読みます[yomimasu] 。", "I read a book every night."),
    (5, "学", "ガク / まな(ぶ)", "gaku / mana(bu)", "study, learning", "大学[daigaku] で[de] 日本語[nihongo] を[o] 勉強しています[benkyou shiteimasu] 。", "I'm studying Japanese at university."),
    (5, "生", "セイ・ショウ / い(きる)・う(まれる)", "sei, shou / i(kiru), u(mareru)", "life, birth", "私[watashi] は[wa] 学生[gakusei] です[desu] 。", "I'm a student."),
    (5, "先", "セン / さき", "sen / saki", "ahead, previous", "先週[senshuu] 京都[kyouto] に[ni] 行きました[ikimashita] 。", "I went to Kyoto last week."),
    (5, "年", "ネン / とし", "nen / toshi", "year", "来年[rainen] 日本[nihon] に[ni] 住みたい[sumitai] です[desu] 。", "I want to live in Japan next year."),
    (5, "時", "ジ / とき", "ji / toki", "time, hour", "今[ima] 何時[nanji] です[desu] か[ka] 。", "What time is it now?"),
    (5, "食", "ショク / た(べる)", "shoku / ta(beru)", "eat, food", "朝ご飯[asagohan] を[o] 食べました[tabemashita] か[ka] 。", "Did you eat breakfast?"),
    (5, "飲", "イン / の(む)", "in / no(mu)", "drink", "毎朝[maiasa] コーヒー[koohii] を[o] 飲みます[nomimasu] 。", "I drink coffee every morning."),
    (5, "見", "ケン / み(る)", "ken / mi(ru)", "see, look", "昨日[kinou] 映画[eiga] を[o] 見ました[mimashita] 。", "I watched a movie yesterday."),
    (5, "行", "コウ・ギョウ / い(く)", "kou, gyou / i(ku)", "go", "駅[eki] まで[made] 歩いて[aruite] 行きます[ikimasu] 。", "I walk to the station."),
    (5, "来", "ライ / く(る)", "rai / ku(ru)", "come", "友達[tomodachi] が[ga] 家[ie] に[ni] 来ました[kimashita] 。", "A friend came to my house."),
    (5, "雨", "ウ / あめ", "u / ame", "rain", "明日[ashita] は[wa] 雨[ame] が[ga] 降る[furu] でしょう[deshou] 。", "It will probably rain tomorrow."),
    (5, "電", "デン", "den", "electricity", "電車[densha] で[de] 会社[kaisha] に[ni] 行きます[ikimasu] 。", "I go to work by train."),
    (5, "名", "メイ・ミョウ / な", "mei, myou / na", "name", "お名前[onamae] は[wa] 何[nan] です[desu] か[ka] 。", "What is your name?"),
    # ---- N4 ----
    (4, "会", "カイ / あ(う)", "kai / a(u)", "meet, association", "駅[eki] で[de] 友達[tomodachi] に[ni] 会いました[aimashita] 。", "I met a friend at the station."),
    (4, "同", "ドウ / おな(じ)", "dou / ona(ji)", "same", "私[watashi] たち[tachi] は[wa] 同じ[onaji] クラス[kurasu] です[desu] 。", "We're in the same class."),
    (4, "事", "ジ / こと", "ji / koto", "thing, matter", "仕事[shigoto] は[wa] 何時[nanji] に[ni] 終わります[owarimasu] か[ka] 。", "What time does work end?"),
    (4, "自", "ジ・シ / みずか(ら)", "ji, shi / mizuka(ra)", "self", "自分[jibun] で[de] 作りました[tsukurimashita] 。", "I made it myself."),
    (4, "社", "シャ / やしろ", "sha / yashiro", "company, shrine", "兄[ani] は[wa] 大きな[ookina] 会社[kaisha] で[de] 働いています[hataraiteimasu] 。", "My older brother works at a big company."),
    (4, "発", "ハツ・ホツ", "hatsu, hotsu", "departure, emit", "電車[densha] は[wa] 九時[kuji] に[ni] 出発します[shuppatsu shimasu] 。", "The train departs at nine."),
    (4, "者", "シャ / もの", "sha / mono", "person (who does)", "父[chichi] は[wa] 医者[isha] です[desu] 。", "My father is a doctor."),
    (4, "地", "チ・ジ", "chi, ji", "ground, land", "地図[chizu] を[o] 見せて[misete] ください[kudasai] 。", "Please show me the map."),
    (4, "業", "ギョウ・ゴウ / わざ", "gyou, gou / waza", "business, work", "授業[jugyou] は[wa] 三時[sanji] に[ni] 終わります[owarimasu] 。", "Class ends at three."),
    (4, "方", "ホウ / かた", "hou / kata", "direction, way", "この[kono] 漢字[kanji] の[no] 読み方[yomikata] を[o] 教えて[oshiete] ください[kudasai] 。", "Please tell me how to read this kanji."),
    (4, "新", "シン / あたら(しい)", "shin / atara(shii)", "new", "新しい[atarashii] 靴[kutsu] を[o] 買いました[kaimashita] 。", "I bought new shoes."),
    (4, "場", "ジョウ / ば", "jou / ba", "place", "駐車場[chuushajou] は[wa] どこ[doko] です[desu] か[ka] 。", "Where is the parking lot?"),
    (4, "員", "イン", "in", "member, staff", "彼[kare] は[wa] 会社員[kaishain] です[desu] 。", "He's an office worker."),
    (4, "立", "リツ / た(つ)", "ritsu / ta(tsu)", "stand", "ドア[doa] の[no] 前[mae] に[ni] 立って[tatte] ください[kudasai] 。", "Please stand in front of the door."),
    (4, "開", "カイ / あ(ける)・ひら(く)", "kai / a(keru), hira(ku)", "open", "窓[mado] を[o] 開けても[aketemo] いい[ii] です[desu] か[ka] 。", "May I open the window?"),
    (4, "思", "シ / おも(う)", "shi / omo(u)", "think", "明日[ashita] は[wa] 晴れる[hareru] と[to] 思います[omoimasu] 。", "I think it'll be sunny tomorrow."),
    (4, "急", "キュウ / いそ(ぐ)", "kyuu / iso(gu)", "hurry, sudden", "急いで[isoide] ください[kudasai] 。", "Please hurry."),
    (4, "料", "リョウ", "ryou", "fee, materials", "母[haha] は[wa] 料理[ryouri] が[ga] 上手[jouzu] です[desu] 。", "My mother is good at cooking."),
    (4, "旅", "リョ / たび", "ryo / tabi", "trip, travel", "夏休み[natsuyasumi] に[ni] 旅行します[ryokou shimasu] 。", "I'll travel during summer vacation."),
    (4, "写", "シャ / うつ(す)", "sha / utsu(su)", "copy, photograph", "ここ[koko] で[de] 写真[shashin] を[o] 撮っても[tottemo] いい[ii] です[desu] か[ka] 。", "May I take photos here?"),
    (4, "夜", "ヤ / よる", "ya / yoru", "night", "夜[yoru] 遅く[osoku] まで[made] 起きていました[okiteimashita] 。", "I was up until late at night."),
    # ---- N3 ----
    (3, "政", "セイ・ショウ / まつりごと", "sei, shou / matsurigoto", "politics, government", "政治[seiji] に[ni] 興味[kyoumi] が[ga] あります[arimasu] 。", "I'm interested in politics."),
    (3, "議", "ギ", "gi", "deliberation, discussion", "会議[kaigi] は[wa] 二時[niji] から[kara] です[desu] 。", "The meeting starts at two."),
    (3, "民", "ミン / たみ", "min / tami", "people, nation", "国民[kokumin] の[no] 声[koe] を[o] 聞く[kiku] べき[beki] だ[da] 。", "We should listen to the voice of the people."),
    (3, "連", "レン / つ(れる)", "ren / tsu(reru)", "take along, link", "犬[inu] を[o] 散歩[sanpo] に[ni] 連れて[tsurete] 行きます[ikimasu] 。", "I take the dog for a walk."),
    (3, "対", "タイ・ツイ", "tai, tsui", "opposite, versus", "その[sono] 意見[iken] に[ni] 反対[hantai] です[desu] 。", "I'm against that opinion."),
    (3, "部", "ブ", "bu", "section, part", "部屋[heya] を[o] 掃除しました[souji shimashita] 。", "I cleaned my room."),
    (3, "合", "ゴウ / あ(う)", "gou / a(u)", "fit, join", "時間[jikan] に[ni] 間に合いました[maniaimashita] 。", "I made it in time."),
    (3, "市", "シ / いち", "shi / ichi", "city, market", "市役所[shiyakusho] は[wa] 駅[eki] の[no] 近く[chikaku] に[ni] あります[arimasu] 。", "City hall is near the station."),
    (3, "内", "ナイ / うち", "nai / uchi", "inside, within", "案内して[annai shite] くれて[kurete] ありがとう[arigatou] 。", "Thanks for showing me around."),
    (3, "相", "ソウ・ショウ / あい", "sou, shou / ai", "mutual, together", "先生[sensei] に[ni] 相談しました[soudan shimashita] 。", "I consulted my teacher."),
    (3, "定", "テイ・ジョウ / さだ(める)", "tei, jou / sada(meru)", "determine, fix", "週末[shuumatsu] の[no] 予定[yotei] は[wa] あります[arimasu] か[ka] 。", "Do you have plans for the weekend?"),
    (3, "回", "カイ / まわ(る)", "kai / mawa(ru)", "times, turn around", "一日[ichinichi] に[ni] 三回[sankai] 薬[kusuri] を[o] 飲みます[nomimasu] 。", "I take medicine three times a day."),
    (3, "選", "セン / えら(ぶ)", "sen / era(bu)", "choose", "好きな[sukina] 色[iro] を[o] 選んで[erande] ください[kudasai] 。", "Please pick a color you like."),
    (3, "米", "ベイ・マイ / こめ", "bei, mai / kome", "rice, America", "米[kome] を[o] 炊きました[takimashita] 。", "I cooked rice."),
    (3, "実", "ジツ / み・みの(る)", "jitsu / mi, mino(ru)", "truth, fruit", "実は[jitsu wa] 、 明日[ashita] 休みます[yasumimasu] 。", "Actually, I'm taking tomorrow off."),
    (3, "感", "カン", "kan", "feeling, sense", "日本[nihon] の[no] 文化[bunka] に[ni] 感動しました[kandou shimashita] 。", "I was moved by Japanese culture."),
    # ---- N2 ----
    (2, "党", "トウ", "tou", "party, faction", "新しい[atarashii] 政党[seitou] が[ga] できた[dekita] 。", "A new political party was formed."),
    (2, "協", "キョウ", "kyou", "cooperation", "みんな[minna] で[de] 協力しましょう[kyouryoku shimashou] 。", "Let's all work together."),
    (2, "総", "ソウ", "sou", "general, total", "総理大臣[souri daijin] が[ga] 会見[kaiken] を[o] 開いた[hiraita] 。", "The prime minister held a press conference."),
    (2, "区", "ク", "ku", "ward, district", "私[watashi] は[wa] 新宿区[shinjuku-ku] に[ni] 住んでいます[sundeimasu] 。", "I live in Shinjuku ward."),
    (2, "領", "リョウ", "ryou", "territory, receive", "領収書[ryoushuusho] を[o] ください[kudasai] 。", "A receipt, please."),
    (2, "県", "ケン", "ken", "prefecture", "出身[shusshin] は[wa] 何県[nanken] です[desu] か[ka] 。", "Which prefecture are you from?"),
    (2, "設", "セツ / もう(ける)", "setsu / mou(keru)", "establish, set up", "アプリ[apuri] の[no] 設定[settei] を[o] 変えました[kaemashita] 。", "I changed the app's settings."),
    (2, "改", "カイ / あらた(める)", "kai / arata(meru)", "reform, revise", "駅[eki] の[no] 改札[kaisatsu] で[de] 待っています[matteimasu] 。", "I'm waiting at the station ticket gate."),
    (2, "府", "フ", "fu", "urban prefecture, government office", "京都府[kyouto-fu] は[wa] 歴史[rekishi] が[ga] 長い[nagai] 。", "Kyoto Prefecture has a long history."),
    (2, "際", "サイ / きわ", "sai / kiwa", "occasion, edge", "国際[kokusai] 交流[kouryuu] の[no] イベント[ibento] に[ni] 参加しました[sanka shimashita] 。", "I took part in an international exchange event."),
    (2, "判", "ハン・バン", "han, ban", "judgment, seal", "その[sono] 映画[eiga] は[wa] 評判[hyouban] が[ga] いい[ii] 。", "That movie has a good reputation."),
    (2, "済", "サイ / す(む)", "sai / su(mu)", "finish, settle", "支払い[shiharai] は[wa] もう[mou] 済みました[sumimashita] 。", "The payment is already done."),
    # ---- N1 ----
    (1, "憲", "ケン", "ken", "constitution, law", "憲法[kenpou] の[no] 改正[kaisei] に[ni] ついて[tsuite] 議論[giron] が[ga] 続いている[tsuzuiteiru] 。", "Debate continues over amending the constitution."),
    (1, "衆", "シュウ", "shuu", "masses, multitude", "観衆[kanshuu] から[kara] 大きな[ookina] 拍手[hakushu] が[ga] 起きた[okita] 。", "Loud applause rose from the audience."),
    (1, "拠", "キョ・コ", "kyo, ko", "basis, foothold", "証拠[shouko] が[ga] 見つからない[mitsukaranai] 。", "No evidence can be found."),
    (1, "抑", "ヨク / おさ(える)", "yoku / osa(eru)", "suppress, restrain", "怒り[ikari] を[o] 抑える[osaeru] の[no] は[wa] 難しい[muzukashii] 。", "It's hard to hold back anger."),
    (1, "措", "ソ", "so", "set aside, measure", "政府[seifu] は[wa] 新しい[atarashii] 措置[sochi] を[o] 取った[totta] 。", "The government took new measures."),
    (1, "携", "ケイ / たずさ(える)", "kei / tazusa(eru)", "carry, cooperate", "携帯[keitai] を[o] 家[ie] に[ni] 忘れた[wasureta] 。", "I left my phone at home."),
    (1, "謙", "ケン", "ken", "humble, modest", "彼[kare] は[wa] いつも[itsumo] 謙虚[kenkyo] だ[da] 。", "He's always modest."),
    (1, "魅", "ミ", "mi", "charm, fascinate", "この[kono] 町[machi] の[no] 魅力[miryoku] は[wa] 静けさ[shizukesa] だ[da] 。", "This town's charm is its quiet."),
    (1, "傾", "ケイ / かたむ(く)", "kei / katamu(ku)", "lean, tendency", "物価[bukka] が[ga] 上がる[agaru] 傾向[keikou] に[ni] ある[aru] 。", "Prices are tending to rise."),
    (1, "慎", "シン / つつし(む)", "shin / tsutsushi(mu)", "careful, discreet", "慎重に[shinchou ni] 判断して[handan shite] ください[kudasai] 。", "Please judge carefully."),
    (1, "綻", "タン / ほころ(びる)", "tan / hokoro(biru)", "come apart, unravel", "計画[keikaku] が[ga] 破綻した[hatan shita] 。", "The plan fell apart."),
    (1, "奨", "ショウ", "shou", "encourage, urge", "大学[daigaku] から[kara] 奨学金[shougakukin] を[o] もらった[moratta] 。", "I received a scholarship from the university."),
]

WORDS = [
    # ---- N5 ----
    (5, "食べ物", "たべもの", "tabemono", "food", "好きな[sukina] 食べ物[tabemono] は[wa] 何[nan] です[desu] か[ka] 。", "What's your favorite food?"),
    (5, "友達", "ともだち", "tomodachi", "friend", "友達[tomodachi] と[to] 映画[eiga] を[o] 見ました[mimashita] 。", "I watched a movie with a friend."),
    (5, "天気", "てんき", "tenki", "weather", "今日[kyou] は[wa] 天気[tenki] が[ga] いい[ii] です[desu] ね[ne] 。", "Nice weather today, isn't it?"),
    (5, "電車", "でんしゃ", "densha", "train", "電車[densha] が[ga] 遅れています[okureteimasu] 。", "The train is delayed."),
    (5, "先生", "せんせい", "sensei", "teacher", "先生[sensei] 、 質問[shitsumon] が[ga] あります[arimasu] 。", "Teacher, I have a question."),
    (5, "毎日", "まいにち", "mainichi", "every day", "毎日[mainichi] 日本語[nihongo] を[o] 勉強します[benkyou shimasu] 。", "I study Japanese every day."),
    (5, "朝ご飯", "あさごはん", "asagohan", "breakfast", "朝ご飯[asagohan] に[ni] パン[pan] を[o] 食べました[tabemashita] 。", "I had bread for breakfast."),
    (5, "駅", "えき", "eki", "station", "駅[eki] は[wa] どこ[doko] です[desu] か[ka] 。", "Where is the station?"),
    (5, "時間", "じかん", "jikan", "time", "時間[jikan] が[ga] ありません[arimasen] 。", "I don't have time."),
    (5, "買い物", "かいもの", "kaimono", "shopping", "週末[shuumatsu] に[ni] 買い物[kaimono] に[ni] 行きます[ikimasu] 。", "I'll go shopping on the weekend."),
    (5, "病院", "びょういん", "byouin", "hospital", "病院[byouin] に[ni] 行った[itta] ほう[hou] が[ga] いい[ii] です[desu] よ[yo] 。", "You'd better go to the hospital."),
    (5, "元気", "げんき", "genki", "healthy, energetic", "お元気[ogenki] です[desu] か[ka] 。", "How are you?"),
    (5, "大丈夫", "だいじょうぶ", "daijoubu", "okay, all right", "大丈夫[daijoubu] です[desu] か[ka] 。", "Are you okay?"),
    (5, "勉強", "べんきょう", "benkyou", "study", "図書館[toshokan] で[de] 勉強しましょう[benkyou shimashou] 。", "Let's study at the library."),
    (5, "休み", "やすみ", "yasumi", "rest, day off", "明日[ashita] は[wa] 休み[yasumi] です[desu] 。", "Tomorrow is a day off."),
    # ---- N4 ----
    (4, "経験", "けいけん", "keiken", "experience", "海外[kaigai] で[de] 働いた[hataraita] 経験[keiken] が[ga] あります[arimasu] 。", "I have experience working abroad."),
    (4, "準備", "じゅんび", "junbi", "preparation", "旅行[ryokou] の[no] 準備[junbi] は[wa] できました[dekimashita] か[ka] 。", "Are you ready for the trip?"),
    (4, "説明", "せつめい", "setsumei", "explanation", "もう一度[mou ichido] 説明して[setsumei shite] ください[kudasai] 。", "Please explain it once more."),
    (4, "予約", "よやく", "yoyaku", "reservation", "レストラン[resutoran] を[o] 予約しました[yoyaku shimashita] 。", "I booked a restaurant."),
    (4, "安心", "あんしん", "anshin", "relief, peace of mind", "それ[sore] を[o] 聞いて[kiite] 安心しました[anshin shimashita] 。", "I was relieved to hear that."),
    (4, "趣味", "しゅみ", "shumi", "hobby", "私[watashi] の[no] 趣味[shumi] は[wa] ゲーム[geemu] です[desu] 。", "My hobby is games."),
    (4, "約束", "やくそく", "yakusoku", "promise, appointment", "約束[yakusoku] を[o] 守って[mamotte] ください[kudasai] 。", "Please keep your promise."),
    (4, "必要", "ひつよう", "hitsuyou", "necessary", "パスポート[pasupooto] が[ga] 必要[hitsuyou] です[desu] 。", "A passport is required."),
    (4, "残念", "ざんねん", "zannen", "unfortunate, a pity", "来られなくて[korarenakute] 残念[zannen] です[desu] 。", "It's a shame you can't come."),
    (4, "特に", "とくに", "tokuni", "especially", "特に[tokuni] 問題[mondai] は[wa] ありません[arimasen] 。", "There's no particular problem."),
    (4, "復習", "ふくしゅう", "fukushuu", "review (study)", "寝る[neru] 前[mae] に[ni] 復習します[fukushuu shimasu] 。", "I review before going to bed."),
    (4, "意味", "いみ", "imi", "meaning", "この[kono] 言葉[kotoba] の[no] 意味[imi] は[wa] 何[nan] です[desu] か[ka] 。", "What does this word mean?"),
    # ---- N3 ----
    (3, "努力", "どりょく", "doryoku", "effort", "努力[doryoku] は[wa] 必ず[kanarazu] 報われる[mukuwareru] 。", "Effort always pays off."),
    (3, "状況", "じょうきょう", "joukyou", "situation", "今[ima] の[no] 状況[joukyou] を[o] 教えて[oshiete] ください[kudasai] 。", "Please tell me the current situation."),
    (3, "確認", "かくにん", "kakunin", "confirmation, check", "メール[meeru] を[o] 確認して[kakunin shite] ください[kudasai] 。", "Please check your email."),
    (3, "影響", "えいきょう", "eikyou", "influence, effect", "台風[taifuu] の[no] 影響[eikyou] で[de] 電車[densha] が[ga] 止まった[tomatta] 。", "The trains stopped because of the typhoon."),
    (3, "我慢", "がまん", "gaman", "patience, endurance", "もう[mou] 我慢[gaman] できない[dekinai] 。", "I can't take it anymore."),
    (3, "得意", "とくい", "tokui", "good at, strong point", "彼女[kanojo] は[wa] 数学[suugaku] が[ga] 得意[tokui] だ[da] 。", "She's good at math."),
    (3, "普段", "ふだん", "fudan", "usually, ordinarily", "普段[fudan] は[wa] 自転車[jitensha] で[de] 通勤しています[tsuukin shiteimasu] 。", "I usually commute by bike."),
    (3, "感謝", "かんしゃ", "kansha", "gratitude", "皆さん[minasan] に[ni] 感謝しています[kansha shiteimasu] 。", "I'm grateful to everyone."),
    (3, "迷う", "まよう", "mayou", "get lost, hesitate", "道[michi] に[ni] 迷って[mayotte] しまった[shimatta] 。", "I got lost."),
    (3, "偶然", "ぐうぜん", "guuzen", "coincidence, by chance", "駅[eki] で[de] 偶然[guuzen] 先生[sensei] に[ni] 会った[atta] 。", "I ran into my teacher at the station by chance."),
    # ---- N2 ----
    (2, "把握", "はあく", "haaku", "grasp, understanding", "状況[joukyou] を[o] 把握する[haaku suru] 必要[hitsuyou] が[ga] ある[aru] 。", "We need to grasp the situation."),
    (2, "妥協", "だきょう", "dakyou", "compromise", "品質[hinshitsu] に[ni] 妥協[dakyou] は[wa] しない[shinai] 。", "We don't compromise on quality."),
    (2, "貴重", "きちょう", "kichou", "precious, valuable", "貴重な[kichouna] 経験[keiken] に[ni] なりました[narimashita] 。", "It was a valuable experience."),
    (2, "曖昧", "あいまい", "aimai", "vague, ambiguous", "彼[kare] の[no] 返事[henji] は[wa] 曖昧[aimai] だった[datta] 。", "His answer was vague."),
    (2, "維持", "いじ", "iji", "maintenance, upkeep", "健康[kenkou] を[o] 維持する[iji suru] の[no] は[wa] 大切[taisetsu] だ[da] 。", "Staying healthy is important."),
    (2, "削除", "さくじょ", "sakujo", "deletion", "古い[furui] ファイル[fairu] を[o] 削除しました[sakujo shimashita] 。", "I deleted the old files."),
    (2, "抱える", "かかえる", "kakaeru", "hold, carry (burdens)", "多く[ooku] の[no] 問題[mondai] を[o] 抱えている[kakaeteiru] 。", "They have a lot of problems on their hands."),
    (2, "継続", "けいぞく", "keizoku", "continuation", "継続[keizoku] は[wa] 力[chikara] なり[nari] 。", "Persistence is power."),
    (2, "著しい", "いちじるしい", "ichijirushii", "remarkable, striking", "技術[gijutsu] の[no] 進歩[shinpo] が[ga] 著しい[ichijirushii] 。", "Technological progress is remarkable."),
    (2, "範囲", "はんい", "han'i", "range, scope", "試験[shiken] の[no] 範囲[han'i] を[o] 確認した[kakunin shita] 。", "I checked what the exam covers."),
    # ---- N1 ----
    (1, "懸念", "けねん", "kenen", "concern, worry", "専門家[senmonka] は[wa] 景気[keiki] の[no] 悪化[akka] を[o] 懸念している[kenen shiteiru] 。", "Experts are concerned about the economy worsening."),
    (1, "覆す", "くつがえす", "kutsugaesu", "overturn, overthrow", "判決[hanketsu] が[ga] 覆された[kutsugaesareta] 。", "The ruling was overturned."),
    (1, "蔑ろ", "ないがしろ", "naigashiro", "neglect, disregard", "家族[kazoku] を[o] 蔑ろに[naigashiro ni] して[shite] は[wa] いけない[ikenai] 。", "You mustn't neglect your family."),
    (1, "潔い", "いさぎよい", "isagiyoi", "graceful, gallant (in accepting)", "潔く[isagiyoku] 負け[make] を[o] 認めた[mitometa] 。", "They gracefully admitted defeat."),
    (1, "目論む", "もくろむ", "mokuromu", "plan, scheme", "彼[kare] は[wa] 何か[nanika] を[o] 目論んでいる[mokurondeiru] 。", "He's scheming something."),
    (1, "滞る", "とどこおる", "todokooru", "stagnate, fall behind", "仕事[shigoto] が[ga] 滞っている[todokootteiru] 。", "Work has fallen behind."),
    (1, "斡旋", "あっせん", "assen", "mediation, placement", "学校[gakkou] が[ga] 就職[shuushoku] を[o] 斡旋して[assen shite] くれた[kureta] 。", "The school helped place me in a job."),
    (1, "如実", "にょじつ", "nyojitsu", "faithfully, vividly", "数字[suuji] が[ga] 現状[genjou] を[o] 如実に[nyojitsu ni] 示している[shimeshiteiru] 。", "The numbers clearly show the current state."),
]

PHRASES = [
    # ---- N5 ----
    (5, "いただきます", "いただきます", "itadakimasu", "Let's eat (said before a meal)", "手[te] を[o] 合わせて[awasete] 「 いただきます[itadakimasu] 」 と[to] 言った[itta] 。", "I put my hands together and said \"itadakimasu\"."),
    (5, "よろしくお願いします", "よろしくおねがいします", "yoroshiku onegaishimasu", "Nice to meet you / I'm in your care", "初めまして[hajimemashite] 、 よろしく[yoroshiku] お願いします[onegaishimasu] 。", "Nice to meet you, I look forward to working with you."),
    (5, "お疲れ様です", "おつかれさまです", "otsukaresama desu", "Thanks for your hard work", "今日[kyou] も[mo] お疲れ様でした[otsukaresama deshita] 。", "Thanks for your hard work today too."),
    (5, "すみません", "すみません", "sumimasen", "Excuse me / I'm sorry", "すみません[sumimasen] 、 お手洗い[otearai] は[wa] どこ[doko] です[desu] か[ka] 。", "Excuse me, where is the restroom?"),
    (5, "いくらですか", "いくらですか", "ikura desu ka", "How much is it?", "この[kono] 傘[kasa] は[wa] いくら[ikura] です[desu] か[ka] 。", "How much is this umbrella?"),
    (5, "大丈夫です", "だいじょうぶです", "daijoubu desu", "I'm fine / It's okay", "心配[shinpai] しないで[shinaide] 、 大丈夫[daijoubu] です[desu] よ[yo] 。", "Don't worry, I'm fine."),
    (5, "お先に失礼します", "おさきにしつれいします", "osaki ni shitsurei shimasu", "Excuse me for leaving first", "では[dewa] 、 お先に[osaki ni] 失礼します[shitsurei shimasu] 。", "Well then, I'll be heading out first."),
    (5, "ちょっと待ってください", "ちょっとまってください", "chotto matte kudasai", "Wait a moment, please", "ちょっと[chotto] 待って[matte] ください[kudasai] 、 すぐ[sugu] 行きます[ikimasu] 。", "Wait a moment, I'll be right there."),
    (5, "行ってきます", "いってきます", "ittekimasu", "I'm off! (leaving home)", "「 行ってきます[ittekimasu] 」 「 行ってらっしゃい[itterasshai] 」 。", "\"I'm off!\" \"See you later!\""),
    (5, "おかえりなさい", "おかえりなさい", "okaerinasai", "Welcome home", "おかえりなさい[okaerinasai] 、 今日[kyou] は[wa] どう[dou] だった[datta] ？", "Welcome home, how was today?"),
    # ---- N4 ----
    (4, "気をつけて", "きをつけて", "ki o tsukete", "Take care / Be careful", "雪[yuki] だ[da] から[kara] 、 気をつけて[ki o tsukete] 帰って[kaette] ね[ne] 。", "It's snowing, so be careful going home."),
    (4, "お邪魔します", "おじゃまします", "ojama shimasu", "Sorry to intrude (entering a home)", "「 お邪魔します[ojama shimasu] 」 と[to] 言って[itte] 部屋[heya] に[ni] 入った[haitta] 。", "I said \"ojama shimasu\" and came into the room."),
    (4, "仕方がない", "しかたがない", "shikata ga nai", "It can't be helped", "雨[ame] なら[nara] 仕方がない[shikata ga nai] ね[ne] 。", "If it rains, it can't be helped."),
    (4, "楽しみにしています", "たのしみにしています", "tanoshimi ni shiteimasu", "I'm looking forward to it", "来週[raishuu] の[no] 旅行[ryokou] を[o] 楽しみにしています[tanoshimi ni shiteimasu] 。", "I'm looking forward to next week's trip."),
    (4, "どういう意味ですか", "どういういみですか", "dou iu imi desu ka", "What does it mean?", "この[kono] 言葉[kotoba] は[wa] どういう[dou iu] 意味[imi] です[desu] か[ka] 。", "What does this word mean?"),
    (4, "お大事に", "おだいじに", "odaiji ni", "Get well soon / Take care", "風邪[kaze] です[desu] か[ka] 。 お大事に[odaiji ni] 。", "You have a cold? Get well soon."),
    (4, "手伝いましょうか", "てつだいましょうか", "tetsudaimashou ka", "Shall I help?", "荷物[nimotsu] 、 手伝いましょう[tetsudaimashou] か[ka] 。", "Shall I help with your bags?"),
    (4, "もう一度お願いします", "もういちどおねがいします", "mou ichido onegaishimasu", "Once more, please", "すみません[sumimasen] 、 もう一度[mou ichido] お願いします[onegaishimasu] 。", "Sorry, once more please."),
    # ---- N3 ----
    (3, "せっかくだから", "せっかくだから", "sekkaku dakara", "Since we have the chance", "せっかく[sekkaku] 京都[kyouto] に[ni] 来た[kita] んだ[n da] から[kara] 、 お寺[otera] を[o] 見よう[miyou] 。", "Since we came all the way to Kyoto, let's see the temples."),
    (3, "さすがですね", "さすがですね", "sasuga desu ne", "As expected of you!", "一回[ikkai] で[de] 合格[goukaku] する[suru] なんて[nante] 、 さすが[sasuga] です[desu] ね[ne] 。", "Passing on the first try — that's you all over!"),
    (3, "とりあえず", "とりあえず", "toriaezu", "For now / To start with", "とりあえず[toriaezu] 、 ビール[biiru] を[o] 二つ[futatsu] ください[kudasai] 。", "Two beers to start with, please."),
    (3, "迷惑をかける", "めいわくをかける", "meiwaku o kakeru", "To cause someone trouble", "ご迷惑[gomeiwaku] を[o] おかけして[okakeshite] すみません[sumimasen] 。", "I'm sorry for the trouble."),
    (3, "念のため", "ねんのため", "nen no tame", "Just in case", "念のため[nen no tame] 傘[kasa] を[o] 持って[motte] 行こう[ikou] 。", "Let's take an umbrella just in case."),
    (3, "気にしないで", "きにしないで", "ki ni shinaide", "Don't worry about it", "遅れても[okuretemo] 気にしないで[ki ni shinaide] ね[ne] 。", "Don't worry if you're late."),
    (3, "お言葉に甘えて", "おことばにあまえて", "okotoba ni amaete", "Taking you up on your kind offer", "では[dewa] 、 お言葉に[okotoba ni] 甘えて[amaete] 。", "Then I'll gladly accept your offer."),
    (3, "いい加減にして", "いいかげんにして", "iikagen ni shite", "Knock it off / That's enough", "もう[mou] いい加減に[iikagen ni] して[shite] ！", "That's enough already!"),
    # ---- N2 ----
    (2, "腑に落ちない", "ふにおちない", "fu ni ochinai", "Doesn't sit right / Unconvincing", "彼[kare] の[no] 説明[setsumei] は[wa] どうも[doumo] 腑に落ちない[fu ni ochinai] 。", "His explanation somehow doesn't sit right."),
    (2, "言うまでもない", "いうまでもない", "iu made mo nai", "It goes without saying", "健康[kenkou] が[ga] 大切[taisetsu] な[na] の[no] は[wa] 言うまでもない[iu made mo nai] 。", "It goes without saying that health is important."),
    (2, "手間がかかる", "てまがかかる", "tema ga kakaru", "To take a lot of work", "この[kono] 料理[ryouri] は[wa] 手間がかかる[tema ga kakaru] 。", "This dish takes a lot of work."),
    (2, "目を通す", "めをとおす", "me o toosu", "To look over / skim", "会議[kaigi] の[no] 前[mae] に[ni] 資料[shiryou] に[ni] 目を通して[me o tooshite] ください[kudasai] 。", "Please look over the materials before the meeting."),
    (2, "頭が上がらない", "あたまがあがらない", "atama ga agaranai", "To be deeply indebted to someone", "母[haha] に[ni] は[wa] 頭が上がらない[atama ga agaranai] 。", "I owe my mother more than I can repay."),
    (2, "口が軽い", "くちがかるい", "kuchi ga karui", "Can't keep a secret", "彼[kare] は[wa] 口が軽い[kuchi ga karui] から[kara] 、 言わない[iwanai] ほう[hou] が[ga] いい[ii] 。", "He can't keep a secret, so better not tell him."),
    # ---- N1 ----
    (1, "一石二鳥", "いっせきにちょう", "isseki nichou", "Two birds with one stone", "散歩[sanpo] は[wa] 運動[undou] にも[nimo] 気分転換[kibuntenkan] にも[nimo] なって[natte] 一石二鳥[isseki nichou] だ[da] 。", "Walking is exercise and a change of mood — two birds with one stone."),
    (1, "猫の手も借りたい", "ねこのてもかりたい", "neko no te mo karitai", "So busy you'd take any help", "年末[nenmatsu] は[wa] 猫の手も借りたい[neko no te mo karitai] ほど[hodo] 忙しい[isogashii] 。", "The end of the year is so busy I'd take help from a cat."),
    (1, "足元を見る", "あしもとをみる", "ashimoto o miru", "To exploit someone's weak position", "足元を見られて[ashimoto o mirarete] 高く[takaku] 売られた[urareta] 。", "They saw I was desperate and overcharged me."),
    (1, "棚に上げる", "たなにあげる", "tana ni ageru", "To ignore one's own faults", "自分[jibun] の[no] こと[koto] は[wa] 棚に上げて[tana ni agete] 、 人[hito] を[o] 責める[semeru] 。", "They blame others while ignoring their own faults."),
    (1, "七転び八起き", "ななころびやおき", "nanakorobi yaoki", "Fall seven times, get up eight", "人生[jinsei] は[wa] 七転び八起き[nanakorobi yaoki] だ[da] 。", "Life is falling seven times and getting up eight."),
    (1, "水に流す", "みずにながす", "mizu ni nagasu", "To let bygones be bygones", "昔[mukashi] の[no] こと[koto] は[wa] 水に流そう[mizu ni nagasou] 。", "Let's let bygones be bygones."),
]

KANJI_RE = re.compile(r"[㐀-䶿一-鿿々]")


def tokens(s):
    out = []
    for m in TOKEN.finditer(s):
        out.append((m.group(1), m.group(2)))
    return out


def build():
    entries, errors, seen = [], [], set()
    for kind, rows in (("kanji", KANJI), ("word", WORDS), ("phrase", PHRASES)):
        for row in rows:
            if len(row) != 7:
                errors.append(f"bad row {row}")
                continue
            level, text, reading, romaji, meaning, ex, ex_en = row
            eid = f"{kind}-n{level}-{text}"
            if eid in seen:
                errors.append(f"duplicate {eid}")
            seen.add(eid)
            toks = tokens(ex)
            surface = "".join(t for t, _ in toks)
            # every non-punctuation token must carry ASCII romaji
            for t, r in toks:
                if r is None:
                    if re.search(r"[\w぀-ヿ一-鿿]", t):
                        errors.append(f"{eid}: token '{t}' has no romaji")
                elif not r or not re.fullmatch(r"[a-z' \-]+", r):
                    errors.append(f"{eid}: bad romaji '{r}' for '{t}'")
            # the card's kanji (or first kanji of a word/phrase) must appear in its sentence
            needle = text if kind == "kanji" else (KANJI_RE.findall(text) or [text[:2]])[0]
            if needle not in surface:
                errors.append(f"{eid}: example doesn't contain '{needle}'")
            if not re.fullmatch(r"[a-z' ,/()\-]+", romaji):
                errors.append(f"{eid}: bad header romaji '{romaji}'")
            entries.append({
                "id": eid, "type": kind, "level": level, "text": text,
                "reading": reading, "romaji": romaji, "meaning": meaning,
                "ex": ex, "exEn": ex_en,
            })
    return entries, errors


if __name__ == "__main__":
    entries, errors = build()
    if errors:
        print("\n".join(errors), file=sys.stderr)
        sys.exit(1)
    out = Path(__file__).resolve().parent.parent / "app/src/main/assets/entries.json"
    out.write_text(json.dumps(entries, ensure_ascii=False, indent=0) + "\n", encoding="utf-8")
    counts = {}
    for e in entries:
        counts[(e["type"], e["level"])] = counts.get((e["type"], e["level"]), 0) + 1
    print(f"wrote {len(entries)} entries -> {out}")
    for k in sorted(counts):
        print(f"  {k[0]:6} N{k[1]}: {counts[k]}")
