"""Generates the sample themes Estelar, Autumn (Otoño), Minicadena, Prado, Maritime (Marítimo), Wheat
(Trigo), Mars (Marte) and Rays (Rayos): JSON + pictures.
Usage: python tools/make_more_sample_themes.py app/src/main/assets/themes [THEME…] (all without names)."""
import json, math, os, random, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter
import sample_art

OUT = sys.argv[1]
ONLY = set(sys.argv[2:])
W, H = 1080, 2280
rng = np.random.default_rng(7)
random.seed(7)


def save_jpg(img, theme, name):
    os.makedirs(os.path.join(OUT, theme), exist_ok=True)
    img.convert("RGB").save(os.path.join(OUT, theme, name), quality=84, optimize=True, progressive=True)


def save_icon(img, theme, name):
    os.makedirs(os.path.join(OUT, theme), exist_ok=True)
    img.resize((192, 192), Image.LANCZOS).save(os.path.join(OUT, theme, name), optimize=True)


def smooth_noise(w, h, scale, octaves=4, seed=0):
    r = np.random.default_rng(seed)
    acc = np.zeros((h, w), np.float32)
    amp, total = 1.0, 0.0
    for o in range(octaves):
        s = max(2, int(scale / (2 ** o)))
        small = r.random((h // s + 2, w // s + 2)).astype(np.float32)
        big = np.array(Image.fromarray((small * 255).astype(np.uint8)).resize((w + 2 * s, h + 2 * s), Image.BICUBIC), np.float32) / 255
        acc += amp * big[s:s + h, s:s + w]
        total += amp
        amp *= 0.5
    return acc / total


def gradient(w, h, top, bottom):
    t = np.linspace(0, 1, h, dtype=np.float32)[:, None, None]
    return (np.array(top, np.float32) * (1 - t) + np.array(bottom, np.float32) * t) * np.ones((1, w, 1), np.float32)


def to_img(a):
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


def icon_canvas():
    return Image.new("RGBA", (768, 768), (0, 0, 0, 0))


# ---------------------------------------------------------------- Estelar (stars, nebula)

def starfield(w, h, density, seed, nebula=True):
    base = gradient(w, h, (6, 8, 26), (14, 6, 30))
    if nebula:
        n1 = smooth_noise(w, h, 520, 5, seed) ** 2.2
        n2 = smooth_noise(w, h, 380, 5, seed + 1) ** 2.6
        base += n1[..., None] * np.array([120, 40, 150], np.float32) * 0.8
        base += n2[..., None] * np.array([20, 90, 160], np.float32) * 0.8
    img = to_img(base)
    glow = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    g = ImageDraw.Draw(glow)
    r = random.Random(seed)
    for _ in range(int(w * h * density)):
        x, y = r.random() * w, r.random() * h
        b = r.random() ** 4
        c = r.choice([(255, 255, 255), (200, 220, 255), (255, 236, 200), (255, 210, 230)])
        c = tuple(int(v * (0.35 + 0.65 * b)) for v in c)
        s = 0.6 + 2.2 * b
        d.ellipse((x - s, y - s, x + s, y + s), fill=c)
        if b > 0.7:
            g.ellipse((x - 4 * s, y - 4 * s, x + 4 * s, y + 4 * s), fill=tuple(v // 2 for v in c))
    glow = glow.filter(ImageFilter.GaussianBlur(5))
    return Image.fromarray(np.clip(np.array(img, np.int32) + np.array(glow, np.int32), 0, 255).astype(np.uint8))


def star_icon(points=5, inner=0.42):
    im = icon_canvas(); d = ImageDraw.Draw(im)
    c, R = 384, 330
    pts = []
    for i in range(points * 2):
        a = -math.pi / 2 + i * math.pi / points
        rr = R if i % 2 == 0 else R * inner
        pts.append((c + rr * math.cos(a), c + rr * math.sin(a)))
    d.polygon(pts, fill="white")
    return im


def planet_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    d.ellipse((214, 214, 554, 554), fill="white")
    ring = Image.new("RGBA", (768, 768)); rd = ImageDraw.Draw(ring)
    rd.ellipse((40, 300, 728, 468), outline="white", width=44)
    ring = ring.rotate(-22, resample=Image.BICUBIC)
    # Hide the ring behind the planet's top half.
    mask = Image.new("L", (768, 768), 255); md = ImageDraw.Draw(mask)
    md.ellipse((214, 214, 554, 554), fill=0)
    md.rectangle((0, 384, 768, 768), fill=255)
    cut = Image.new("L", (768, 768), 0); ImageDraw.Draw(cut).ellipse((200, 200, 568, 568), fill=255)
    ring_alpha = np.array(ring.split()[3], np.float32)
    behind = (np.array(cut) > 0) & (np.arange(768)[:, None] < 384 + (np.arange(768)[None, :] - 384) * math.tan(math.radians(22)))
    ring_alpha[behind] = 0
    ring.putalpha(Image.fromarray(ring_alpha.astype(np.uint8)))
    gap = Image.new("L", (768, 768), 0); ImageDraw.Draw(gap).ellipse((14, 280, 754, 488), outline=255, width=96)
    gap = gap.rotate(-22, resample=Image.BICUBIC)
    a = np.array(im.split()[3], np.float32)
    front = (np.array(gap) > 0) & ~behind
    a[front & (np.array(ring.split()[3]) == 0)] = 0
    im.putalpha(Image.fromarray(a.astype(np.uint8)))
    im.alpha_composite(ring)
    return im


def moon_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    d.ellipse((110, 110, 658, 658), fill="white")
    d.ellipse((260, 40, 760, 540), fill=(0, 0, 0, 0))
    star = star_icon(4, 0.3).resize((200, 200), Image.LANCZOS)
    im.alpha_composite(star, (470, 470))
    return im


def comet_icon():
    im = icon_canvas()
    tail = Image.new("L", (768, 768), 0); td = ImageDraw.Draw(tail)
    for i in range(60):
        t = i / 60
        x = 520 - 430 * t; y = 248 + 430 * t
        r = 130 * (1 - t) + 10
        td.ellipse((x - r, y - r, x + r, y + r), fill=int(255 * (1 - t) ** 1.4))
    im.putalpha(tail)
    head = Image.new("RGBA", (768, 768)); ImageDraw.Draw(head).ellipse((410, 138, 650, 378), fill="white")
    im = Image.alpha_composite(Image.merge("RGBA", (*Image.new("RGB", (768, 768), "white").split(), tail)), head)
    return im


def estelar():
    t = "Estelar"
    save_jpg(starfield(W, H, 0.00028, 11), t, "background-sky.jpg")
    strip = starfield(1200, 360, 0.0009, 12)
    save_jpg(strip, t, "background-nebula.jpg")
    band = starfield(1200, 300, 0.0007, 13, nebula=True)
    save_jpg(band.filter(ImageFilter.GaussianBlur(1.2)), t, "background-band.jpg")
    save_icon(star_icon(), t, "icon-star.png")
    save_icon(planet_icon(), t, "icon-planet.png")
    save_icon(moon_icon(), t, "icon-moon.png")
    img = lambda f, dim=0, tint=False: {"file": f, "dim": dim, "tint": tint}
    title = {"type": "TITLE", "framed": True, "image": img("background-nebula.jpg", 25), "lines": [
        {"kind": "TITLE"}, {"kind": "ARTIST"}, {"kind": "TEMPLATE", "template": "{album}[ · {year}]", "size": "SMALL"}]}
    info = {"type": "TEXT", "template": "{format}[ · {samplerate}][ · {bpm}][ · {duration}]", "size": "SMALL", "boxed": False}
    transport = {"type": "BUTTONS", "buttons": ["SHUFFLE", "PREVIOUS", "PLAY_PAUSE", "NEXT", "REPEAT"]}
    listbox = {"type": "BOX", "framed": True, "horizontal": True, "image": img("background-band.jpg", 35), "children": [
        {"type": "LIST", "width": 75, "framed": False, "lines": [{"kind": "LIST_NAME"}, {"kind": "LIST_DETAILS"}]},
        {"type": "BUTTONS", "width": 25, "buttons": ["FAVORITE"], "icons": {"FAVORITE": img("icon-star.png", tint=True)}}]}
    # The navigator (the way to the side menu) keeps its usual icon in every sample, so it is easy to find.
    tools = {"type": "BUTTONS", "buttons": ["NAVIGATOR", "LISTS", "SLEEP", "QUICK_LIST", "SOUND"], "icons": {
        "LISTS": img("icon-planet.png", tint=True), "SLEEP": img("icon-moon.png", tint=True)}}
    portrait = [{"type": "COVER"}, title, info, {"type": "PROGRESS"}, transport, listbox, tools]
    landscape = [{"type": "BOX", "framed": False, "horizontal": True, "children": [
        {"type": "BOX", "framed": False, "width": 42, "children": [{"type": "COVER"}, listbox]},
        {"type": "BOX", "framed": False, "width": 58, "children": [title, info, {"type": "PROGRESS"}, transport, tools]}]}]
    return t, {
        "format": 1, "name": t, "dark": True,
        "background": "#FF070A1C", "surface": "#FF141A3A", "text": "#FFE8ECFF", "textSecondary": "#A6E8ECFF",
        "accent": "#FFFFD27A", "coverAccent": False, "excluded": "#FFFFB74D", "danger": "#FFFF6B81",
        "corners": 1.0, "textScale": 1.0, "font": "SYSTEM", "playerBackground": "PLAIN",
        "playerLayout": portrait, "playerLayoutLandscape": landscape,
        "backgrounds": {"PLAYER": img("background-sky.jpg", 10), "OTHERS": img("background-sky.jpg", 50)},
        "icons": {"PLAYLIST": img("icon-planet.png", tint=True), "NO_COVER": img("icon-planet.png", tint=True),
                  "LISTS": img("icon-planet.png", tint=True), "BOOKMARKS": img("icon-star.png", tint=True)},
    }


# ---------------------------------------------------------------- Otoño (leaves)

def leaf_shape(size, lobes=False):
    im = Image.new("L", (size, size), 0); d = ImageDraw.Draw(im)
    c = size / 2
    pts = []
    for i in range(200):
        a = i / 200 * 2 * math.pi
        # Pointed both ends, wider in the middle.
        # Narrow tip at the top, rounder towards the stem.
        r = abs(math.sin(a)) ** 1.25 * size * 0.2 * (1.15 - 0.3 * math.cos(a))
        if lobes:
            r *= 1 + 0.18 * math.sin(5 * a) ** 2
        y = -math.cos(a) * size * 0.44
        x = r * (1 if math.sin(a) >= 0 else -1)
        pts.append((c + x, c + y))
    d.polygon(pts, fill=255)
    d.line((c, c - size * 0.44, c, c + size * 0.5), fill=0, width=max(2, size // 40))
    for k in range(-3, 4):
        y0 = c + k * size * 0.1
        d.line((c, y0, c + size * 0.15, y0 - size * 0.08), fill=0, width=max(1, size // 70))
        d.line((c, y0, c - size * 0.15, y0 - size * 0.08), fill=0, width=max(1, size // 70))
    return im


def leaves(w, h, count, seed, top, bottom, blur_far=True):
    base = to_img(gradient(w, h, top, bottom) + (smooth_noise(w, h, 300, 4, seed)[..., None] - 0.5) * 30).convert("RGBA")
    r = random.Random(seed)
    palette = [(196, 82, 30), (222, 132, 40), (176, 50, 32), (232, 176, 62), (140, 70, 30), (205, 105, 45)]
    for i in range(count):
        size = int(r.uniform(60, 220))
        shape = leaf_shape(size * 2, lobes=r.random() < 0.5).rotate(r.uniform(0, 360), resample=Image.BICUBIC, expand=True)
        far = size < 110
        col = r.choice(palette)
        layer = Image.new("RGBA", shape.size, col + (0,))
        alpha = np.array(shape, np.float32) * (0.55 if far else 0.92)
        layer.putalpha(Image.fromarray(alpha.astype(np.uint8)))
        if far and blur_far:
            layer = layer.filter(ImageFilter.GaussianBlur(3))
        base.alpha_composite(layer, (int(r.uniform(-size, w - size)), int(r.uniform(-size, h - size))))
    return base.convert("RGB")


def acorn_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    d.ellipse((214, 300, 554, 700), fill="white")
    d.chord((170, 190, 598, 470), 180, 360, fill="white")
    d.rectangle((170, 326, 598, 352), fill="white")
    d.line((384, 200, 420, 90), fill="white", width=40)
    d.line((176, 372, 592, 372), fill=(0, 0, 0, 0), width=26)
    return im


def mushroom_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    d.chord((90, 120, 678, 560), 180, 360, fill="white")
    d.rounded_rectangle((300, 330, 468, 680), radius=60, fill="white")
    d.line((90, 352, 678, 352), fill=(0, 0, 0, 0), width=30)
    for x, y, r in ((250, 250, 40), (420, 200, 34), (540, 280, 30)):
        d.ellipse((x - r, y - r, x + r, y + r), fill=(0, 0, 0, 0))
    return im


def leaf_icon(lobes):
    shape = leaf_shape(768, lobes).rotate(-30, resample=Image.BICUBIC)
    im = Image.new("RGBA", (768, 768), (255, 255, 255, 0)); im.putalpha(shape)
    return im


def otono():
    t = "Autumn"
    save_jpg(leaves(W, H, 46, 21, (58, 30, 22), (24, 14, 12)), t, "background-leaves.jpg")
    save_jpg(leaves(1200, 380, 26, 22, (120, 60, 26), (80, 36, 18)).filter(ImageFilter.GaussianBlur(1)), t, "background-bark.jpg")
    save_jpg(leaves(1200, 320, 20, 23, (150, 96, 40), (110, 64, 28)).filter(ImageFilter.GaussianBlur(1.5)), t, "background-amber.jpg")
    save_icon(leaf_icon(False), t, "icon-leaf.png")
    save_icon(leaf_icon(True), t, "icon-oak.png")
    save_icon(acorn_icon(), t, "icon-acorn.png")
    save_icon(mushroom_icon(), t, "icon-mushroom.png")
    img = lambda f, dim=0, tint=False, light=None: {"file": f, "dim": dim, "tint": tint, **({"dimLight": light} if light is not None else {})}
    title = {"type": "TITLE", "framed": False, "image": img("background-bark.jpg", 40, light=70), "lines": [
        {"kind": "TITLE"}, {"kind": "ARTIST"}, {"kind": "ALBUM"}]}
    info = {"type": "BOX", "framed": True, "horizontal": True, "image": img("background-amber.jpg", 50, light=78), "children": [
        {"type": "TEXT", "template": "[{year}][ · {genre}]", "size": "SMALL"},
        {"type": "TEXT", "template": "{format}[ · {bitrate}]", "size": "SMALL"}]}
    transport = {"type": "BUTTONS", "buttons": ["SEEK_BACK", "PREVIOUS", "PLAY_PAUSE", "NEXT", "SEEK_FORWARD"]}
    tools = {"type": "BUTTONS", "buttons": ["SHUFFLE", "QUEUE", "FAVORITE", "QUICK_LIST", "SLEEP", "NAVIGATOR", "REPEAT"], "icons": {
        "QUEUE": img("icon-leaf.png", tint=True),
        "SLEEP": img("icon-mushroom.png", tint=True)}}
    portrait = [{"type": "COVER"}, title, info, {"type": "PROGRESS"}, transport, tools]
    landscape = [{"type": "BOX", "framed": False, "horizontal": True, "children": [
        {"type": "BOX", "framed": False, "width": 42, "children": [{"type": "COVER"}, info]},
        {"type": "BOX", "framed": False, "width": 58, "children": [title, {"type": "PROGRESS"}, transport, tools]}]}]
    return t, {
        "format": 1, "name": "Otoño", "dark": False,
        "background": "#FFF7EBDD", "surface": "#FFEEDCC4", "text": "#FF3A2216", "textSecondary": "#B33A2216",
        "accent": "#FFC4561E", "coverAccent": False, "excluded": "#FFD9480F", "danger": "#FFB3261E",
        "corners": 1.2, "textScale": 1.0, "font": "SERIF", "playerBackground": "PLAIN",
        "playerLayout": portrait, "playerLayoutLandscape": landscape,
        "backgrounds": {"PLAYER": img("background-leaves.jpg", 20, light=80), "OTHERS": img("background-leaves.jpg", 55, light=88)},
        "icons": {"PLAYLIST": img("icon-leaf.png", tint=True), "NO_COVER": img("icon-oak.png", tint=True),
                  "LISTS": img("icon-leaf.png", tint=True), "BOOKMARKS": img("icon-acorn.png", tint=True)},
        "alternate": {"dark": True, "background": "#FF1E120C", "surface": "#FF2E1D14", "text": "#FFF6E3CF",
                      "textSecondary": "#B3F6E3CF", "accent": "#FFF08A3C", "excluded": "#FFFFB74D", "danger": "#FFFF6B5B"},
    }


# ---------------------------------------------------------------- Minicadena (hi-fi system)

def brushed(w, h, seed, tone=(58, 60, 64), strength=18):
    r = np.random.default_rng(seed)
    lines = r.normal(0, 1, (h, 1)).astype(np.float32)
    streak = np.array(Image.fromarray(((r.random((h, w // 8)) * 255)).astype(np.uint8)).resize((w, h), Image.BILINEAR), np.float32) / 255 - 0.5
    a = np.array(tone, np.float32)[None, None, :] + (lines * 0.6 + streak * 1.4)[..., None] * strength
    shade = np.linspace(1.08, 0.86, h, dtype=np.float32)[:, None, None]
    return to_img(a * shade)


def grille(img, box, hole=7, step=22):
    d = ImageDraw.Draw(img)
    x0, y0, x1, y1 = box
    d.rounded_rectangle(box, radius=36, fill=(22, 22, 24), outline=(90, 92, 96), width=4)
    row = 0
    y = y0 + step
    while y < y1 - step / 2:
        x = x0 + step + (step / 2 if row % 2 else 0)
        while x < x1 - step / 2:
            d.ellipse((x - hole, y - hole, x + hole, y + hole), fill=(6, 6, 7))
            x += step
        y += step * 0.87
        row += 1


def lcd(w, h, seed):
    base = gradient(w, h, (18, 40, 34), (8, 22, 18))
    a = base + (smooth_noise(w, h, 120, 3, seed)[..., None] - 0.5) * 14
    img = to_img(a)
    d = ImageDraw.Draw(img)
    for x in range(0, w, 6):
        d.line((x, 0, x, h), fill=(0, 0, 0), width=1)
    for y in range(0, h, 6):
        d.line((0, y, w, y), fill=(0, 0, 0), width=1)
    return img.filter(ImageFilter.GaussianBlur(0.6))


def speaker_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    d.rounded_rectangle((170, 60, 598, 708), radius=60, outline="white", width=40)
    d.ellipse((290, 130, 478, 318), outline="white", width=34)
    d.ellipse((230, 360, 538, 668), outline="white", width=38)
    d.ellipse((350, 480, 418, 548), fill="white")
    return im


def cassette_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    d.rounded_rectangle((60, 170, 708, 598), radius=50, outline="white", width=38)
    d.rounded_rectangle((170, 250, 598, 420), radius=30, outline="white", width=26)
    for cx in (270, 498):
        d.ellipse((cx - 50, 285, cx + 50, 385), outline="white", width=22)
    d.polygon([(220, 598), (260, 500), (508, 500), (548, 598)], outline="white", width=26)
    return im


def cd_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    d.ellipse((60, 60, 708, 708), fill="white")
    d.ellipse((310, 310, 458, 458), fill=(0, 0, 0, 0))
    d.arc((150, 150, 618, 618), 200, 260, fill=(0, 0, 0, 0), width=22)
    d.arc((210, 210, 558, 558), 20, 80, fill=(0, 0, 0, 0), width=18)
    return im


def knob_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    d.ellipse((120, 120, 648, 648), outline="white", width=44)
    d.line((384, 384, 384 + 190 * math.cos(math.radians(-60)), 384 + 190 * math.sin(math.radians(-60))), fill="white", width=48)
    for k in range(9):
        a = math.radians(135 + k * 33.75)
        d.line((384 + 300 * math.cos(a), 384 + 300 * math.sin(a), 384 + 360 * math.cos(a), 384 + 360 * math.sin(a)), fill="white", width=22)
    return im



# Physical keys: brushed aluminium with a bevel, the symbol engraved. Drawn untinted, so the app
# shows each as the whole button.

def symbol_mask(kind):
    """A white symbol on a 768 canvas (alpha = shape)."""
    if kind in ("cassette", "knob", "cd"):
        return {"cassette": cassette_icon, "knob": knob_icon, "cd": cd_icon}[kind]().split()[3]
    m = Image.new("L", (768, 768), 0); d = ImageDraw.Draw(m)
    if kind == "play_pause":
        d.polygon([(150, 214), (150, 554), (400, 384)], fill=255)
        d.rectangle((460, 224, 520, 544), fill=255); d.rectangle((570, 224, 630, 544), fill=255)
    elif kind == "next":
        d.polygon([(200, 214), (200, 554), (480, 384)], fill=255); d.rectangle((500, 214, 570, 554), fill=255)
    elif kind == "previous":
        d.polygon([(568, 214), (568, 554), (288, 384)], fill=255); d.rectangle((198, 214, 268, 554), fill=255)
    elif kind == "next_album":
        d.polygon([(150, 234), (150, 534), (390, 384)], fill=255); d.polygon([(390, 234), (390, 534), (630, 384)], fill=255)
    elif kind == "previous_album":
        d.polygon([(618, 234), (618, 534), (378, 384)], fill=255); d.polygon([(378, 234), (378, 534), (138, 384)], fill=255)
    elif kind == "repeat":
        d.rounded_rectangle((170, 250, 598, 518), radius=110, outline=255, width=52)
        d.rectangle((300, 200, 420, 300), fill=0)
        d.polygon([(300, 170), (300, 330), (190, 250)], fill=255)
    elif kind == "shuffle":
        d.line((150, 250, 300, 250, 470, 518, 560, 518), fill=255, width=50, joint="curve")
        d.line((150, 518, 300, 518, 470, 250, 560, 250), fill=255, width=50, joint="curve")
        d.polygon([(560, 170), (560, 330), (660, 250)], fill=255)
        d.polygon([(560, 438), (560, 598), (660, 518)], fill=255)
    elif kind == "menu":
        for y in (250, 384, 518):
            d.rounded_rectangle((170, y - 34, 598, y + 34), radius=34, fill=255)
    elif kind == "sleep":
        d.ellipse((150, 150, 618, 618), fill=255); d.ellipse((290, 80, 760, 520), fill=0)
    elif kind == "quick":
        # A big four-pointed sparkle and a small one: a list made at once.
        def sparkle(cx, cy, r, w):
            d.polygon([(cx, cy - r), (cx + w, cy - w), (cx + r, cy), (cx + w, cy + w),
                       (cx, cy + r), (cx - w, cy + w), (cx - r, cy), (cx - w, cy - w)], fill=255)
        sparkle(330, 430, 250, 62)
        sparkle(570, 230, 120, 32)
    return m


def key(kind, round_button=False):
    size = 768
    k = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    body = brushed(size, size, sum(map(ord, kind)), tone=(176, 178, 184), strength=16).convert("RGBA")
    shape = Image.new("L", (size, size), 0)
    radius = (size - 72) // 2 if round_button else 110
    ImageDraw.Draw(shape).rounded_rectangle((36, 36, size - 36, size - 36), radius=radius, fill=255)
    # Bevel: light from the top left, shade at the bottom right.
    bevel = Image.new("L", (size, size), 0); bd = ImageDraw.Draw(bevel)
    bd.rounded_rectangle((36, 36, size - 36, size - 36), radius=radius, outline=255, width=26)
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    light = np.clip(1.2 - (xx + yy) / size, -1, 1)
    arr = np.array(body, np.float32)
    edge = np.array(bevel, np.float32)[..., None] / 255
    arr[..., :3] += edge * light[..., None] * 70
    arr[..., :3] *= (1.06 - 0.16 * yy / size)[..., None]
    if round_button:
        # A domed push button: a soft highlight towards the top left, darker rim.
        r = np.hypot(xx - size * 0.42, yy - size * 0.38) / (size * 0.5)
        arr[..., :3] += (np.clip(1 - r, 0, 1) ** 2 * 55)[..., None]
        rim = np.hypot(xx - size / 2, yy - size / 2) / (size / 2 - 36)
        arr[..., :3] *= (1 - 0.25 * np.clip((rim - 0.8) / 0.2, 0, 1))[..., None]
    # Engraved symbol: dark, with a light lip below.
    sym = symbol_mask(kind).resize((430, 430), Image.LANCZOS)
    full = Image.new("L", (size, size), 0); full.paste(sym, ((size - 430) // 2, (size - 430) // 2))
    lip = Image.new("L", (size, size), 0); lip.paste(sym, ((size - 430) // 2 + 6, (size - 430) // 2 + 8))
    s = np.array(full, np.float32)[..., None] / 255
    l = np.clip(np.array(lip, np.float32)[..., None] / 255 - s, 0, 1)
    ink = np.array([28, 30, 34], np.float32)
    arr[..., :3] = arr[..., :3] * (1 - s) + ink * s
    arr[..., :3] += l * 60
    arr[..., 3] = np.array(shape, np.float32)
    shadow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sm = Image.new("L", (size, size), 0); ImageDraw.Draw(sm).rounded_rectangle((44, 56, size - 28, size - 20), radius=radius, fill=150)
    shadow.putalpha(sm.filter(ImageFilter.GaussianBlur(10)))
    k.alpha_composite(shadow)
    k.alpha_composite(Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGBA"))
    return k

def minicadena():
    t = "Minicadena"
    bg = brushed(W, H, 31)
    d = ImageDraw.Draw(bg)
    d.rounded_rectangle((40, 40, 1040, 2240), radius=48, outline=(110, 112, 118), width=5)
    save_jpg(bg, t, "background-panel.jpg")
    save_jpg(lcd(1200, 420, 32), t, "background-lcd.jpg")
    save_jpg(brushed(1200, 280, 33, tone=(150, 152, 158), strength=22), t, "background-alu.jpg")
    speaker = Image.new("RGB", (1200, 240), (22, 22, 24))
    grille(speaker, (0, 0, 1199, 239), hole=8, step=26)
    save_jpg(speaker, t, "background-grille.jpg")
    save_jpg(brushed(1200, 260, 34, tone=(40, 41, 44), strength=14), t, "background-deck.jpg")
    save_icon(speaker_icon(), t, "icon-speaker.png")
    save_icon(cassette_icon(), t, "icon-cassette.png")
    save_icon(cd_icon(), t, "icon-cd.png")
    save_icon(knob_icon(), t, "icon-knob.png")
    img = lambda f, dim=0, tint=False: {"file": f, "dim": dim, "tint": tint}
    display = {"type": "BOX", "framed": True, "image": img("background-lcd.jpg", 0), "children": [
        {"type": "TEXT", "template": "[{position}/{count}][  {duration}][  {format}][ {bitrate}]", "size": "SMALL"},
        {"type": "TITLE", "framed": False, "lines": [{"kind": "TITLE", "size": "LARGE"}, {"kind": "ARTIST", "size": "MEDIUM"}]},
        {"type": "TEXT", "template": "[{samplerate}][ · {bits}][ · {gain}]", "size": "SMALL"}]}
    names = {"PREVIOUS_ALBUM": "previous_album", "PREVIOUS": "previous", "PLAY_PAUSE": "play_pause", "NEXT": "next",
             "NEXT_ALBUM": "next_album", "SHUFFLE": "shuffle", "QUEUE": "cassette", "SOUND": "knob", "LISTS": "cd",
             "SLEEP": "sleep", "NAVIGATOR": "menu", "REPEAT": "repeat", "QUICK_LIST": "quick"}
    transport = ["PREVIOUS_ALBUM", "PREVIOUS", "PLAY_PAUSE", "NEXT", "NEXT_ALBUM"]
    functions = ["SHUFFLE", "QUEUE", "SOUND", "QUICK_LIST", "SLEEP", "NAVIGATOR", "REPEAT"]
    # Transport: square keys; the functions below: round push buttons.
    for action, kind in names.items():
        save_icon(key(kind, round_button=action in functions), t, "key-%s.png" % kind.replace("_", "-"))
    keys = lambda actions: {a: img("key-%s.png" % names[a].replace("_", "-")) for a in actions}
    meter = {"type": "BOX", "framed": True, "image": img("background-lcd.jpg", 0), "children": [{"type": "PROGRESS"}]}
    deck = {"type": "BOX", "framed": True, "image": img("background-deck.jpg", 0), "children": [
        {"type": "BUTTONS", "buttons": transport, "icons": keys(transport)}]}
    tools = {"type": "BOX", "framed": True, "image": img("background-grille.jpg", 0), "children": [
        {"type": "BUTTONS", "buttons": functions, "icons": keys(functions)}]}
    portrait = [{"type": "COVER"}, display, meter, deck, tools]
    landscape = [{"type": "BOX", "framed": False, "horizontal": True, "children": [
        {"type": "BOX", "framed": False, "width": 40, "children": [{"type": "COVER"}, tools]},
        {"type": "BOX", "framed": False, "width": 60, "children": [display, meter, deck]}]}]
    return t, {
        "format": 1, "name": t, "dark": True,
        "background": "#FF1A1B1E", "surface": "#FF2A2C30", "text": "#FFE9ECEF", "textSecondary": "#A6E9ECEF",
        "accent": "#FF4FE3C1", "coverAccent": False, "excluded": "#FFFFB300", "danger": "#FFFF5252",
        "corners": 0.6, "textScale": 1.0, "font": "MONOSPACE", "playerBackground": "PLAIN",
        "playerLayout": portrait, "playerLayoutLandscape": landscape,
        "backgrounds": {"PLAYER": img("background-panel.jpg", 0), "OTHERS": img("background-panel.jpg", 45)},
        "icons": {"PLAYLIST": img("icon-cassette.png", tint=True), "NO_COVER": img("icon-cd.png", tint=True),
                  "LISTS": img("icon-cd.png", tint=True), "SOUND": img("icon-knob.png", tint=True),
                  "DEVICES": img("icon-speaker.png", tint=True)},
    }



# ---------------------------------------------------------------- Prado (green meadow)

def meadow(w, h, seed, horizon=0.55, flowers=True):
    r = random.Random(seed)
    a = gradient(w, h, (150, 200, 235), (225, 240, 230))
    clouds = smooth_noise(w, h, 260, 4, seed) ** 3
    a += clouds[..., None] * np.array([255, 255, 255], np.float32) * 0.9 * np.linspace(1, 0, h, dtype=np.float32)[:, None, None]
    img = to_img(a).convert("RGBA")
    d = ImageDraw.Draw(img)
    hy = int(h * horizon)
    # Rolling hills, far (pale) to near (deep green).
    for k, (col, amp, base) in enumerate((((150, 190, 120), 60, 0), ((110, 170, 80), 80, 60), ((70, 140, 55), 70, 140))):
        pts = [(0, h)]
        for x in range(0, w + 20, 20):
            y = hy + (base + amp * math.sin(x / w * math.pi * (1.3 + k * 0.6) + seed + k)) * h / 2280
            pts.append((x, y))
        pts.append((w, h))
        d.polygon(pts, fill=col)
    # Grass blades and flowers on the near hill.
    for _ in range(int(w * 1.6)):
        x = r.random() * w
        y = hy + (0.25 + 0.75 * r.random() ** 0.6) * (h - hy)
        L = r.uniform(18, 60) * (y - hy) / (h - hy) + 6
        lean = r.uniform(-0.5, 0.5)
        g = r.randint(90, 170)
        d.line((x, y, x + lean * L, y - L), fill=(40, g, 40), width=max(1, int(L / 14)))
    if flowers:
        for _ in range(int(w * 0.12)):
            x = r.random() * w
            y = hy + (0.15 + 0.85 * r.random()) * (h - hy)
            s = r.uniform(4, 13) * (0.4 + (y - hy) / (h - hy))
            c = r.choice([(255, 255, 255), (255, 220, 60), (240, 120, 160), (180, 140, 230)])
            for i in range(5):
                ang = i * 2 * math.pi / 5
                px, py = x + s * math.cos(ang), y + s * math.sin(ang)
                d.ellipse((px - s * 0.7, py - s * 0.7, px + s * 0.7, py + s * 0.7), fill=c)
            d.ellipse((x - s * 0.55, y - s * 0.55, x + s * 0.55, y + s * 0.55), fill=(250, 190, 40))
    return img.convert("RGB")


def grass_strip(w, h, seed):
    r = random.Random(seed)
    img = to_img(gradient(w, h, (120, 180, 80), (60, 120, 45)))
    d = ImageDraw.Draw(img)
    for _ in range(w * 3):
        x = r.random() * w
        y = h * (0.3 + 0.7 * r.random())
        L = r.uniform(20, 70)
        d.line((x, y, x + r.uniform(-12, 12), y - L), fill=(40, r.randint(110, 190), 40), width=2)
    return img.filter(ImageFilter.GaussianBlur(1.2))


def flower_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    for i in range(6):
        a = i * math.pi / 3
        x, y = 384 + 190 * math.cos(a), 384 + 190 * math.sin(a)
        d.ellipse((x - 130, y - 130, x + 130, y + 130), fill="white")
    d.ellipse((290, 290, 478, 478), fill=(0, 0, 0, 0))
    d.ellipse((318, 318, 450, 450), fill="white")
    return im


def clover_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    for a in (-90, 30, 150):
        x, y = 384 + 150 * math.cos(math.radians(a)), 340 + 150 * math.sin(math.radians(a))
        d.ellipse((x - 150, y - 150, x + 150, y + 150), fill="white")
    d.line((384, 400, 470, 720), fill="white", width=44)
    return im


def sun_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    d.ellipse((234, 234, 534, 534), fill="white")
    for i in range(12):
        a = i * math.pi / 6
        d.line((384 + 210 * math.cos(a), 384 + 210 * math.sin(a), 384 + 330 * math.cos(a), 384 + 330 * math.sin(a)), fill="white", width=44)
    return im


def butterfly_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    d.ellipse((64, 120, 364, 420), fill="white"); d.ellipse((404, 120, 704, 420), fill="white")
    d.ellipse((134, 380, 354, 640), fill="white"); d.ellipse((414, 380, 634, 640), fill="white")
    d.rounded_rectangle((344, 150, 424, 670), radius=40, fill=(0, 0, 0, 0))
    d.rounded_rectangle((362, 180, 406, 640), radius=22, fill="white")
    d.line((384, 190, 300, 70), fill="white", width=20); d.line((384, 190, 468, 70), fill="white", width=20)
    return im


def prado():
    t = "Prado"
    save_jpg(meadow(W, H, 41), t, "background-meadow.jpg")
    save_jpg(meadow(1200, 380, 42, horizon=0.35, flowers=False), t, "background-hill.jpg")
    save_jpg(grass_strip(1200, 300, 43), t, "background-grass.jpg")
    save_icon(flower_icon(), t, "icon-flower.png")
    save_icon(clover_icon(), t, "icon-clover.png")
    save_icon(sun_icon(), t, "icon-sun.png")
    save_icon(butterfly_icon(), t, "icon-butterfly.png")
    img = lambda f, dim=0, tint=False, light=None: {"file": f, "dim": dim, "tint": tint, **({"dimLight": light} if light is not None else {})}
    title = {"type": "TITLE", "framed": True, "image": img("background-hill.jpg", 50, light=72), "lines": [
        {"kind": "TITLE"}, {"kind": "ARTIST"}, {"kind": "TEMPLATE", "template": "{album}[ · {year}]", "size": "SMALL"}]}
    transport = {"type": "BUTTONS", "buttons": ["SHUFFLE", "PREVIOUS", "PLAY_PAUSE", "NEXT", "REPEAT"]}
    listbox = {"type": "BOX", "framed": True, "horizontal": True, "image": img("background-grass.jpg", 55, light=60), "children": [
        {"type": "LIST", "width": 75, "framed": False, "lines": [{"kind": "LIST_NAME"}, {"kind": "LIST_DETAILS"}]},
        {"type": "BUTTONS", "width": 25, "buttons": ["FAVORITE"], "icons": {"FAVORITE": img("icon-clover.png", tint=True)}}]}
    tools = {"type": "BUTTONS", "buttons": ["NAVIGATOR", "BOOKMARK", "SLEEP", "QUICK_LIST", "LYRICS", "SOUND"], "icons": {
        "BOOKMARK": img("icon-flower.png", tint=True), "SLEEP": img("icon-sun.png", tint=True),
        "LYRICS": img("icon-butterfly.png", tint=True)}}
    portrait = [{"type": "COVER"}, title, {"type": "PROGRESS"}, transport, listbox, tools]
    landscape = [{"type": "BOX", "framed": False, "horizontal": True, "children": [
        {"type": "BOX", "framed": False, "width": 42, "children": [{"type": "COVER"}, listbox]},
        {"type": "BOX", "framed": False, "width": 58, "children": [title, {"type": "PROGRESS"}, transport, tools]}]}]
    return t, {
        "format": 1, "name": t, "dark": False,
        "background": "#FFF1F7EA", "surface": "#FFE1EED4", "text": "#FF1F3A1C", "textSecondary": "#B31F3A1C",
        "accent": "#FF3E9B2F", "coverAccent": False, "excluded": "#FFE07B00", "danger": "#FFC62828",
        "corners": 1.6, "textScale": 1.0, "font": "SYSTEM", "playerBackground": "PLAIN",
        "playerLayout": portrait, "playerLayoutLandscape": landscape,
        "backgrounds": {"PLAYER": img("background-meadow.jpg", 25, light=55), "OTHERS": img("background-meadow.jpg", 60, light=85)},
        "icons": {"PLAYLIST": img("icon-clover.png", tint=True), "NO_COVER": img("icon-flower.png", tint=True),
                  "LISTS": img("icon-clover.png", tint=True), "BOOKMARKS": img("icon-flower.png", tint=True)},
        "alternate": {"dark": True, "background": "#FF0F1A0E", "surface": "#FF1B2B19", "text": "#FFE7F3DF",
                      "textSecondary": "#B3E7F3DF", "accent": "#FF8BD46E", "excluded": "#FFFFB74D", "danger": "#FFFF6B6B"},
    }


# ---------------------------------------------------------------- Marítimo (the sea)

def sea(w, h, seed, horizon=0.3, moon=True):
    a = gradient(w, h, (20, 40, 80), (90, 130, 170))
    hy = int(h * horizon)
    yy = np.arange(h, dtype=np.float32)[:, None]
    xx = np.arange(w, dtype=np.float32)[None, :]
    t = np.clip((yy - hy) / max(1, h - hy), 0, 1)
    water = np.stack([12 + 10 * (1 - t), 50 + 40 * (1 - t), 90 + 50 * (1 - t)], -1) * np.ones((1, w, 1), np.float32)
    # Noise stretched sideways: long, low swells rather than blobs.
    n = np.array(Image.fromarray((smooth_noise(max(8, w // 10), h, 60, 4, seed) * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC), np.float32) / 255
    # Wave crests: thin bright bands, wider and further apart towards the bottom.
    phase = (yy - hy) / (4 + 30 * t) + n * 2.2
    crest = np.clip(np.cos(phase * 2 * math.pi), 0, 1) ** 14
    water += crest[..., None] * np.array([120, 160, 190], np.float32) * (0.25 + 0.5 * t[..., None])
    below = (yy >= hy)[..., None]
    a = np.where(below, water, a)
    if moon:
        # The moon and its path on the water.
        mx, my = w * 0.7, hy * 0.45
        r2 = (xx - mx) ** 2 + (yy - my) ** 2
        a += (np.exp(-r2 / (2 * (w * 0.03) ** 2)) * 230)[..., None] * (~below)
        a += (np.exp(-r2 / (2 * (w * 0.12) ** 2)) * 50)[..., None] * (~below)
        path = np.exp(-((xx - mx) ** 2) / (2 * (w * (0.02 + 0.08 * t)) ** 2)) * crest
        a += (path * 200)[..., None] * below
    return to_img(a)


def anchor_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    d.ellipse((314, 60, 454, 200), outline="white", width=40)
    d.line((384, 200, 384, 660), fill="white", width=48)
    d.line((240, 300, 528, 300), fill="white", width=44)
    d.arc((130, 330, 638, 690), 20, 160, fill="white", width=48)
    d.polygon([(110, 470), (210, 420), (190, 540)], fill="white")
    d.polygon([(658, 470), (558, 420), (578, 540)], fill="white")
    return im


def wheel_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    d.ellipse((164, 164, 604, 604), outline="white", width=46)
    d.ellipse((324, 324, 444, 444), fill="white")
    for i in range(8):
        a = i * math.pi / 4
        d.line((384, 384, 384 + 330 * math.cos(a), 384 + 330 * math.sin(a)), fill="white", width=34)
        x, y = 384 + 340 * math.cos(a), 384 + 340 * math.sin(a)
        d.ellipse((x - 34, y - 34, x + 34, y + 34), fill="white")
    return im


def lighthouse_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    d.polygon([(300, 700), (468, 700), (430, 250), (338, 250)], fill="white")
    d.rectangle((318, 170, 450, 250), fill="white")
    d.polygon([(300, 170), (468, 170), (384, 90)], fill="white")
    d.rectangle((338, 190, 430, 232), fill=(0, 0, 0, 0))
    for y in (360, 500):
        d.rectangle((290, y, 480, y + 50), fill=(0, 0, 0, 0))
    d.polygon([(470, 190), (740, 120), (740, 300)], fill=(255, 255, 255, 140))
    d.polygon([(298, 190), (28, 120), (28, 300)], fill=(255, 255, 255, 140))
    return im


def wave_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    for k, y in enumerate((260, 420, 580)):
        pts = [(x, y + 60 * math.sin(x / 768 * 4 * math.pi + k)) for x in range(60, 712, 8)]
        d.line(pts, fill="white", width=48, joint="curve")
    return im


def maritimo():
    t = "Maritime"
    day, night = sample_art.coast(W, H, False), sample_art.coast(W, H, True)
    save_jpg(night, t, "background-coast-night.jpg")
    save_jpg(day, t, "background-coast-day.jpg")
    # Landscape: the lighthouse in the gap the landscape layout leaves between the cover and the controls.
    save_jpg(sample_art.coast(2400, 1080, True, g=sample_art.LANDSCAPE), t, "background-coast-wide-night.jpg")
    save_jpg(sample_art.coast(2400, 1080, False, g=sample_art.LANDSCAPE), t, "background-coast-wide-day.jpg")
    # The open sea, for the title box.
    for name, im in (("background-waves-night.jpg", night), ("background-waves-day.jpg", day)):
        save_jpg(im.crop((0, 1500, 1080, 1842)).resize((1200, 380), Image.LANCZOS), t, name)
    save_icon(anchor_icon(), t, "icon-anchor.png")
    save_icon(wheel_icon(), t, "icon-wheel.png")
    save_icon(lighthouse_icon(), t, "icon-lighthouse.png")
    save_icon(wave_icon(), t, "icon-wave.png")
    img = lambda f, dim=0, tint=False, light=None, file_light=None: {
        "file": f, "dim": dim, "tint": tint, **({"dimLight": light} if light is not None else {}),
        **({"fileLight": file_light} if file_light else {})}
    title = {"type": "TITLE", "framed": True, "image": img("background-waves-night.jpg", 35, light=60, file_light="background-waves-day.jpg"), "lines": [
        {"kind": "TITLE"}, {"kind": "ARTIST"}, {"kind": "ALBUM"}]}
    info = {"type": "TEXT", "template": "{format}[ · {samplerate}][ · {loudness}][ · {duration}]", "size": "SMALL", "boxed": False}
    transport = {"type": "BUTTONS", "buttons": ["PREVIOUS_ALBUM", "PREVIOUS", "PLAY_PAUSE", "NEXT", "NEXT_ALBUM"]}
    tools = {"type": "BUTTONS", "buttons": ["SHUFFLE", "QUEUE", "FAVORITE", "QUICK_LIST", "NAVIGATOR", "SOUND", "REPEAT"], "icons": {
        "QUEUE": img("icon-wave.png", tint=True),
        "SOUND": img("icon-lighthouse.png", tint=True)}}
    portrait = [{"type": "COVER"}, title, info, {"type": "PROGRESS"}, transport, tools]
    landscape = [{"type": "BOX", "framed": False, "horizontal": True, "children": [
        {"type": "BOX", "framed": False, "width": 36, "children": [{"type": "COVER"}, info]},
        {"type": "SPACE", "width": 19},
        {"type": "BOX", "framed": False, "width": 45, "children": [title, {"type": "PROGRESS"}, transport, tools]}]}]
    coast = lambda dim, light: {**img("background-coast-night.jpg", dim, light=light, file_light="background-coast-day.jpg"),
                                "fileWide": "background-coast-wide-night.jpg", "fileWideLight": "background-coast-wide-day.jpg"}
    return t, {
        "format": 1, "name": "Marina", "dark": True,
        "background": "#FF07182B", "surface": "#FF10283F", "text": "#FFE6F1F8", "textSecondary": "#A6E6F1F8",
        "accent": "#FFF2C14E", "coverAccent": False, "excluded": "#FFFFB74D", "danger": "#FFFF6B6B",
        "corners": 0.8, "textScale": 1.0, "font": "SERIF", "playerBackground": "PLAIN",
        "playerLayout": portrait, "playerLayoutLandscape": landscape,
        "backgrounds": {"PLAYER": coast(15, 35), "OTHERS": coast(55, 78)},
        "icons": {"PLAYLIST": img("icon-wave.png", tint=True), "NO_COVER": img("icon-wheel.png", tint=True),
                  "LISTS": img("icon-anchor.png", tint=True), "BOOKMARKS": img("icon-anchor.png", tint=True)},
        "alternate": {"dark": False, "background": "#FFEAF3F8", "surface": "#FFD6E6F0", "text": "#FF0B2236",
                      "textSecondary": "#B30B2236", "accent": "#FF1F6FA8", "excluded": "#FFE07B00", "danger": "#FFC62828"},
    }


# ---------------------------------------------------------------- Trigo (a wheat field in the sun)

def wheat_icon():
    im = icon_canvas()
    d = ImageDraw.Draw(im)
    d.line((384, 740, 384, 300), fill="white", width=24)
    # Grains in pairs up the stalk, leaning out, each with its awn.
    for k in range(5):
        y = 580 - k * 86
        for side in (-1, 1):
            grain = Image.new("RGBA", (96, 160), (0, 0, 0, 0))
            ImageDraw.Draw(grain).ellipse((8, 8, 88, 152), fill="white")
            grain = grain.rotate(-side * 32, expand=True, resample=Image.BICUBIC)
            im.alpha_composite(grain, (int(384 + side * 66 - grain.width / 2), int(y - grain.height / 2)))
            d.line((384 + side * 100, y - 60, 384 + side * 170, y - 170), fill="white", width=9)
    d.ellipse((339, 70, 429, 230), fill="white")
    d.line((384, 75, 384, 10), fill="white", width=9)
    return im


def poppy_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    for k in range(4):
        a = k * math.pi / 2 + math.pi / 4
        x, y = 384 + 150 * math.cos(a), 330 + 130 * math.sin(a)
        d.ellipse((x - 175, y - 150, x + 175, y + 150), fill="white")
    d.ellipse((320, 266, 448, 394), fill=(0, 0, 0, 0))
    d.ellipse((350, 296, 418, 364), fill="white")
    d.line((384, 470, 420, 740), fill="white", width=30)
    return im


def windmill_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    d.polygon([(300, 740), (468, 740), (430, 360), (338, 360)], fill="white")
    d.polygon([(320, 360), (448, 360), (384, 290)], fill="white")
    d.rectangle((360, 620, 408, 740), fill=(0, 0, 0, 0))
    for k in range(4):
        a = k * math.pi / 2 + math.pi / 4
        ex, ey = 384 + 330 * math.cos(a), 330 + 330 * math.sin(a)
        px, py = -math.sin(a) * 46, math.cos(a) * 46
        d.polygon([(384 + px * 0.3, 330 + py * 0.3), (ex + px * 0.3, ey + py * 0.3), (ex + px * 1.6, ey + py * 1.6), (384 + px * 1.4, 330 + py * 1.4)], fill="white")
    d.ellipse((354, 300, 414, 360), fill="white")
    return im


def bale_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    d.ellipse((90, 170, 678, 640), fill="white")
    d.ellipse((170, 240, 598, 570), fill=(0, 0, 0, 0))
    d.ellipse((230, 290, 538, 520), fill="white")
    d.ellipse((300, 345, 468, 465), fill=(0, 0, 0, 0))
    d.ellipse((350, 380, 418, 430), fill="white")
    return im


def trigo():
    t = "Wheat"
    day, dusk = sample_art.wheat(W, H, False), sample_art.wheat(W, H, True)
    save_jpg(dusk, t, "background-field-dusk.jpg")
    save_jpg(day, t, "background-field-day.jpg")
    # Ears close up, for the title box.
    for name, im in (("background-ears-dusk.jpg", dusk), ("background-ears-day.jpg", day)):
        save_jpg(im.crop((0, 1640, 1080, 1980)).resize((1200, 380), Image.LANCZOS), t, name)
    save_icon(wheat_icon(), t, "icon-wheat.png")
    save_icon(poppy_icon(), t, "icon-poppy.png")
    save_icon(windmill_icon(), t, "icon-windmill.png")
    save_icon(bale_icon(), t, "icon-bale.png")
    save_icon(sun_icon(), t, "icon-sun.png")
    img = lambda f, dim=0, tint=False, light=None, file_light=None: {
        "file": f, "dim": dim, "tint": tint, **({"dimLight": light} if light is not None else {}),
        **({"fileLight": file_light} if file_light else {})}
    title = {"type": "TITLE", "framed": True, "image": img("background-ears-dusk.jpg", 70, light=70, file_light="background-ears-day.jpg"), "lines": [
        {"kind": "TITLE"}, {"kind": "ARTIST"}, {"kind": "TEMPLATE", "template": "{album}[ · {year}]", "size": "SMALL"}]}
    mood = {"type": "TEXT", "template": "[{mood}][ · {family}][ · {bpm}]", "size": "SMALL", "boxed": False}
    transport = {"type": "BUTTONS", "buttons": ["SHUFFLE", "PREVIOUS_ALBUM", "PREVIOUS", "PLAY_PAUSE", "NEXT", "NEXT_ALBUM", "REPEAT"]}
    listbox = {"type": "LIST", "framed": True, "lines": [{"kind": "LIST_NAME"}, {"kind": "LIST_DETAILS"}]}
    tools = {"type": "BUTTONS", "buttons": ["NAVIGATOR", "BOOKMARK", "SLEEP", "QUICK_LIST", "FAVORITE", "LYRICS", "SOUND"], "icons": {
        "BOOKMARK": img("icon-poppy.png", tint=True), "SLEEP": img("icon-sun.png", tint=True),
        "FAVORITE": img("icon-wheat.png", tint=True), "LYRICS": img("icon-windmill.png", tint=True)}}
    portrait = [{"type": "COVER"}, title, mood, {"type": "PROGRESS"}, transport, listbox, tools]
    landscape = [{"type": "BOX", "framed": False, "horizontal": True, "children": [
        {"type": "BOX", "framed": False, "width": 42, "children": [{"type": "COVER"}, listbox]},
        {"type": "BOX", "framed": False, "width": 58, "children": [title, mood, {"type": "PROGRESS"}, transport, tools]}]}]
    field = lambda dim, light: img("background-field-dusk.jpg", dim, light=light, file_light="background-field-day.jpg")
    return t, {
        "format": 1, "name": "Trigo", "dark": False,
        "background": "#FFFFF8D8", "surface": "#FFF8EBAA", "text": "#FF3A300A", "textSecondary": "#B33A300A",
        "accent": "#FFC99A06", "coverAccent": False, "excluded": "#FFD84315", "danger": "#FFC62828",
        "corners": 1.25, "textScale": 1.0, "font": "SYSTEM", "playerBackground": "PLAIN",
        "playerLayout": portrait, "playerLayoutLandscape": landscape,
        "backgrounds": {"PLAYER": field(55, 40), "OTHERS": field(74, 80)},
        "icons": {"PLAYLIST": img("icon-wheat.png", tint=True), "NO_COVER": img("icon-bale.png", tint=True),
                  "LISTS": img("icon-wheat.png", tint=True), "BOOKMARKS": img("icon-poppy.png", tint=True)},
        "alternate": {"dark": True, "background": "#FF1C1A08", "surface": "#FF302B10", "text": "#FFFBF1C8",
                      "textSecondary": "#B3FBF1C8", "accent": "#FFF6CF3A", "excluded": "#FFFF8A65", "danger": "#FFFF6B6B"},
    }


# ---------------------------------------------------------------- Marte (the red planet)

def rocket_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    d.ellipse((284, 60, 484, 560), fill="white")
    d.rectangle((284, 300, 484, 600), fill="white")
    d.ellipse((334, 230, 434, 330), fill=(0, 0, 0, 0))
    d.polygon([(284, 440), (170, 640), (284, 600)], fill="white")
    d.polygon([(484, 440), (598, 640), (484, 600)], fill="white")
    d.polygon([(320, 610), (448, 610), (384, 740)], fill=(255, 255, 255, 170))
    return im


def rover_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    d.rounded_rectangle((150, 360, 618, 500), radius=30, fill="white")
    d.rectangle((210, 300, 470, 360), fill="white")
    d.line((470, 330, 560, 200), fill="white", width=24)
    d.rounded_rectangle((520, 150, 640, 220), radius=16, fill="white")
    for x in (210, 384, 558):
        d.ellipse((x - 70, 520, x + 70, 660), fill="white")
        d.ellipse((x - 26, 566, x + 26, 614), fill=(0, 0, 0, 0))
    d.line((200, 500, 210, 530), fill="white", width=20)
    d.line((568, 500, 558, 530), fill="white", width=20)
    return im


def mars_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    d.ellipse((94, 94, 674, 674), fill="white")
    for (x, y, r) in ((280, 270, 70), (480, 380, 95), (330, 500, 50), (520, 220, 40), (230, 420, 30)):
        d.ellipse((x - r, y - r, x + r, y + r), fill=(0, 0, 0, 0))
        d.ellipse((x - r * 0.6, y - r * 0.6, x + r * 0.6, y + r * 0.6), fill=(255, 255, 255, 120))
    return im


def dish_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    d.chord((110, 120, 590, 600), 120, 300, fill="white")
    d.line((350, 360, 540, 170), fill="white", width=22)
    d.ellipse((510, 140, 580, 210), fill="white")
    d.polygon([(320, 420), (440, 420), (500, 720), (260, 720)], fill="white")
    return im


def marte():
    t = "Mars"
    day, night = sample_art.mars(W, H, False), sample_art.mars(W, H, True)
    save_jpg(night, t, "background-mars-night.jpg")
    save_jpg(day, t, "background-mars-day.jpg")
    for name, im in (("background-dunes-night.jpg", night), ("background-dunes-day.jpg", day)):
        save_jpg(im.crop((0, 1560, 1080, 1900)).resize((1200, 380), Image.LANCZOS), t, name)
    save_icon(rocket_icon(), t, "icon-rocket.png")
    save_icon(rover_icon(), t, "icon-rover.png")
    save_icon(mars_icon(), t, "icon-mars.png")
    save_icon(dish_icon(), t, "icon-dish.png")
    img = lambda f, dim=0, tint=False, light=None, file_light=None: {
        "file": f, "dim": dim, "tint": tint, **({"dimLight": light} if light is not None else {}),
        **({"fileLight": file_light} if file_light else {})}
    # The title box shows the cover itself, blurred: the cover of the track, as through a dusty visor.
    title = {"type": "TITLE", "framed": True, "cover": {"fit": "CROP", "dim": 55, "blur": 25}, "lines": [
        {"kind": "TITLE", "size": "LARGE"}, {"kind": "ARTIST", "size": "MEDIUM"}, {"kind": "ALBUM", "size": "MEDIUM"}]}
    info = {"type": "TEXT", "template": "{codec}[ · {bitrate}][ · {samplerate}][ · {mood}]", "size": "SMALL", "boxed": True}
    transport = {"type": "BUTTONS", "buttons": ["SHUFFLE", "PREVIOUS", "SEEK_BACK", "PLAY_PAUSE", "SEEK_FORWARD", "NEXT", "REPEAT"]}
    listbox = {"type": "BOX", "framed": True, "horizontal": True, "image": img("background-dunes-night.jpg", 50, light=62, file_light="background-dunes-day.jpg"), "children": [
        {"type": "LIST", "width": 78, "framed": False, "lines": [{"kind": "LIST_NAME"}, {"kind": "LIST_DETAILS"}]},
        {"type": "BUTTONS", "width": 22, "buttons": ["QUEUE"], "icons": {"QUEUE": img("icon-rover.png", tint=True)}}]}
    tools = {"type": "BUTTONS", "buttons": ["NAVIGATOR", "BOOKMARK", "FAVORITE", "SLEEP", "QUICK_LIST", "SOUND"], "icons": {
        "BOOKMARK": img("icon-rocket.png", tint=True), "SLEEP": img("icon-mars.png", tint=True),
        "SOUND": img("icon-dish.png", tint=True)}}
    portrait = [{"type": "COVER"}, title, info, {"type": "PROGRESS"}, transport, listbox, tools]
    landscape = [{"type": "BOX", "framed": False, "horizontal": True, "children": [
        {"type": "BOX", "framed": False, "width": 42, "children": [{"type": "COVER"}, listbox]},
        {"type": "BOX", "framed": False, "width": 58, "children": [title, info, {"type": "PROGRESS"}, transport, tools]}]}]
    land = lambda dim, light: img("background-mars-night.jpg", dim, light=light, file_light="background-mars-day.jpg")
    return t, {
        "format": 1, "name": "Marte", "dark": True,
        "background": "#FF1A0B08", "surface": "#FF2C1510", "text": "#FFF6E2D6", "textSecondary": "#A6F6E2D6",
        "accent": "#FFFF7043", "coverAccent": False, "excluded": "#FFFFB74D", "danger": "#FFFF5252",
        "corners": 0.6, "textScale": 1.0, "font": "MONOSPACE", "playerBackground": "PLAIN",
        "playerLayout": portrait, "playerLayoutLandscape": landscape,
        "backgrounds": {"PLAYER": land(20, 40), "OTHERS": land(60, 80)},
        "icons": {"PLAYLIST": img("icon-rover.png", tint=True), "NO_COVER": img("icon-mars.png", tint=True),
                  "LISTS": img("icon-rocket.png", tint=True), "BOOKMARKS": img("icon-rocket.png", tint=True)},
        "alternate": {"dark": False, "background": "#FFF7E6D8", "surface": "#FFEED2BE", "text": "#FF3B160C",
                      "textSecondary": "#B33B160C", "accent": "#FFC1440E", "excluded": "#FFE07B00", "danger": "#FFC62828"},
    }


# ---------------------------------------------------------------- Rayos (a burst of golden rays)

def rays(w, h, night, seed=11):
    """Golden rays bursting from a glow in the middle."""
    base = gradient(w, h, (40, 28, 8), (22, 14, 4)) if night else gradient(w, h, (236, 214, 150), (214, 182, 104))
    cx, cy = w * 0.5, h * 0.36
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    angle = np.arctan2(yy - cy, xx - cx)
    dist = np.hypot(xx - cx, yy - cy) / max(w, h)
    r = np.random.default_rng(seed)
    # Many thin rays of random width and brightness, sharper far from the centre.
    count = 180
    phases = np.sort(r.random(count) * 2 * np.pi)
    widths = r.uniform(0.004, 0.016, count)
    strength = r.uniform(0.35, 1.0, count)
    ray = np.zeros((h, w), np.float32)
    for p, wd, s in zip(phases, widths, strength):
        d = np.abs(np.angle(np.exp(1j * (angle - p))))
        ray = np.maximum(ray, s * np.clip(1 - d / wd, 0, 1))
    ray *= np.clip((dist - 0.08) / 0.25, 0, 1)
    glow = np.exp(-(dist / 0.22) ** 2)
    noise = smooth_noise(w, h, 260, 4, seed)
    if night:
        colour = np.array([210, 170, 80], np.float32)
        img = base + ray[..., None] * colour * 0.55 + glow[..., None] * np.array([120, 92, 40], np.float32) + (noise[..., None] - 0.5) * 22
    else:
        colour = np.array([255, 246, 210], np.float32)
        img = base * (1 - 0.5 * ray[..., None]) + ray[..., None] * colour * 0.5 + glow[..., None] * np.array([40, 34, 18], np.float32) + (noise[..., None] - 0.5) * 18
    return to_img(img)


def sunburst_icon():
    im = icon_canvas(); d = ImageDraw.Draw(im)
    for i in range(16):
        a = i * math.pi / 8
        d.polygon([(384 + 90 * math.cos(a - 0.12), 384 + 90 * math.sin(a - 0.12)), (384 + 360 * math.cos(a), 384 + 360 * math.sin(a)),
                   (384 + 90 * math.cos(a + 0.12), 384 + 90 * math.sin(a + 0.12))], fill="white")
    d.ellipse((264, 264, 504, 504), fill="white")
    return im


def rayos():
    t = "Rays"
    night, day = rays(W, H, True), rays(W, H, False)
    save_jpg(night, t, "background-rays-night.jpg")
    save_jpg(day, t, "background-rays-day.jpg")
    save_icon(cd_icon(), t, "icon-cd.png")
    save_icon(sunburst_icon(), t, "icon-sunburst.png")
    img = lambda f, dim=0, tint=False, light=None, file_light=None: {
        "file": f, "dim": dim, "tint": tint, **({"dimLight": light} if light is not None else {}),
        **({"fileLight": file_light} if file_light else {})}
    # The layout of the old player screen: where it is in the list on top, the title, artist and
    # album big in the middle, the cover at the left of the times and the file's details.
    position = {"type": "TEXT", "template": "{position}/{count}[ · {list}]", "size": "SMALL"}
    title = {"type": "TITLE", "framed": False, "lines": [
        {"kind": "TITLE", "size": "HUGE"}, {"kind": "ARTIST", "size": "LARGE"}, {"kind": "ALBUM", "size": "MEDIUM"}]}
    transport = {"type": "BUTTONS", "buttons": ["SHUFFLE", "PREVIOUS", "SEEK_BACK", "PLAY_PAUSE", "SEEK_FORWARD", "NEXT", "REPEAT"]}
    tools = {"type": "BUTTONS", "buttons": ["NAVIGATOR", "LISTS", "QUEUE", "FAVORITE", "SLEEP", "QUICK_LIST", "SOUND"],
             "icons": {"LISTS": img("icon-cd.png", tint=True), "SOUND": img("icon-sunburst.png", tint=True)}}
    # Upright, the cover goes on top, where the old screen had the list; the title under it, then
    # the times and the file's details.
    info = {"type": "BOX", "framed": False, "children": [
        {"type": "PROGRESS"},
        {"type": "TEXT", "template": "[{year}][ · {genre}]", "size": "SMALL"},
        {"type": "TEXT", "template": "{codec}[ @ {bitrate}][ @ {samplerate}][ · ▶ {plays}]", "size": "SMALL"}]}
    portrait = [position, {"type": "COVER"}, title, info, transport, tools]
    landscape = [{"type": "BOX", "framed": False, "horizontal": True, "children": [
        {"type": "BOX", "framed": False, "width": 40, "children": [{"type": "COVER"}]},
        {"type": "BOX", "framed": False, "width": 60, "children": [position, title,
            {"type": "PROGRESS"}, {"type": "TEXT", "template": "{codec}[ @ {bitrate}][ @ {samplerate}]", "size": "SMALL"},
            transport, tools]}]}]
    back = lambda dim, light: img("background-rays-night.jpg", dim, light=light, file_light="background-rays-day.jpg")
    return t, {
        "format": 1, "name": "Rayos", "dark": True,
        "background": "#FF1C1408", "surface": "#FF2E2210", "text": "#FFFDF996", "textSecondary": "#B3FDF996",
        "accent": "#FFE6B422", "coverAccent": False, "excluded": "#FFFF9999", "danger": "#FFFF6E6E",
        "corners": 0.5, "textScale": 1.0, "font": "SYSTEM", "playerBackground": "PLAIN",
        "playerLayout": portrait, "playerLayoutLandscape": landscape,
        "backgrounds": {"PLAYER": back(15, 30), "OTHERS": back(65, 75)},
        "icons": {"NO_COVER": img("icon-cd.png", tint=True), "PLAYLIST": img("icon-cd.png", tint=True)},
        "alternate": {"dark": False, "background": "#FFF6EBC8", "surface": "#FFEAD9A6", "text": "#FF3A2A05",
                      "textSecondary": "#B33A2A05", "accent": "#FF9A7200", "excluded": "#FFC2185B", "danger": "#FFC62828"},
    }


MAKERS = {"Estelar": estelar, "Autumn": otono, "Minicadena": minicadena, "Prado": prado, "Maritime": maritimo, "Wheat": trigo, "Mars": marte, "Rays": rayos}
for theme_key, make in MAKERS.items():
    if ONLY and theme_key not in ONLY:
        continue
    name, spec = make()
    text = json.dumps(spec, indent=2, ensure_ascii=False).replace("\n", "\r\n") + "\r\n"
    with open(os.path.join(OUT, name + ".json"), "wb") as f:
        f.write(text.encode("utf-8"))
    print("written", name)
