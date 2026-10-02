"""SAIFE 로고 파일 생성기. 글자는 Pretendard Bold를 패스로 바꿔 넣는다(폰트 없이 어디서나 같은 모양).

출력(frontend/public/brand/):
  saife-mark.svg          심볼(파란 육각 + 흰 S)
  saife-logo.svg          심볼 + 글자(밝은 배경용, 글자 남색)
  saife-logo-inverse.svg  심볼 + 글자(어두운 배경용, 글자 흰색)
  saife-mark-512.png      앱 아이콘, 파비콘 원본
실행: python scripts/build-logo.py  (fontTools, playwright 필요)
"""
import asyncio
import os

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, "node_modules", "pretendard", "dist", "public", "static", "Pretendard-Bold.otf")
OUT = os.path.join(ROOT, "public", "brand")

BLUE, NAVY = "#1f4e8c", "#0d1b2e"
HEX = "M24 2.5l18.6 10.75v21.5L24 45.5 5.4 34.75v-21.5z"
S = "M31 15.5H20.5a4.75 4.75 0 0 0 0 9.5h7a4.75 4.75 0 0 1 0 9.5H17"


def mark(hex_fill=BLUE, s_stroke="#fff"):
    return (f'<path d="{HEX}" fill="{hex_fill}"/>'
            f'<path d="{S}" fill="none" stroke="{s_stroke}" stroke-width="4.4" stroke-linecap="round" stroke-linejoin="round"/>')


def word_path(text, size, x0, baseline, tracking=-0.01):
    """글자를 SVG 패스로. size는 em 크기(px), tracking은 em 비율"""
    font = TTFont(FONT)
    gs = font.getGlyphSet()
    cmap = font.getBestCmap()
    upem = font["head"].unitsPerEm
    scale = size / upem
    pen = SVGPathPen(gs)
    x = x0
    for ch in text:
        g = cmap[ord(ch)]
        tp = TransformPen(pen, (scale, 0, 0, -scale, x, baseline))
        gs[g].draw(tp)
        x += gs[g].width * scale + tracking * size
    return pen.getCommands(), x


def logo(text_fill, hex_fill=BLUE, s_stroke="#fff"):
    d, end = word_path("SAIFE", 30, 58, 35.5)
    w = int(end + 2)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} 48" width="{w}" height="48">'
            f'{mark(hex_fill, s_stroke)}<path d="{d}" fill="{text_fill}"/></svg>')


def main():
    os.makedirs(OUT, exist_ok=True)
    files = {
        "saife-mark.svg": f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" width="48" height="48">{mark()}</svg>',
        "saife-logo.svg": logo(NAVY),
        "saife-logo-inverse.svg": logo("#fff", hex_fill="#fff", s_stroke=NAVY),
    }
    for name, svg in files.items():
        open(os.path.join(OUT, name), "w", encoding="utf-8").write(svg)

    async def png():
        from playwright.async_api import async_playwright
        async with async_playwright() as p:
            b = await p.chromium.launch()
            pg = await b.new_page(viewport={"width": 512, "height": 512})
            await pg.set_content(f'<html><body style="margin:0;background:transparent">'
                                 f'<svg viewBox="0 0 48 48" width="512" height="512">{mark()}</svg></body></html>')
            await pg.screenshot(path=os.path.join(OUT, "saife-mark-512.png"), omit_background=True)
            await b.close()
    asyncio.run(png())
    print("ok", OUT)


if __name__ == "__main__":
    main()
