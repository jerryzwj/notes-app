import io, os, sys

ROOT = r"D:\code\notes-app"
NEW = "简·笔记本"

edits = {
    r"public\index.html": [
        ("个人记事本", NEW),
        ("记事本", NEW),
        ('<span class="login-mark">记</span>', '<span class="login-mark">简</span>'),
    ],
    r"public\manifest.webmanifest": [
        ("个人记事本", NEW),
        ("记事本", NEW),
    ],
    r"public\app.js": [
        ("/* 记事本 · 前端 */", "/* 简·笔记本 · 前端 */"),
        ('<div class="foot">记事本 ·', '<div class="foot">简·笔记本 ·'),
        ("解锁记事本", "解锁简·笔记本"),
    ],
    r"public\share.html": [("记事本", NEW)],
    r"public\style.css": [("记事本 · 视觉样式", "简·笔记本 · 视觉样式")],
    r"public\sw.js": [("notes-v29", "notes-v30")],
    r"android-compose\app\src\main\res\values\strings.xml": [
        ('<string name="app_name">记事本</string>', '<string name="app_name">简·笔记本</string>'),
    ],
    r"android-compose\app\src\main\java\eu\wenjun\notes\ListScreen.kt": [
        ('Text("记事本", fontSize = 16.sp', 'Text("简·笔记本", fontSize = 16.sp'),
    ],
    r"android-compose\app\src\main\java\eu\wenjun\notes\LoginScreen.kt": [
        ('Text("记事本", fontSize = 22.sp', 'Text("简·笔记本", fontSize = 22.sp'),
    ],
}

for rel, pairs in edits.items():
    path = os.path.join(ROOT, rel)
    with io.open(path, "r", encoding="utf-8") as f:
        text = f.read()
    before = text
    for old, new in pairs:
        text = text.replace(old, new)
    if text != before:
        with io.open(path, "w", encoding="utf-8", newline="") as f:
            f.write(text)
        print("updated", rel)
    else:
        print("unchanged", rel)

# README title only
p = os.path.join(ROOT, "README.md")
with io.open(p, "r", encoding="utf-8") as f:
    lines = f.read().split("\n")
if lines and lines[0].strip() == "# 记事本":
    lines[0] = "# 简·笔记本"
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write("\n".join(lines))
    print("updated README.md title")
else:
    print("README title kept:", lines[0] if lines else "")
