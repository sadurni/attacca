"""The sample themes shipped with Attacca (Vinilo, Aurora): pictures drawn here, and their JSON."""
import json
import math
import os
import random
import sys

# Needs Pillow (pip install pillow). Writes app/src/main/assets/themes/ of this project.
from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'assets', 'themes')
random.seed(7)


def save(img, theme, name, **kw):
    d = os.path.join(OUT, theme)
    os.makedirs(d, exist_ok=True)
    img.save(os.path.join(d, name), **kw)


# ---------- Vinilo ----------
def vinyl_background(w=1080, h=2280):
    img = Image.new('RGB', (w, h), (20, 14, 26))
    px = img.load()
    cx, cy = w * 0.5, h * 0.36
    for y in range(h):
        for x in range(w):
            r = math.hypot(x - cx, y - cy)
            # Warm light near the record's label, fading to ink.
            glow = max(0.0, 1 - r / (w * 0.95))
            groove = 0.5 + 0.5 * math.sin(r / 3.2)
            shade = 0.06 * groove * (1 if r < w * 0.62 else 0.25)
            px[x, y] = (
                int(20 + 90 * glow ** 2 + 255 * shade * 0.5),
                int(14 + 45 * glow ** 2 + 255 * shade * 0.35),
                int(26 + 30 * glow ** 2 + 255 * shade * 0.45),
            )
    return img.filter(ImageFilter.GaussianBlur(0.6))


def warm_strip(w=1200, h=420):
    img = Image.new('RGB', (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = x / w
            u = y / h
            px[x, y] = (int(110 + 120 * t - 20 * u), int(40 + 70 * t - 10 * u), int(70 - 20 * t + 10 * u))
    glow = Image.new('RGB', (w, h), (0, 0, 0))
    ImageDraw.Draw(glow).ellipse((w * 0.55, -h * 0.6, w * 1.2, h * 0.9), fill=(255, 190, 120))
    img = Image.blend(img, glow.filter(ImageFilter.GaussianBlur(90)), 0.25)
    return img.filter(ImageFilter.GaussianBlur(2))


def wood(w=1200, h=360):
    img = Image.new('RGB', (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            g = math.sin(y / 9 + math.sin(x / 140) * 2.2) * 0.5 + 0.5
            px[x, y] = (int(70 + 40 * g), int(42 + 22 * g), int(30 + 12 * g))
    return img.filter(ImageFilter.GaussianBlur(1.2))


def icon_record(s=512):
    img = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse((16, 16, s - 16, s - 16), fill=(255, 255, 255, 255))
    for r in range(70, 230, 22):
        d.ellipse((s / 2 - r, s / 2 - r, s / 2 + r, s / 2 + r), outline=(0, 0, 0, 0), width=6)
    d.ellipse((s / 2 - 58, s / 2 - 58, s / 2 + 58, s / 2 + 58), fill=(0, 0, 0, 0))
    d.ellipse((s / 2 - 40, s / 2 - 40, s / 2 + 40, s / 2 + 40), fill=(255, 255, 255, 255))
    d.ellipse((s / 2 - 10, s / 2 - 10, s / 2 + 10, s / 2 + 10), fill=(0, 0, 0, 0))
    return img


def icon_moon(s=512):
    img = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse((60, 60, s - 60, s - 60), fill=(255, 255, 255, 255))
    d.ellipse((170, 20, s + 20, s - 120), fill=(0, 0, 0, 0))
    for (x, y, r) in ((380, 330, 18), (430, 230, 10), (330, 420, 8)):
        d.ellipse((x - r, y - r, x + r, y + r), fill=(255, 255, 255, 255))
    return img


def icon_compass(s=512):
    img = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = s / 2
    d.ellipse((30, 30, s - 30, s - 30), outline=(255, 255, 255, 255), width=26)
    d.polygon([(c, 70), (c + 46, c), (c, s - 70), (c - 46, c)], fill=(255, 255, 255, 255))
    d.polygon([(70, c), (c, c - 30), (s - 70, c), (c, c + 30)], fill=(255, 255, 255, 200))
    d.ellipse((c - 18, c - 18, c + 18, c + 18), fill=(0, 0, 0, 0))
    return img


def icon_heart_note(s=512):
    img = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse((60, 90, 260, 290), fill=(255, 255, 255, 255))
    d.ellipse((252, 90, 452, 290), fill=(255, 255, 255, 255))
    d.polygon([(70, 230), (s / 2, 470), (442, 230)], fill=(255, 255, 255, 255))
    d.ellipse((200, 230, 270, 290), fill=(0, 0, 0, 0))
    d.rectangle((256, 140, 270, 262), fill=(0, 0, 0, 0))
    return img


save(vinyl_background(), 'Vinilo', 'background-vinyl.jpg', quality=88)
save(warm_strip(), 'Vinilo', 'background-strip.jpg', quality=88)
save(wood(), 'Vinilo', 'background-wood.jpg', quality=88)
for name, f in (('icon-record.png', icon_record), ('icon-moon.png', icon_moon), ('icon-compass.png', icon_compass), ('icon-heart.png', icon_heart_note)):
    save(f().resize((192, 192), Image.LANCZOS), 'Vinilo', name)


# ---------- Aurora ----------
def aurora(w=1080, h=2280):
    img = Image.new('RGB', (w, h), (8, 18, 30))
    layer = Image.new('RGB', (w, h), (0, 0, 0))
    d = ImageDraw.Draw(layer)
    blobs = [((0.25, 0.18), 520, (30, 200, 160)), ((0.8, 0.28), 460, (120, 70, 210)), ((0.5, 0.55), 600, (20, 120, 180)),
             ((0.15, 0.8), 420, (60, 210, 120)), ((0.85, 0.85), 500, (150, 60, 170))]
    for (fx, fy), r, col in blobs:
        x, y = fx * w, fy * h
        d.ellipse((x - r, y - r * 0.6, x + r, y + r * 0.6), fill=col)
    layer = layer.filter(ImageFilter.GaussianBlur(180))
    img = Image.blend(img, layer, 0.55)
    # A few stars.
    d = ImageDraw.Draw(img)
    for _ in range(140):
        x, y = random.randint(0, w), random.randint(0, h)
        b = random.randint(120, 230)
        d.point((x, y), fill=(b, b, b))
    return img


def glass(w=1200, h=300):
    base = aurora(1080, 2280).crop((0, 300, 1080, 600)).resize((w, h))
    white = Image.new('RGB', (w, h), (210, 240, 235))
    return Image.blend(base, white, 0.18).filter(ImageFilter.GaussianBlur(3))


save(aurora(), 'Aurora', 'background-aurora.jpg', quality=86)
save(glass(), 'Aurora', 'background-glass.jpg', quality=86)


# ---------- JSON ----------
def block(t, **kw):
    b = {'type': t}
    b.update(kw)
    return b


def img(file, dim=0, tint=False):
    return {'file': file, 'dim': dim, 'tint': tint}


transport = ['SHUFFLE', 'PREVIOUS_ALBUM', 'PREVIOUS', 'PLAY_PAUSE', 'NEXT', 'NEXT_ALBUM', 'REPEAT']

vinilo_portrait = [
    block('COVER'),
    block('TITLE', framed=False, image=img('background-strip.jpg', 35),
          lines=[{'kind': 'TITLE'}, {'kind': 'ARTIST'}, {'kind': 'TEMPLATE', 'template': '{album}[ · {year}]', 'size': 'MEDIUM'}]),
    block('TEXT', template='{format}[ · {bitrate}][ · {samplerate}]', size='SMALL', boxed=False),
    block('PROGRESS'),
    block('BUTTONS', buttons=transport),
    block('BOX', framed=True, horizontal=True, image=img('background-wood.jpg', 55), children=[
        block('LIST', width=72, framed=False, lines=[{'kind': 'LIST_NAME'}, {'kind': 'LIST_DETAILS'}]),
        block('BUTTONS', width=28, buttons=['FAVORITE'], icons={'FAVORITE': img('icon-heart.png', tint=True)}),
    ]),
    block('BUTTONS', buttons=['NAVIGATOR', 'BOOKMARK', 'SLEEP', 'QUICK_LIST', 'LISTS', 'SOUND'],
          icons={'NAVIGATOR': img('icon-compass.png', tint=True), 'SLEEP': img('icon-moon.png', tint=True),
                 'LISTS': img('icon-record.png', tint=True)}),
]

vinilo = {
    'format': 1, 'name': 'Vinilo', 'dark': True,
    'background': '#FF140E1A', 'surface': '#FF241B30', 'text': '#FFF3E9DC', 'textSecondary': '#B3F3E9DC',
    'accent': '#FFF2A541', 'coverAccent': False, 'excluded': '#FFFF9800', 'danger': '#FFE05555',
    'corners': 1.4, 'textScale': 1.0, 'font': 'SERIF', 'playerBackground': 'PLAIN',
    'playerLayout': vinilo_portrait,
    'backgrounds': {'PLAYER': dict(img('background-vinyl.jpg', 15), dimLight=82), 'OTHERS': dict(img('background-vinyl.jpg', 55), dimLight=88)},
    'icons': {'PLAYLIST': img('icon-record.png', tint=True), 'NO_COVER': img('icon-record.png', tint=True),
              'LISTS': img('icon-record.png', tint=True)},
    'alternate': {'dark': False, 'background': '#FFF4EBDD', 'surface': '#FFE8DCC8', 'text': '#FF2B1D2E',
                  'textSecondary': '#B32B1D2E', 'accent': '#FFB5651D', 'excluded': '#FFE07B00', 'danger': '#FFC0392B'},
}

aurora_portrait = [
    block('COVER'),
    block('BOX', framed=True, image=img('background-glass.jpg', 30), children=[
        block('TEXT', template='{format}[ · {samplerate}][ · {bits}][ · {loudness}][ · {bpm}]', size='SMALL'),
    ]),
    block('TITLE', framed=False, lines=[{'kind': 'TITLE'}, {'kind': 'ARTIST'},
                                       {'kind': 'TEMPLATE', 'template': '{album}[ · {year}][ · {position}/{count}]', 'size': 'SMALL'}]),
    block('PROGRESS'),
    block('BUTTONS', buttons=['SEEK_BACK', 'PREVIOUS', 'PLAY_PAUSE', 'NEXT', 'SEEK_FORWARD']),
    block('BUTTONS', buttons=['SHUFFLE', 'QUEUE', 'FAVORITE', 'QUICK_LIST', 'SOUND', 'NAVIGATOR', 'REPEAT']),
]
aurora_landscape = [
    block('BOX', framed=False, horizontal=True, children=[
        block('BOX', framed=False, width=40, children=[
            block('COVER'),
            block('BOX', framed=True, image=img('background-glass.jpg', 30),
                  children=[block('TEXT', template='{format}[ · {samplerate}][ · {bits}][ · {bpm}]', size='SMALL')]),
        ]),
        block('BOX', framed=False, width=60, children=[
            block('TITLE', framed=False, lines=[{'kind': 'TITLE'}, {'kind': 'ARTIST'}, {'kind': 'ALBUM'}]),
            block('PROGRESS'),
            block('BUTTONS', buttons=['SEEK_BACK', 'PREVIOUS', 'PLAY_PAUSE', 'NEXT', 'SEEK_FORWARD']),
            block('BUTTONS', buttons=['SHUFFLE', 'QUEUE', 'FAVORITE', 'QUICK_LIST', 'SOUND', 'NAVIGATOR', 'REPEAT']),
        ]),
    ]),
]

aurora_theme = {
    'format': 1, 'name': 'Aurora', 'dark': True,
    'background': '#FF08121E', 'surface': '#FF13263A', 'text': '#FFE6F4F1', 'textSecondary': '#A6E6F4F1',
    'accent': '#FF3DDBB4', 'coverAccent': False, 'excluded': '#FFFFB74D', 'danger': '#FFFF6B81',
    'corners': 0.5, 'textScale': 1.0, 'font': 'SYSTEM', 'playerBackground': 'PLAIN',
    'playerLayout': aurora_portrait, 'playerLayoutLandscape': aurora_landscape,
    'backgrounds': {'PLAYER': img('background-aurora.jpg', 10), 'OTHERS': img('background-aurora.jpg', 55)},
}

for name, theme in (('Vinilo', vinilo), ('Aurora', aurora_theme)):
    with open(os.path.join(OUT, name + '.json'), 'w', encoding='utf-8') as f:
        json.dump(theme, f, ensure_ascii=False, indent=2)
print('ok', os.listdir(OUT))
