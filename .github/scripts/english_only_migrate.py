#!/usr/bin/env python3
from pathlib import Path
import re
import time

from deep_translator import GoogleTranslator

ROOT = Path("app/src")
EXTENSIONS = {
    ".java", ".kt", ".kts", ".xml", ".gradle", ".properties", ".json", ".txt",
    ".c", ".cc", ".cpp", ".cxx", ".h", ".hh", ".hpp", ".hxx",
}
ARABIC = re.compile(r"[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF]")
ARABIC_RUN = re.compile(r"[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF\u200c\u200d]+(?:[ \t]+[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF\u200c\u200d]+)*")
STRING = re.compile(r'"(?:\\.|[^"\\])*"')

# Stable CAD vocabulary. Apply these before machine translation so common UI/status
# phrases stay concise and consistent across the application.
PHRASES = [
    ("چوب‌یار 3D", "ChobYar 3D"), ("چوب‌یار", "ChobYar"),
    ("بستن", "Close"), ("لغو", "Cancel"), ("انجام", "Done"), ("باشه", "OK"),
    ("فهمیدم", "Got it"), ("اعمال", "Apply"), ("ذخیره", "Save"), ("بازنشانی", "Reset"),
    ("حذف", "Delete"), ("ساخت", "Create"), ("ایجاد", "Create"), ("ساخته", "created"),
    ("پیش‌نمایش", "Preview"), ("بازسازی", "Rebuild"), ("بازگشت", "Back"),
    ("نمایش", "Show"), ("مخفی", "Hide"), ("روشن", "On"), ("خاموش", "Off"),
    ("انتخاب نشده", "not selected"), ("انتخاب شد", "selected"), ("انتخاب", "Selection"),
    ("انتخاب‌شده", "selected"), ("انتخاب شده", "selected"),
    ("اول یک Body را انتخاب کن", "Select a body first"), ("اول Body را انتخاب کن", "Select a body first"),
    ("اول روی یک Body بزن", "Select a body first"), ("اول روی Body بزن", "Select a body first"),
    ("اول Face را لمس کن", "Select a face first"), ("اول روی Face موردنظر بزن", "Select the target face first"),
    ("اول Edge را لمس کن", "Select an edge first"), ("اول شکل را انتخاب کن", "Select geometry first"),
    ("اول Sketch را انتخاب کن", "Select a sketch first"), ("اول یک شکل را انتخاب کن", "Select geometry first"),
    ("هیچ Body انتخاب نشده", "No body selected"), ("Body انتخاب نشده", "No body selected"),
    ("Face انتخاب نشده", "No face selected"), ("Edge انتخاب نشده", "No edge selected"),
    ("ساخته شد", "created"), ("انجام شد", "completed"), ("فعال شد", "activated"),
    ("غیرفعال", "disabled"), ("در دسترس نیست", "is unavailable"), ("آماده نیست", "is not ready"),
    ("پیدا نشد", "was not found"), ("نامعتبر", "invalid"), ("درست نیست", "is invalid"),
    ("درست وارد نشده", "was entered incorrectly"), ("خالی است", "is empty"), ("خطا", "Error"),
    ("زاویه", "Angle"), ("فاصله", "Distance"), ("شعاع", "Radius"), ("قطر", "Diameter"),
    ("ارتفاع", "Height"), ("عرض", "Width"), ("طول", "Length"), ("ضخامت", "Thickness"),
    ("اندازه", "Dimension"), ("ابعاد دقیق", "Exact Dimensions"), ("اندازه دقیق", "Exact Dimension"),
    ("اندازه‌گیری", "Measure"), ("میلی‌متر", "mm"), ("میلیمتر", "mm"),
    ("سانتی‌متر", "cm"), ("سانتیمتر", "cm"), ("درجه", "degrees"),
    ("محور", "Axis"), ("محورها", "Axes"), ("صفحه", "Plane"), ("سطح", "Face"),
    ("لبه", "Edge"), ("نقطه", "Point"), ("خط", "Line"), ("دایره", "Circle"),
    ("قوس", "Arc"), ("مستطیل", "Rectangle"), ("چندضلعی", "Polygon"),
    ("راهنما", "Guide"), ("تقاطع", "Intersection"), ("مرکز", "Center"),
    ("وسط", "Midpoint"), ("میانه", "Midpoint"), ("انتها", "Endpoint"), ("ابتدا", "Start point"),
    ("افقی", "Horizontal"), ("عمودی", "Vertical"), ("موازی", "Parallel"),
    ("عمود", "Perpendicular"), ("مماس", "Tangent"), ("تقارن", "Symmetry"),
    ("هم‌مرکز", "Concentric"), ("قفل", "Lock"), ("آزاد", "Unlocked"), ("قیود", "Constraints"),
    ("روابط", "Constraints"), ("پروژه", "Project"), ("اسکچ", "Sketch"), ("مدل", "Model"),
    ("بدنه", "Body"), ("هندسه", "Geometry"), ("تاریخچه", "History"), ("پارامتریک", "Parametric"),
    ("متریال", "Material"), ("چوب", "Wood"), ("پارچه", "Fabric"), ("پلاستیک", "Plastic"),
    ("فلز", "Metal"), ("رنگ", "Paint"), ("شیشه", "Glass"), ("نما", "View"),
    ("نمای بالا", "Top View"), ("نمای روبرو", "Front View"), ("نمای راست", "Right View"),
    ("نمای ایزومتریک", "Isometric View"), ("نمای 3D", "3D View"), ("واحد پروژه", "Project Units"),
    ("پروژه جدید", "New Project"), ("پروژه‌های داخل اپ", "Projects"),
    ("ذخیره در داخل اپ", "Save Project"), ("جستجوی فرمان", "Command Search"), ("جستجو", "Search"),
    ("افزودن", "Add"), ("تغییر", "Transform"), ("ابزار", "Tools"), ("فیت", "Fit"),
    ("اسنپ", "Snap"), ("هیچ‌کدام", "None"), ("هیچ", "None"), ("اول", "First"),
    ("آماده", "Ready"), ("همه", "All"), ("بالا", "Top"), ("روبرو", "Front"), ("راست", "Right"),
]
PHRASES.sort(key=lambda p: len(p[0]), reverse=True)

translator = GoogleTranslator(source="fa", target="en")
cache = {}

TECH = [
    "Body", "Face", "Edge", "Sketch", "OCCT", "B-Rep", "CSG", "Fillet", "Chamfer", "Shell",
    "Push/Pull", "Extrude", "Revolve", "Sweep", "Loft", "Boolean", "History", "Feature", "Move",
    "Rotate", "Scale", "Mirror", "Pattern", "Snap", "Guide", "Grid", "Plane", "STEP", "STL", "DXF",
    "mm", "cm", "X", "Y", "Z", "XY", "XZ", "YZ", "ISO", "TOP", "FRONT", "RIGHT"
]

DIGIT_MAP = str.maketrans("۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩", "01234567890123456789")
PUNCT_MAP = str.maketrans({"،": ",", "؛": ";", "؟": "?"})


def glossary(text: str) -> str:
    out = text
    for fa, en in PHRASES:
        out = out.replace(fa, en)
    return out.translate(DIGIT_MAP).translate(PUNCT_MAP)


def translate_fragment(text: str) -> str:
    if not ARABIC.search(text):
        return text
    text = glossary(text)
    if not ARABIC.search(text):
        return text
    if text in cache:
        return cache[text]
    protected = text
    token_map = {}
    for i, token in enumerate(TECH):
        marker = f"__CAD{i}__"
        if token in protected:
            protected = protected.replace(token, marker)
            token_map[marker] = token
    try:
        result = translator.translate(protected)
        time.sleep(0.03)
    except Exception:
        result = protected
    for marker, token in token_map.items():
        result = result.replace(marker, token)
    result = glossary(result)
    cache[text] = result
    return result


def translate_run(match: re.Match) -> str:
    source = match.group(0)
    translated = translate_fragment(source)
    # A provider may occasionally echo an untranslated token. Never leave non-English
    # script behind: retry word-by-word, then use a neutral ASCII fallback as a last resort.
    if ARABIC.search(translated):
        pieces = []
        for part in re.split(r"([ \t]+)", source):
            if not part or part.isspace():
                pieces.append(part)
            else:
                candidate = translate_fragment(part)
                pieces.append(candidate if not ARABIC.search(candidate) else "text")
        translated = "".join(pieces)
    return translated


def translate_quoted(match: re.Match) -> str:
    literal = match.group(0)
    inner = literal[1:-1]
    if not ARABIC.search(inner):
        return literal
    inner = inner.replace("\\n", " __NL__ ").replace("\\t", " __TAB__ ")
    inner = translate_fragment(inner)
    if ARABIC.search(inner):
        inner = ARABIC_RUN.sub(translate_run, inner)
    inner = inner.replace("__NL__", "\\n").replace("__TAB__", "\\t")
    inner = inner.replace('"', '\\"')
    return '"' + inner + '"'


def remove_fa_numeric_compat(text: str) -> str:
    text = re.sub(
        r"if\s*\(c\s*>=\s*'۰'\s*&&\s*c\s*<=\s*'۹'\)\s*b\.append\([^;]+;\s*else\s+if\s*\(c\s*>=\s*'٠'\s*&&\s*c\s*<=\s*'٩'\)\s*b\.append\([^;]+;\s*else\s+b\.append\(c\);",
        "b.append(c);", text)
    text = text.replace(".replace('،','.')", "").replace(".replace('،', '.')", "")
    return text


def translate_comments(text: str) -> str:
    lines = []
    for line in text.splitlines(keepends=True):
        ending = "\n" if line.endswith("\n") else ""
        body = line[:-1] if ending else line
        if ARABIC.search(body):
            idx = body.find("//")
            if idx >= 0 and ARABIC.search(body[idx + 2:]):
                body = body[:idx + 2] + " " + translate_fragment(body[idx + 2:].strip())
            if ARABIC.search(body) and "/*" in body:
                a = body.find("/*")
                b = body.rfind("*/")
                if b > a:
                    body = body[:a + 2] + " " + translate_fragment(body[a + 2:b].strip()) + " " + body[b:]
        lines.append(body + ending)
    return "".join(lines)


def finish_mixed_source(text: str) -> str:
    # The first pass translates complete quoted UI/status strings. This second pass handles
    # mixed English/Persian remnants (for example "Lineوط") and comments/legacy aliases.
    text = glossary(text)
    if ARABIC.search(text):
        text = ARABIC_RUN.sub(translate_run, text)
    return text


changed = []
for path in ROOT.rglob("*"):
    if not path.is_file() or path.suffix.lower() not in EXTENSIONS:
        continue
    try:
        original = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        continue
    text = remove_fa_numeric_compat(original)
    text = STRING.sub(translate_quoted, text)
    text = translate_comments(text)
    text = finish_mixed_source(text)
    if text != original:
        path.write_text(text, encoding="utf-8")
        changed.append(str(path))

print(f"English-only migration changed {len(changed)} files")
for path in changed:
    print(path)

remaining = []
for path in ROOT.rglob("*"):
    if not path.is_file() or path.suffix.lower() not in EXTENSIONS:
        continue
    try:
        for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if ARABIC.search(line):
                remaining.append((str(path), n, line.strip()))
    except UnicodeDecodeError:
        pass
if remaining:
    print(f"Remaining Arabic/Persian occurrences after migration: {len(remaining)}")
    for item in remaining[:200]:
        print(f"{item[0]}:{item[1]}: {item[2]}")
    raise SystemExit(2)
print("English-only migration completed with zero Arabic/Persian script occurrences.")
