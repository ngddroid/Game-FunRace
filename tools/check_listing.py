"""
Checks the Google Play listing in store/play/<lang>/ against Play's limits, its metadata policy
and simple keyword placement rules.

    python3 tools/check_listing.py            # en-US
    python3 tools/check_listing.py hi-IN
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
LANG = sys.argv[1] if len(sys.argv) > 1 else "en-US"
D = os.path.join(ROOT, "store", "play", LANG)

def read(name):
    with open(os.path.join(D, name), encoding="utf-8") as f:
        return f.read().strip()

title = read("title.txt")
short = read("short_description.txt")
full = read("full_description.txt")
notes = read(os.path.join("changelogs", sorted(os.listdir(os.path.join(D, "changelogs")), key=lambda n: int(n.split(".")[0]))[-1]))

problems, ok = [], []
def check(cond, good, bad):
    (ok if cond else problems).append(good if cond else bad)

# Play Console limits (characters)
for label, text, limit in (("Title", title, 30), ("Short description", short, 80), ("Full description", full, 4000), ("Release notes", notes, 500)):
    check(len(text) <= limit, f"{label}: {len(text)}/{limit} characters", f"{label} is too long: {len(text)}/{limit}")

# Metadata policy: no ranking, price or promo claims; no emoji or ALL CAPS in the title
BANNED = ["best", "#1", "number one", "top", "free", "new", "hot", "sale", "discount", "no ads", "ad free", "million downloads", "game of the year", "award"]
for label, text in (("Title", title), ("Short description", short)):
    hits = [w for w in BANNED if re.search(r"(?<![\w#])" + re.escape(w) + r"(?!\w)", text, re.I)]
    check(not hits, f"{label}: no ranking, price or promo words", f"{label} uses {hits} (Play metadata policy)")
EMOJI = re.compile("[\U0001F300-\U0001FAFF☀-➿]")
for label, text in (("Title", title), ("Short description", short)):
    check(not EMOJI.search(text), f"{label}: no emoji", f"{label} has emoji")
check(not re.search(r"\b[A-Z]{4,}\b", title), "Title: no ALL CAPS words", "Title has ALL CAPS words")
# other games' names must never be used as keywords
OTHERS = ["fall guys", "stumble guys", "fun race 3d", "subway surfers", "temple run", "roblox", "minecraft"]
all_text = " ".join((title, short, full, notes)).lower()
check(not any(o in all_text for o in OTHERS), "No other games' names", "Mentions another game's name")

# keyword placement and density (Play indexes the title, short and full description)
KEYWORDS = {"obstacle": r"obstacles?", "race": r"rac(e|es|ing|er|ers)", "run": r"run(s|ning|ner)?", "jump": r"jump(s|ing)?", "course": r"courses?"}
words = re.findall(r"[A-Za-z']+", full)
print(f"\nListing check: store/play/{LANG}/")
print(f"  Full description: {len(words)} words\n")
print(f"  {'keyword':<10}{'title':>7}{'short':>7}{'full':>6}{'density':>9}")
for k, rx in KEYWORDS.items():
    r = re.compile(r"\b" + rx + r"\b", re.I)
    n = len(r.findall(full))
    print(f"  {k:<10}{'yes' if r.search(title) else '-':>7}{'yes' if r.search(short) else '-':>7}{n:>6}{100.0 * n / max(len(words), 1):>8.1f}%")
    if k in ("obstacle", "race"):
        check(r.search(title) is not None, f"'{k}' is in the title", f"'{k}' is missing from the title")
        check(r.search(short) is not None, f"'{k}' is in the short description", f"'{k}' is missing from the short description")
        d = 100.0 * n / max(len(words), 1)
        check(1.0 <= d <= 4.0, f"'{k}' density {d:.1f}% (natural range)", f"'{k}' density {d:.1f}% is outside 1-4% (too thin or stuffed)")

print()
for m in ok: print("  OK   " + m)
for m in problems: print("  FIX  " + m)
sys.exit(1 if problems else 0)
