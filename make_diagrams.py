# -*- coding: utf-8 -*-
"""Диаграммы для книги (matplotlib, кириллица через DejaVu)."""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch
from pathlib import Path

OUT = Path(__file__).resolve().parent / "book" / "img"
OUT.mkdir(parents=True, exist_ok=True)

plt.rcParams["font.family"] = "DejaVu Sans"
NAVY, TEAL, IVORY, CORAL, GRAY = "#1F4E79", "#2E74B5", "#FBF6EA", "#E8A87C", "#666666"

# ---------- 1. Таймлайн эпох ----------
fig, ax = plt.subplots(figsize=(12.2, 4.4), dpi=200)
ax.axis("off")
ax.set_xlim(0, 100); ax.set_ylim(0, 10)
ax.annotate("", xy=(99, 2.2), xytext=(1, 2.2),
            arrowprops=dict(arrowstyle="-|>", lw=3, color=NAVY))
eras = [
    (3.5,  "1970-е", "Монополия открытой\nсперматоцелэктомии;\nпункция дискредитирована", NAVY),
    (22.5, "1980-е", "Эра склерозантов:\nтетрациклин (1984), фенол;\nмикропринципы Goldstein", TEAL),
    (41.5, "1990-е", "Этаноламин олеат (1992,\n63 сперматоцеле), OK-432,\nполидоканол, доксициклин", TEAL),
    (60.5, "2000-е", "Стандарт УЗ-протоколов:\nСТС (2001); старт серии\nмикрохирургии (→2011)", NAVY),
    (79.5, "2010–2020-е", "Микрохирургия — стандарт\nпри репродуктивных планах;\nперсонализация (CJU-2025)", NAVY),
]
for x, title, desc, color in eras:
    ax.scatter([x], [2.2], s=140, color=color, zorder=5, edgecolors="white", linewidths=2)
    ax.text(x, 3.1, title, ha="center", va="bottom", fontsize=11.5, fontweight="bold", color=color)
    ax.text(x, 5.6, desc, ha="center", va="center", fontsize=8.6, color="#333333",
            bbox=dict(boxstyle="round,pad=0.45", fc=IVORY, ec=color, lw=1.2))
miles = [(13, "1975\nаспирация+склеро", 0.9), (32, "1984\nтетрациклин 23/23", 0.9),
         (51, "1992\nэтаноламин 60 %", 0.9), (70, "2001\nСТС: 85 % удовл.", 0.9),
         (89, "2011/2025\nмикро / CJU 14,3 %", 0.9)]
for x, txt, y in miles:
    ax.scatter([x], [1.55], s=26, color=CORAL, zorder=5)
    ax.text(x, 0.75, txt, ha="center", va="center", fontsize=7.6, color=CORAL, fontweight="bold")
plt.tight_layout(pad=0.4)
plt.savefig(OUT / "timeline.png", facecolor="white")
plt.close()

# ---------- 2. Алгоритм выбора ----------
fig, ax = plt.subplots(figsize=(12.4, 9.0), dpi=200)
ax.axis("off"); ax.set_xlim(0, 100); ax.set_ylim(0, 100)

def box(x, y, w, h, text, fc, ec, fs=10, bold=True, tc="#1a1a1a"):
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.6",
                                fc=fc, ec=ec, lw=1.6))
    ax.text(x + w/2, y + h/2, text, ha="center", va="center", fontsize=fs,
            fontweight="bold" if bold else "normal", color=tc)

def arrow(x1, y1, x2, y2, label=None, lx=0, ly=0, rad=0.0):
    ax.add_patch(FancyArrowPatch(
        (x1, y1), (x2, y2), arrowstyle="-|>",
        connectionstyle=f"arc3,rad={rad}",
        mutation_scale=14, lw=1.5, color=NAVY, zorder=3))
    if label:
        ax.text((x1 + x2) / 2 + lx, (y1 + y2) / 2 + ly, label, fontsize=8.4,
                color=NAVY, ha="center", va="center", zorder=4,
                bbox=dict(fc="white", ec="none", pad=1.2))

box(28, 90, 44, 8, "Киста придатка яичка\n(УЗИ-подтверждение)", NAVY, NAVY, 11, tc="white")
box(6, 76, 28, 8, "Бессимптомная\n≤ 1 см", IVORY, TEAL, 10)
box(62, 76, 32, 8, "Симптомы, > 2 см,\nрост, деформация", IVORY, CORAL, 10)
box(2, 58, 34, 11, "НАБЛЮДЕНИЕ\nУЗИ 6–12 мес;\nу детей регресс 71–77 %", "#EAF3FB", TEAL, 9.2)
box(42, 58, 56, 11, "Оценка репродуктивных планов + спермограмма\nисключить другую патологию", "#FDEEE2", CORAL, 9.4)
arrow(20, 76, 19, 69)
arrow(78, 76, 70, 69)
arrow(36, 63.5, 42, 63.5, "при росте / боли", 0, 2.6)
box(2, 28, 30, 16, "МИКРОХИРУРГИЧЕСКАЯ\nСПЕРМАТОЦЕЛЭКТОМИЯ\nоптика 6–25×\nрецидив 0–5 %\nпридаток сохранён", "#EAF3FB", TEAL, 8.6)
box(35, 28, 30, 16, "СПЕРМАТОЦЕЛЭКТОМИЯ\n± ЭПИДИДИМЭКТОМИЯ\nрецидив 5–14 %\nэпидидимэктомия — только\nпри завершённой фертильности", "#FDEEE2", CORAL, 8.2)
box(68, 28, 30, 16, "АСПИРАЦИЯ +\nСКЛЕРОТЕРАПИЯ\nуспех 70–90 %\nрецидив 10–30 %\nНЕ при планах на детей", "#FFF7E6", "#C8871E", 8.4)
# Подписи стоят в промежутке между блоками, стрелки не пересекают чужие решения.
arrow(50, 58, 17, 44, "планы\nна детей", -8, 1.5, rad=0.18)
arrow(70, 58, 50, 44, "фертильность\nзавершена", 0, 1.2)
arrow(90, 58, 83, 44, "отказ или\nвысокий риск", 0, 1.2)
box(14, 8, 72, 12, "ОСЛОЖНЁННАЯ КИСТА (разрыв, кровоизлияние, инфицирование)\nэкстренная госпитализация: обезболивание, ревизия / дренирование,\nантибиотики при инфекции", "#FBEAEA", "#B03A3A", 9.2, tc="#7A1F1F")
ax.text(50, 3.2, "Лечим симптом и рост, а не «картинку на УЗИ» — бессимптомная киста лечения не требует",
        ha="center", fontsize=9.5, style="italic", color=GRAY)
plt.tight_layout(pad=0.4)
plt.savefig(OUT / "algorithm.png", facecolor="white")
plt.close()

# ---------- 3. Диапазоны рецидива ----------
fig, ax = plt.subplots(figsize=(11.5, 5.2), dpi=200)
methods = [
    ("Аспирация без склерозанта", 90, 100, "#B03A3A"),
    ("Аспирация + склеротерапия", 10, 30, "#C8871E"),
    ("Сперматоцелэктомия", 5, 15, TEAL),
    ("Сперматоцелэктомия\n+ эпидидимэктомия", 2, 10, NAVY),
    ("Микрохирургическая\nсперматоцелэктомия", 0, 5, "#2E8B57"),
]
ys = range(len(methods))
for i, (name, lo, hi, color) in enumerate(methods):
    ax.barh(i, hi - lo if hi > lo else 0.6, left=lo, height=0.52, color=color, alpha=0.9, zorder=3)
    ax.text(hi + 1.5, i, f"{lo}–{hi} %" if hi > lo else "0 %", va="center",
            fontsize=10, fontweight="bold", color=color)
ax.set_yticks(list(ys)); ax.set_yticklabels([m[0] for m in methods], fontsize=10)
ax.set_xlim(0, 112); ax.set_xlabel("Рецидив кисты, % (диапазон по литературе 1970–2026)", fontsize=10.5)
ax.invert_yaxis()
ax.spines[["top", "right"]].set_visible(False)
ax.grid(axis="x", ls=":", alpha=0.55, zorder=0)
ax.set_title("Рецидив кисты придатка после различных методов лечения", fontsize=12.5,
             fontweight="bold", color=NAVY, pad=12)
plt.tight_layout(pad=0.6)
plt.savefig(OUT / "recurrence.png", facecolor="white")
plt.close()
print("diagrams OK")
