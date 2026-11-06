"""The app icon (src/main/resources/icon.png and icon.ico): Attacca's mark (a slur joining two
groups of waveform bars) over the lines of a list, on a black rounded square."""
import os
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
OUT = os.path.join(ROOT, 'src', 'main', 'resources')
S = 1024  # drawn large, scaled down
RED = (255, 69, 58, 255)
WHITE = (255, 255, 255, 255)


def draw():
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, S - 1, S - 1], radius=S // 5, fill=(0, 0, 0, 255))
    u = S / 64.0  # the mark on a 64-unit grid

    def rect(x, y, w, h, color):
        d.rounded_rectangle([x * u, y * u, (x + w) * u, (y + h) * u], radius=u * 0.6, fill=color)

    # Bars: three red rising, three white falling.
    for i, h in enumerate([8, 13, 17]):
        rect(13 + i * 6, 34 - h, 4.2, h, RED)
    for i, h in enumerate([17, 13, 8]):
        rect(32.8 + i * 6, 34 - h, 4.2, h, WHITE)
    # The slur over them (a quadratic curve, as in Attacca's icon), drawn as round dots.
    p0, c, p1 = (20, 14), (31, 3), (42, 14)
    r = 1.8 * u
    for i in range(401):
        t = i / 400
        x = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * c[0] + t * t * p1[0]
        y = (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * c[1] + t * t * p1[1]
        d.ellipse([x * u - r, y * u - r, x * u + r, y * u + r], fill=WHITE)
    # The list: three lines, the first one red (the track playing).
    for i, color in enumerate([RED, WHITE, WHITE]):
        y = 39 + i * 6.5
        d.ellipse([13 * u, y * u, 16.4 * u, (y + 3.4) * u], fill=color)
        rect(19.5, y + 0.4, 31.5 - i * 5, 2.6, color)
    return img


img = draw()
os.makedirs(OUT, exist_ok=True)
img.resize((512, 512), Image.LANCZOS).save(os.path.join(OUT, 'icon.png'))
img.save(os.path.join(OUT, 'icon.ico'), sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print('icon.png, icon.ico written to', OUT)
