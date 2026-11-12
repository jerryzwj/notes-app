# -*- coding: utf-8 -*-
import io

mark_xml = '''<?xml version="1.0" encoding="utf-8"?>
<!-- 品牌标识：扁平绿底圆角方块 + 白色描边纸页（右上折角），与网页 mark.svg 同款 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="28dp"
    android:height="28dp"
    android:viewportWidth="28"
    android:viewportHeight="28">

    <path
        android:fillColor="#2CBA70"
        android:pathData="M7,0h14a7,7 0 0 1 7,7v14a7,7 0 0 1 -7,7h-14a7,7 0 0 1 -7,-7v-14a7,7 0 0 1 7,-7z" />

    <path
        android:strokeColor="#FFFFFF"
        android:strokeWidth="1.3"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:fillColor="#00000000"
        android:pathData="M8.8,6h8.8l3,3v11.6a1.4,1.4 0 0 1 -1.4,1.4h-10.4a1.4,1.4 0 0 1 -1.4,-1.4v-13.2a1.4,1.4 0 0 1 1.4,-1.4z" />

    <path
        android:strokeColor="#FFFFFF"
        android:strokeWidth="1.3"
        android:strokeLineCap="round"
        android:pathData="M9.9,11.6h8.2" />
    <path
        android:strokeColor="#FFFFFF"
        android:strokeWidth="1.3"
        android:strokeLineCap="round"
        android:pathData="M9.9,14.4h8.2" />
    <path
        android:strokeColor="#FFFFFF"
        android:strokeWidth="1.3"
        android:strokeLineCap="round"
        android:pathData="M9.9,17h5.4" />
</vector>
'''

mark_svg = '''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 28 28" width="28" height="28">
  <rect width="28" height="28" rx="6.3" fill="#2CBA70"/>
  <path d="M8.8 6h8.8l3 3v11.6a1.4 1.4 0 0 1-1.4 1.4H8.8a1.4 1.4 0 0 1-1.4-1.4V7.4a1.4 1.4 0 0 1 1.4-1.4z"
        fill="none" stroke="#FFFFFF" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M9.9 11.6h8.2M9.9 14.4h8.2M9.9 17h5.4" fill="none" stroke="#FFFFFF" stroke-width="1.3" stroke-linecap="round"/>
</svg>
'''

targets = [
    (r"D:\code\notes-app\android-compose\app\src\main\res\drawable\ic_mark.xml", mark_xml),
    (r"D:\code\notes-app\public\mark.svg", mark_svg),
]
for path, content in targets:
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)
    print("wrote", path)

bg = r"D:\code\notes-app\android-compose\app\src\main\res\values\ic_launcher_background.xml"
with io.open(bg, "r", encoding="utf-8") as f:
    text = f.read()
text = text.replace("#0E9E74", "#2CBA70")
with io.open(bg, "w", encoding="utf-8", newline="\n") as f:
    f.write(text)
print("wrote", bg)
