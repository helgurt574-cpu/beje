#!/usr/bin/env python3
"""Bêje simgesini üretir: Android adaptive icon vektörleri + PNG'ler için SVG ana dosyaları.

Simge: kırmızı zemin üzerinde 21 ışınlı sarı güneş ve ortasında yeşil mikrofon.
Uygulama temasındaki marka renkleriyle (values/colors.xml) aynı paleti kullanır.
Çalıştırma:  python3 branding/generate_icons.py
"""
import math
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
OUT = os.path.join(ROOT, "branding")

RED_TOP = "#EF4656"
RED_BOTTOM = "#C41E30"
SUN = "#FFC83D"
GREEN = "#1E7B3C"

C = 54.0  # adaptive icon tuvali 108x108, merkez
RAYS = 21
RAY_IN, RAY_OUT, RAY_W = 25.0, 31.0, 2.8
DISC_R = 21.5


def f(v):
    return ("%.2f" % v).rstrip("0").rstrip(".")


def rays_path(cx=C, cy=C, scale=1.0):
    parts = []
    for i in range(RAYS):
        a = -math.pi / 2 + i * 2 * math.pi / RAYS
        x1, y1 = cx + math.cos(a) * RAY_IN * scale, cy + math.sin(a) * RAY_IN * scale
        x2, y2 = cx + math.cos(a) * RAY_OUT * scale, cy + math.sin(a) * RAY_OUT * scale
        parts.append("M%s,%s L%s,%s" % (f(x1), f(y1), f(x2), f(y2)))
    return " ".join(parts)


def circle_path(cx, cy, r):
    return "M%s,%s a%s,%s 0 1,0 %s,0 a%s,%s 0 1,0 %s,0 Z" % (
        f(cx - r), f(cy), f(r), f(r), f(2 * r), f(r), f(r), f(-2 * r))


# Mikrofon (merkez 54,54 etrafında)
MIC_BODY = "M49,46.5 a5,5 0 0,1 10,0 L59,53.5 a5,5 0 0,1 -10,0 Z"
MIC_ARC = "M45,53 a9,9 0 0,0 18,0"
MIC_STEM = "M54,62 L54,66.5 M49.5,66.5 L58.5,66.5"
MIC_STROKE = 2.6

# MIC_BODY: x 49..59, y 41.5..58.5


def vector(width_dp, body, viewport=108):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- branding/generate_icons.py tarafından üretildi; elle düzenlemeyin. -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    xmlns:aapt="http://schemas.android.com/aapt"\n'
        '    android:width="%sdp"\n    android:height="%sdp"\n'
        '    android:viewportWidth="%s"\n    android:viewportHeight="%s">\n%s</vector>\n'
        % (width_dp, width_dp, viewport, viewport, body))


def gradient_fill(path):
    return (
        '    <path android:pathData="%s">\n'
        '        <aapt:attr name="android:fillColor">\n'
        '            <gradient android:type="linear"\n'
        '                android:startX="0" android:startY="0"\n'
        '                android:endX="108" android:endY="108"\n'
        '                android:startColor="%s" android:endColor="%s" />\n'
        '        </aapt:attr>\n'
        '    </path>\n' % (path, RED_TOP, RED_BOTTOM))


def sun_and_mic(color_sun=SUN, color_mic=GREEN, with_disc=True):
    body = '    <path android:strokeColor="%s" android:strokeWidth="%s" android:strokeLineCap="round"\n' \
           '        android:pathData="%s" />\n' % (color_sun, RAY_W, rays_path())
    if with_disc:
        body += '    <path android:fillColor="%s" android:pathData="%s" />\n' % (color_sun, circle_path(C, C, DISC_R))
    body += '    <path android:fillColor="%s" android:pathData="%s" />\n' % (color_mic, MIC_BODY)
    body += ('    <path android:strokeColor="%s" android:strokeWidth="%s" android:strokeLineCap="round"\n'
             '        android:pathData="%s %s" />\n' % (color_mic, MIC_STROKE, MIC_ARC, MIC_STEM))
    return body


def write(path, content):
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(content)
    print("yazıldı:", os.path.relpath(path, ROOT))


def svg(size, rounded=True, circle=False, scale=1.0, transparent_bg=False):
    """PNG üretimi için SVG (108 birimlik tuval, `scale` ile içerik büyütülür)."""
    s = scale
    tr = "translate(54 54) scale(%s) translate(-54 -54)" % s
    if transparent_bg:
        bg = ""
    elif circle:
        bg = '<circle cx="54" cy="54" r="54" fill="url(#g)"/>'
    elif rounded:
        bg = '<rect width="108" height="108" rx="24" fill="url(#g)"/>'
    else:
        bg = '<rect width="108" height="108" fill="url(#g)"/>'
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 108 108">'
        '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">'
        '<stop offset="0" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient></defs>%s'
        '<g transform="%s">'
        '<path d="%s" stroke="%s" stroke-width="%s" stroke-linecap="round" fill="none"/>'
        '<circle cx="54" cy="54" r="%s" fill="%s"/>'
        '<path d="%s" fill="%s"/>'
        '<path d="%s %s" stroke="%s" stroke-width="%s" stroke-linecap="round" fill="none"/>'
        '</g></svg>' % (size, size, RED_TOP, RED_BOTTOM, bg, tr, rays_path(), SUN, RAY_W, DISC_R, SUN,
                        MIC_BODY, GREEN, MIC_ARC, MIC_STEM, GREEN, MIC_STROKE))


def main():
    drawable = os.path.join(RES, "drawable")
    write(os.path.join(drawable, "ic_launcher_background.xml"),
          vector(108, gradient_fill("M0,0 L108,0 L108,108 L0,108 Z")))
    write(os.path.join(drawable, "ic_launcher_foreground.xml"), vector(108, sun_and_mic()))
    write(os.path.join(drawable, "ic_launcher_monochrome.xml"),
          vector(108, sun_and_mic("#FFFFFFFF", "#FFFFFFFF", with_disc=False)))
    # Araç çubuğu logosu: yuvarlak kırmızı zemin, içerik biraz büyütülmüş
    logo = gradient_fill(circle_path(C, C, 54)) + \
        '    <group android:pivotX="54" android:pivotY="54" android:scaleX="1.45" android:scaleY="1.45">\n' + \
        sun_and_mic() + '    </group>\n'
    write(os.path.join(drawable, "ic_logo.xml"), vector(40, logo))

    svgs = {
        "icon_square": svg(1024, rounded=False, scale=1.25),   # Play Store (maskeyi Play uygular)
        "icon_rounded": svg(1024, rounded=True, scale=1.25),   # masaüstü / eski Android
        "icon_round": svg(1024, circle=True, scale=1.3),
    }
    for name, content in svgs.items():
        write(os.path.join(OUT, name + ".svg"), content)
    render_pngs()


def write_icns(image, path):
    """macOS simgesi: PNG gömülü ICNS (ic07..ic10 = 128..1024 px)."""
    import io
    import struct
    from PIL import Image
    chunks = b""
    for kind, size in ((b"ic07", 128), (b"ic08", 256), (b"ic09", 512), (b"ic10", 1024)):
        buffer = io.BytesIO()
        image.resize((size, size), Image.LANCZOS).save(buffer, format="PNG")
        data = buffer.getvalue()
        chunks += kind + struct.pack(">I", len(data) + 8) + data
    with open(path, "wb") as fh:
        fh.write(b"icns" + struct.pack(">I", len(chunks) + 8) + chunks)
    print("yazıldı:", os.path.relpath(path, ROOT))


def render_pngs():
    """SVG'leri başsız Chrome ile PNG'ye çevirir ve boyutlandırır (Pillow gerekir)."""
    import shutil
    import subprocess
    import tempfile
    from PIL import Image

    chrome = shutil.which("google-chrome") or shutil.which("chromium") or "/opt/google/chrome/chrome"
    if not os.path.exists(chrome):
        print("Chrome bulunamadı; PNG üretimi atlandı.")
        return
    tmp = tempfile.mkdtemp()
    images = {}
    for name in ("icon_square", "icon_rounded", "icon_round"):
        out = os.path.join(tmp, name + ".png")
        subprocess.run([chrome, "--headless=new", "--disable-gpu", "--no-sandbox", "--hide-scrollbars",
                        "--default-background-color=00000000", "--user-data-dir=" + os.path.join(tmp, "profile"),
                        "--window-size=1024,1024", "--screenshot=" + out,
                        "file://" + os.path.join(OUT, name + ".svg")],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        images[name] = Image.open(out).convert("RGBA")

    def save(img, size, path, margin=0.0):
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        inner = round(size * (1 - 2 * margin))
        canvas.alpha_composite(img.resize((inner, inner), Image.LANCZOS), ((size - inner) // 2, (size - inner) // 2))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        canvas.save(path)
        print("yazıldı:", os.path.relpath(path, ROOT))

    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for density, size in densities.items():
        folder = os.path.join(RES, "mipmap-" + density)
        save(images["icon_rounded"], size, os.path.join(folder, "ic_launcher.png"), margin=1 / 24)
        save(images["icon_round"], size, os.path.join(folder, "ic_launcher_round.png"), margin=1 / 24)
    save(images["icon_square"], 512, os.path.join(OUT, "play_store_icon_512.png"))
    save(images["icon_rounded"], 1024, os.path.join(OUT, "icon_1024.png"))
    write_icns(images["icon_rounded"], os.path.join(OUT, "beje.icns"))
    desktop = os.path.join(ROOT, "desktop", "src", "main", "resources")
    if os.path.isdir(desktop):
        save(images["icon_rounded"], 1024, os.path.join(desktop, "beje_icon_full.png"), margin=0.02)
        save(images["icon_round"], 256, os.path.join(desktop, "icons", "beje_round.png"))
        ico = os.path.join(desktop, "icons", "orato.ico")
        os.makedirs(os.path.dirname(ico), exist_ok=True)
        images["icon_rounded"].save(ico, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
        print("yazıldı:", os.path.relpath(ico, ROOT))


if __name__ == "__main__":
    main()
