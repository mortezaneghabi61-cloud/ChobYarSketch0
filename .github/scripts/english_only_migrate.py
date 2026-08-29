#!/usr/bin/env python3
from pathlib import Path
import re
import time

from deep_translator import GoogleTranslator

ROOT = Path("app/src/main")
EXTENSIONS = {".java", ".kt", ".xml", ".gradle", ".properties", ".json", ".txt"}
ARABIC = re.compile(r"[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF]")
STRING = re.compile(r'"(?:\\.|[^"\\])*"')

# Stable CAD vocabulary. Apply these before machine translation so common UI/status
# phrases stay concise and consistent across the application.
PHRASES = [
    ("چوب‌یار 3D", "ChobYar 3D"),
    ("چوب‌یار", "ChobYar"),
    ("بستن", "Close"), ("لغو", "Cancel"), ("انجام", "Done"),
    ("اعمال", "Apply"), ("ذخیره", "Save"), ("بازنشانی", "Reset"),
    ("حذف", "Delete"), ("ساخت", "Create"), ("ایجاد", "Create"),
    ("پیش‌نمایش", "Preview"), ("بازسازی", "Rebuild"),
    ("نمایش", "Show"), ("مخفی", "Hide"), ("روشن", "On"), ("خاموش", "Off"),
    ("انتخاب نشده", "not selected"), ("انتخاب شد", "selected"),
    ("انتخاب‌شده", "selected"), ("انتخاب شده", "selected"),
    ("اول یک Body را انتخاب کن", "Select a body first"),
    ("اول Body را انتخاب کن", "Select a body first"),
    ("اول روی یک Body بزن", "Select a body first"),
    ("اول روی Body بزن", "Select a body first"),
    ("اول Face را لمس کن", "Select a face first"),
    ("اول روی Face موردنظر بزن", "Select the target face first"),
    ("اول Edge را لمس کن", "Select an edge first"),
    ("اول شکل را انتخاب کن", "Select geometry first"),
    ("اول Sketch را انتخاب کن", "Select a sketch first"),
    ("اول یک شکل را انتخاب کن", "Select geometry first"),
    ("هیچ Body انتخاب نشده", "No body selected"),
    ("Body انتخاب نشده", "No body selected"),
    ("Face انتخاب نشده", "No face selected"),
    ("Edge انتخاب نشده", "No edge selected"),
    ("ساخته شد", "created"), ("انجام شد", "completed"),
    ("فعال شد", "activated"), ("غیرفعال", "disabled"),
    ("در دسترس نیست", "is unavailable"), ("آماده نیست", "is not ready"),
    ("پیدا نشد", "was not found"), ("نامعتبر", "invalid"),
    ("درست نیست", "is invalid"), ("درست وارد نشده", "was entered incorrectly"),
    ("خالی است", "is empty"), ("خطا", "Error"),
    ("زاویه", "Angle"), ("فاصله", "Distance"), ("شعاع", "Radius"),
    ("قطر", "Diameter"), ("ارتفاع", "Height"), ("عرض", "Width"),
    ("طول", "Length"), ("ضخامت", "Thickness"), ("اندازه", "Dimension"),
    ("ابعاد دقیق", "Exact Dimensions"), ("اندازه دقیق", "Exact Dimension"),
    ("اندازه‌گیری", "Measure"), ("میلی‌متر", "mm"), ("میلیمتر", "mm"),
    ("سانتی‌متر", "cm"), ("سانتیمتر", "cm"), ("درجه", "degrees"),
    ("محور", "Axis"), ("محورها", "Axes"), ("صفحه", "Plane"),
    ("سطح", "Face"), ("لبه", "Edge"), ("نقطه", "Point"),
    ("خط", "Line"), ("دایره", "Circle"), ("قوس", "Arc"),
    ("مستطیل", "Rectangle"), ("چندضلعی", "Polygon"),
    ("راهنما", "Guide"), ("تقاطع", "Intersection"), ("مرکز", "Center"),
    ("وسط", "Midpoint"), ("انتها", "Endpoint"), ("ابتدا", "Start point"),
    ("افقی", "Horizontal"), ("عمودی", "Vertical"), ("موازی", "Parallel"),
    ("عمود", "Perpendicular"), ("مماس", "Tangent"), ("تقارن", "Symmetry"),
    ("هم‌مرکز", "Concentric"), ("قفل", "Lock"), ("آزاد", "Unlocked"),
    ("قیود", "Constraints"), ("پروژه", "Project"), ("اسکچ", "Sketch"),
    ("مدل", "Model"), ("بدنه", "Body"), ("هندسه", "Geometry"),
    ("تاریخچه", "History"), ("پارامتریک", "Parametric"),
    ("متریال", "Material"), ("چوب", "Wood"), ("پارچه", "Fabric"),
    ("پلاستیک", "Plastic"), ("فلز", "Metal"), ("رنگ", "Paint"),
    ("شیشه", "Glass"), ("نمای بالا", "Top View"), ("نمای روبرو", "Front View"),
    ("نمای راست", "Right View"), ("نمای ایزومتریک", "Isometric View"),
    ("نمای 3D", "3D View"), ("واحد پروژه", "Project Units"),
    ("پروژه جدید", "New Project"), ("پروژه‌های داخل اپ", "Projects"),
    ("ذخیره در داخل اپ", "Save Project"), ("جستجوی فرمان", "Command Search"),
    ("جستجو", "Search"), ("افزودن", "Add"), ("تغییر", "Transform"),
    ("ابزار", "Tools"), ("فیت", "Fit"), ("اسنپ", "Snap"),
    ("هیچ‌کدام", "None"), ("آماده", "Ready"),
]
PHRASES.sort(key=lambda p: len(p[0]), reverse=True)

translator = GoogleTranslator(source="fa", target="en")
cache = {}

TECH = [
    "Body", "Face", "Edge", "Sketch", "OCCT", "B-Rep", "CSG", "Fillet",
    "Chamfer", "Shell", "Push/Pull", "Extrude", "Revolve", "Sweep", "Loft",
    "Boolean", "History", "Feature", "Move", "Rotate", "Scale", "Mirror",
    "Pattern", "Snap", "Guide", "Grid", "Plane", "STEP", "STL", "DXF",
    "mm", "cm", "X", "Y", "Z", "XY", "XZ", "YZ", "ISO", "TOP", "FRONT", "RIGHT"
]

def glossary(text: str) -> str:
    out = text
    for fa, en in PHRASES:
        out = out.replace(fa, en)
    return out


def translate_fragment(text: str) -> str:
    if not ARABIC.search(text):
        return text
    text = glossary(text)
    if not ARABIC.search(text):
        return text
    if text in cache:
        return cache[text]
    # Protect common CAD tokens from unwanted translation.
    protected = text
    token_map = {}
    for i, token in enumerate(TECH):
        marker = f"__CAD{i}__"
        if token in protected:
            protected = protected.replace(token, marker)
            token_map[marker] = token
    try:
        result = translator.translate(protected)
        time.sleep(0.05)
    except Exception:
        result = protected
    for marker, token in token_map.items():
        result = result.replace(marker, token)
    result = glossary(result)
    cache[text] = result
    return result


def translate_quoted(match: re.Match) -> str:
    literal = match.group(0)
    inner = literal[1:-1]
    if not ARABIC.search(inner):
        return literal
    # Keep escaped line breaks stable through translation.
    inner = inner.replace("\\n", " __NL__ ").replace("\\t", " __TAB__ ")
    inner = translate_fragment(inner)
    inner = inner.replace("__NL__", "\\n").replace("__TAB__", "\\t")
    # Escape only characters that can invalidate the Java/XML quoted literal.
    inner = inner.replace('"', '\\"')
    return '"' + inner + '"'


def remove_fa_numeric_compat(text: str) -> str:
    # Remove explicit Persian/Arabic digit normalization branches. ASCII input is canonical now.
    patterns = [
        r"if\s*\(c\s*>?=\s*'۰'\s*&&\s*c\s*<=\s*'۹'\)\s*b\.append\([^;]+;\s*else\s+if\s*\(c\s*>?=\s*'٠'\s*&&\s*c\s*<=\s*'٩'\)\s*b\.append\([^;]+;\s*else\s+b\.append\(c\);",
        r"if\s*\(c\s*>?=\s*'۰'\s*&&\s*c\s*<=\s*'۹'\)\s*b\.append\([^;]+;\s*else\s+if\s*\(c\s*>?=\s*'٠'\s*&&\s*c\s*<=\s*'٩'\)\s*b\.append\([^;]+;\s*else\s+if",
    ]
    text = re.sub(patterns[0], "b.append(c);", text)
    # Simpler common compact form, including optional spaces.
    text = re.sub(
        r"if\s*\(c\s*>=\s*'۰'\s*&&\s*c\s*<=\s*'۹'\)\s*b\.append\([^;]+;\s*else\s+if\s*\(c\s*>=\s*'٠'\s*&&\s*c\s*<=\s*'٩'\)\s*b\.append\([^;]+;\s*else\s+b\.append\(c\);",
        "b.append(c);", text)
    # Persian comma normalization is no longer part of the English-only input contract.
    text = text.replace(".replace('،','.')", "").replace(".replace('،', '.')", "")
    return text


def translate_comments_and_remaining(text: str) -> str:
    lines = []
    for line in text.splitlines(keepends=True):
        if not ARABIC.search(line):
            lines.append(line); continue
        # Translate line/block comments after quoted strings were already handled.
        idx = line.find("//")
        if idx >= 0 and ARABIC.search(line[idx+2:]):
            line = line[:idx+2] + " " + translate_fragment(line[idx+2:].strip()) + ("\n" if line.endswith("\n") else "")
        if ARABIC.search(line) and "/*" in line:
            a = line.find("/*")
            b = line.rfind("*/")
            if b > a:
                line = line[:a+2] + " " + translate_fragment(line[a+2:b].strip()) + " " + line[b:]
        lines.append(line)
    return "".join(lines)

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
    text = translate_comments_and_remaining(text)
    if text != original:
        path.write_text(text, encoding="utf-8")
        changed.append(str(path))

print(f"English-only migration changed {len(changed)} files")
for path in changed:
    print(path)

# Fail here if syntax-level Arabic/Persian remains. The permanent audit repeats this in CI.
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
