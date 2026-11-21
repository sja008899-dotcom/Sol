#!/usr/bin/env python3
"""Deterministic generator for Sol's NLU example data.

Writes:
  app/src/main/resources/nlu/intents_fa.jsonl   (training examples used by IntentClassifier at runtime)
  app/src/test/resources/nlu/golden_fa.jsonl    (held-out dev/test examples for the evaluation harness)

Train and golden use DISJOINT templates, so the harness measures generalisation to unseen phrasings.
NOTE: the data is template-generated (synthetic). Accuracy measured on it over-estimates real-world accuracy;
add real, user-corrected utterances to the files over time (see CONTRIBUTING.md).
Run from the repository root:  python3 tools/gen_nlu_data.py
"""
import itertools, json, random, zlib

CONTACTS = ["علی", "مریم", "رضا", "مامان", "همسرم", "آقای احمدی", "دکتر کریمی", "سارا", "بابا", "محمد", "زهرا", "حسین"]
APPS = ["واتساپ", "اینستاگرام", "تلگرام", "دوربین", "گالری", "مرورگر", "ماشین حساب", "تقویم", "یوتیوب", "نقشه"]
SETTINGS = ["وای فای", "بلوتوث", "لوکیشن", "باتری", "صدا"]
MSGS = ["فردا جلسه داریم", "دیر میرسم", "شام نیستم", "برو بیرون", "ساعت پنج بیا", "ممنون از کمکت"]
TASKS = ["دارو بخورم", "جلسه را بررسی کنم", "به مادرم زنگ بزنم", "نان بخرم", "قبض را پرداخت کنم"]
NUMS = ["دو", "سه", "پنج", "ده", "پانزده", "بیست", "۳۰", "۴۵", "۱۰", "۷"]
FACTS = ["رمز وای فای خانه فلانه", "جلسه هفتگی شنبه ساعت ده است", "ماشین پارکینگ طبقه دوم است", "تولد مریم اول مهر است"]
PREFIX_TRAIN = ["", "", "", "لطفا ", "سول ", "ببین ", "یه لحظه "]
PREFIX_GOLD = ["", "", "ببخشید ", "اگه میشه ", "خب ", "هی سول "]

def E(**kw):  # helper
    return kw

# Each intent: (train templates, golden templates, fill-dict builder).  {c}=contact {a}=app {s}=setting {m}=message {t}=task {n}=number {f}=fact
SPEC = {
 "CALL": (
  ["به {c} زنگ بزن", "با {c} تماس بگیر", "زنگ بزن به {c}", "شماره {c} رو بگیر", "یه زنگ به {c} بزن", "تماس با {c}", "میخوام با {c} صحبت کنم"],
  ["می‌تونی با {c} تماس بگیری", "برای {c} یه تماس بگیر", "بزن به {c} زنگ", "{c} رو شماره بگیر", "یه تماس با {c} برقرار کن", "با {c} حرف بزنم", "به {c} یه زنگ بزنی"]),
 "SEND_SMS": (
  ["به {c} پیام بده", "برای {c} پیامک بفرست", "به {c} اس ام اس بده {m}", "پیام بده به {c} {m}", "به {c} بگو {m} با پیامک", "یه پیامک برای {c} بنویس"],
  ["می‌تونی به {c} پیامک بدی", "یه اس‌ام‌اس به {c} بفرست", "برای {c} پیام بفرست که {m}", "به {c} پیام بزن", "{c} رو با پیامک خبر کن", "متن {m} را برای {c} پیامک کن"]),
 "OPEN_APP": (
  ["{a} را باز کن", "{a} رو باز کن", "برنامه {a} رو باز کن", "باز کن {a}", "اپ {a} را اجرا کن", "{a} رو بیار"],
  ["می‌شه {a} رو باز کنی", "برو تو {a}", "{a} رو اجرا کن", "برنامه {a} رو بیار بالا", "یه بار {a} رو باز کن", "اپلیکیشن {a} رو باز کن لطفا"]),
 "OPEN_SETTINGS": (
  ["تنظیمات {s} را باز کن", "تنظیمات {s} رو باز کن", "{s} رو روشن کن", "{s} را خاموش کن", "بخش {s} تنظیمات"],
  ["می‌خوام {s} رو عوض کنم", "برو به تنظیمات {s}", "{s} گوشی رو تنظیم کن", "صفحه {s} رو نشون بده", "تنظیم {s} رو باز کن"]),
 "DEVICE_STATUS": (
  ["وضعیت باتری چطوره", "باتری چند درصده", "شارژ گوشی چقدره", "وضعیت گوشی چطوره", "باتری گوشی رو بگو", "وضعیت دستگاه را بگو"],
  ["الان شارژم چنده", "گوشی چقدر شارژ داره", "باتریم رو چک کن", "حال گوشی چطوره", "چقدر باتری مونده", "درصد شارژ رو بگو"]),
 "ADJUST_VOLUME": (
  ["صدا را زیاد کن", "صدا رو کم کن", "صدای گوشی را بالا ببر", "صداتو زیاد کن", "ولوم را پایین بیار", "صدا رو بلند کن"],
  ["می‌شه صدا رو کمتر کنی", "صدا خیلی کمه زیادش کن", "یه کم صدا رو بیار پایین", "صدا بیشتر شه", "ولوم رو زیاد کن لطفا", "صدا رو آروم کن"]),
 "SCHEDULE_REMINDER": (
  ["{n} دقیقه دیگه یادآوری کن {t}", "یادم بنداز {t}", "{n} ساعت دیگه یادم بنداز {t}", "فردا ساعت ۹ یادآوری کن {t}", "یادآوری کن {t}", "آلارم بذار {t}"],
  ["می‌شه {n} دقیقه بعد بهم یادآوری کنی {t}", "یه یادآور برام بذار که {t}", "پس فردا یادم بنداز {t}", "ساعت ۸ صبح به من یادآوری کن {t}", "حتما یادم بنداز {t}", "چند ساعت دیگه یادم باشه {t}"]),
 "LEARN_FACT": (
  ["یاد بگیر {f}", "یادت باشه {f}", "ذخیره کن {f}", "به خاطر بسپار {f}", "یادداشت کن که {f}"],
  ["این رو یادت نره {f}", "توی حافظه‌ات بذار که {f}", "اینو ثبت کن {f}", "یه نکته بنویس {f}", "فراموش نکن که {f}"]),
 "LIST_MEMORY": (
  ["چی یادت هست", "حافظه رو نشون بده", "چیا ذخیره کردی", "حافظه‌ات رو بگو", "چه چیزهایی بلدی از من"],
  ["لیست حافظه رو بخون", "از من چی تو حافظه داری", "نشونم بده چی یاد گرفتی", "حافظه چیه الان", "چیزایی که یاد گرفتی رو بگو"]),
 "GREETING": (
  ["سلام", "سلام خوبی", "درود", "صبح بخیر", "شب بخیر", "ممنون", "مرسی", "خسته نباشی"],
  ["سلام سول چطوری", "سلام علیکم", "درود بر تو", "عصر بخیر", "خیلی ممنونم", "دمت گرم مرسی", "سلام خوب هستی"]),
 "IDENTITY": (
  ["اسم تو چیه", "تو کی هستی", "نام تو چیست", "اسمت چیه", "چیستی تو"],
  ["خودت رو معرفی کن", "تو چی هستی دقیقا", "اسمت رو بگو", "کی هستی تو", "می‌تونی بگی کی هستی"]),
 "HELP": (
  ["کمک", "راهنما", "چیکار میتونی بکنی", "چی بلدی", "چه کارهایی میتونی انجام بدی"],
  ["می‌شه راهنماییم کنی", "کمکم کن ببینم چه کارهایی داری", "یه راهنمایی بده", "به من کمک کن", "قابلیت‌هات چیه"]),
 "TIME_DATE": (
  ["ساعت چنده", "امروز چندم برج است", "تاریخ امروز چیه", "امروز چه روزیه", "تاریخ شمسی امروز", "الان ساعت چنده"],
  ["می‌شه بگی ساعت چنده", "امروز چند شنبه است", "چندمه امروز", "بگو الان چه ساعتیه", "تاریخ امروز رو بگو", "امروز چندم ماهه"]),
 "GET_CURRENT_LOCATION": (
  ["الان کجام", "موقعیت فعلی من", "کجا هستم", "موقعیتم کجاست"],
  ["بگو الان کجا هستم", "موقعیت الانم چیه", "مکان فعلی من رو بگو", "الان من کجا ثبت شدم"]),
 "SHOW_CAPABILITIES": (
  ["۲۰ قابلیت", "فهرست قابلیت‌ها", "امکانات پس زمینه", "لیست قابلیت‌های سول"],
  ["قابلیت‌ها رو نشون بده", "فهرست کارهایی که انجام میدی", "لیست امکاناتت", "کارهای پس زمینه چیا هستن"]),
 "CALCULATE": (
  ["{n} ضرب در {n}", "{n} به علاوه {n} چند میشه", "{n} منهای {n}", "{n} تقسیم بر {n}", "حساب کن {n} بعلاوه {n}", "حاصل {n} ضرب در {n}"],
  ["می‌شه بگی {n} به اضافه {n} چنده", "{n} درصد {n} چقدره", "نتیجه {n} تقسیم به {n} رو بگو", "{n} در {n} چند میشه", "جمع {n} و {n} رو حساب کن", "{n} بعلاوه {n} می‌شه چند"]),
 "SET_TIMER": (
  ["تایمر {n} دقیقه بذار", "یه تایمر {n} دقیقه ای تنظیم کن", "تایمر {n} ثانیه", "شمارش معکوس {n} دقیقه", "تایمر بذار برای {n} دقیقه"],
  ["می‌تونی تایمر {n} دقیقه بذاری", "{n} دقیقه تایمر بزن", "یه شمارنده {n} دقیقه ای راه بنداز", "تایمر رو روی {n} دقیقه تنظیم کن", "کرنومتر {n} دقیقه"]),
 "FLASHLIGHT": (
  ["چراغ قوه را روشن کن", "چراغ قوه رو خاموش کن", "فلش رو روشن کن", "چراغ قوه", "چراغ‌قوه روشن"],
  ["می‌شه چراغ قوه رو روشن کنی", "نور گوشی رو روشن کن", "چراغ قوه رو بزن", "فلش‌لایت رو خاموش کن", "چراغ قوه رو بیار"]),
}

NEG_SUBJ = ["هوا", "فوتبال دیشب", "این فیلم", "غذای امشب", "قیمت دلار", "ماشین جدیدم", "سفر شمال", "درس فردا", "این کتاب", "ترافیک امروز", "دوستم", "برادرم", "آشپزی", "موسیقی"]
NEG_PRED = ["خیلی قشنگ بود", "رو دوست دارم", "چطور بود", "چرا اینطوریه", "خسته‌کننده است", "یه چیز جالب بود", "نظرت چیه", "رو یادم نیست", "بد نبود", "عالیه"]
NEG_FIXED = [
 "حال شما خوب است", "بروم خانه", "زنگ تفریح شروع شد", "وب سایت خوبیه", "امروز خیلی خسته‌ام", "یه جوک بگو", "چرا آسمان آبی است",
 "دوست دارم برم مسافرت", "من گرسنه‌ام", "داستان بگو", "پایتخت ایران کجاست", "بزرگترین کوه ایران چیه", "خوب بود", "خیلی وب گردی کردم",
 "ساعت مچی جدید خریدم", "زنگ در رو زدن", "تماشا کردن فیلم", "پیامبر اسلام کی بود", "باتری قلمی ندارم", "صدای بارون قشنگه",
 "فردا باید برم دانشگاه", "این هفته مهمونی داریم", "علی دیروز اومد", "مریم کتاب خوند", "من رفتم بازار", "نمیدونم چی بخورم",
 "برو بابا شوخی نکن", "چه خبر", "همه چی خوبه", "آره", "نه", "هیچی", "باشه", "شاید", "فکر کنم", "امروز چه خبر شد", "یکم استراحت کنم",
]

def split_of(text):
    return "test" if zlib.crc32(text.encode("utf-8")) % 2 else "dev"

def expand(templates, prefixes, rng, limit):
    out = []
    keys = ["c", "a", "s", "m", "t", "n", "f"]
    pools = {"c": CONTACTS, "a": APPS, "s": SETTINGS, "m": MSGS, "t": TASKS, "n": NUMS, "f": FACTS}
    for tpl in templates:
        used = [k for k in keys if "{" + k + "}" in tpl]
        n_slots = tpl.count("{")
        for _ in range(limit):
            fills = {}
            text = tpl
            # templates may repeat {n}; fill each occurrence independently
            while "{" in text:
                k = text[text.index("{") + 1]
                text = text.replace("{" + k + "}", rng.choice(pools[k]), 1)
            out.append(rng.choice(prefixes) + text)
    seen, uniq = set(), []
    for t in out:
        if t not in seen:
            seen.add(t); uniq.append(t)
    return uniq

def main():
    rng = random.Random(20261121)
    train, gold = [], []
    for intent, (tt, gt) in SPEC.items():
        for t in expand(tt, PREFIX_TRAIN, rng, 6):
            train.append({"intent": intent, "text": t, "split": "train"})
        for t in expand(gt, PREFIX_GOLD, rng, 8):
            gold.append({"intent": intent, "text": t, "split": split_of(t)})
    # out-of-scope: train from half of the combinations, golden from the other half (disjoint)
    combos = [f"{s} {p}" for s, p in itertools.product(NEG_SUBJ, NEG_PRED)]
    rng.shuffle(combos)
    half = len(combos) // 2
    for t in combos[:half]:
        train.append({"intent": "UNKNOWN", "text": t, "split": "train"})
    for t in combos[half:]:
        gold.append({"intent": "UNKNOWN", "text": t, "split": split_of(t)})
    fixed = list(NEG_FIXED)
    for i, t in enumerate(fixed):
        (train if i % 2 == 0 else gold).append({"intent": "UNKNOWN", "text": t, "split": "train" if i % 2 == 0 else split_of(t)})
    # never let a golden text appear in train
    train_texts = {r["text"] for r in train}
    gold = [r for r in gold if r["text"] not in train_texts]
    with open("app/src/main/resources/nlu/intents_fa.jsonl", "w", encoding="utf-8") as f:
        for r in train: f.write(json.dumps(r, ensure_ascii=False) + "\n")
    with open("app/src/test/resources/nlu/golden_fa.jsonl", "w", encoding="utf-8") as f:
        for r in gold: f.write(json.dumps(r, ensure_ascii=False) + "\n")
    from collections import Counter
    print("train", len(train), dict(Counter(r["intent"] for r in train)))
    print("gold ", len(gold), dict(Counter((r["intent"], r["split"]) for r in gold if r["intent"] in ("CALL", "UNKNOWN"))))

if __name__ == "__main__":
    main()
