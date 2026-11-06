"""Landscapes of the sample themes Marítimo (a cliff with a lighthouse), Trigo (a wheat field) and
Marte, each by day and by night. Used by make_more_sample_themes.py."""
import math, random
import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from scipy.ndimage import gaussian_filter


def spectral_noise(n, seed, slope=2.2):
    """Tileable noise (n×n) with a 1/f^slope spectrum, values 0…1."""
    r = np.random.default_rng(seed)
    fx = np.fft.fftfreq(n)[None, :]
    fy = np.fft.fftfreq(n)[:, None]
    f = np.sqrt(fx ** 2 + fy ** 2)
    f[0, 0] = 1
    spectrum = (r.normal(size=(n, n)) + 1j * r.normal(size=(n, n))) / f ** (slope / 2)
    spectrum[0, 0] = 0
    a = np.real(np.fft.ifft2(spectrum))
    a = (a - a.min()) / (a.max() - a.min())
    return a.astype(np.float32)


def sample(tex, u, v):
    """Bilinear, wrapping."""
    n = tex.shape[0]
    u = u % n
    v = v % n
    x0 = np.floor(u).astype(int)
    y0 = np.floor(v).astype(int)
    fx = u - x0
    fy = v - y0
    x1 = (x0 + 1) % n
    y1 = (y0 + 1) % n
    return (tex[y0, x0] * (1 - fx) * (1 - fy) + tex[y0, x1] * fx * (1 - fy) + tex[y1, x0] * (1 - fx) * fy + tex[y1, x1] * fx * fy)


def lerp(a, b, t):
    return np.array(a, np.float32) * (1 - t) + np.array(b, np.float32) * t


def sky(w, h, hy, night, seed):
    t = np.clip(np.arange(h, dtype=np.float32) / hy, 0, 1)[:, None, None]
    if night:
        a = lerp((6, 12, 34), (30, 52, 92), t ** 1.6) * np.ones((1, w, 1), np.float32)
    else:
        a = lerp((70, 140, 215), (200, 228, 245), t ** 1.4) * np.ones((1, w, 1), np.float32)
    yy = np.arange(h, dtype=np.float32)[:, None]
    xx = np.arange(w, dtype=np.float32)[None, :]
    r = np.random.default_rng(seed)
    # Clouds: soft streaks, few.
    tex = spectral_noise(512, seed + 1, 2.6)
    c = sample(tex, xx / w * 180, yy / hy * 90)
    c = np.clip((c - 0.58) * 3.2, 0, 1) ** 1.5 * (yy < hy)
    cloud = np.array((40, 50, 80) if night else (255, 255, 255), np.float32)
    a = a * (1 - c[..., None] * 0.75) + cloud * c[..., None] * 0.75
    if night:
        stars = r.random((h, w)) > 0.9982
        mag = r.random((h, w)) ** 3
        s = (stars * mag * (yy < hy * 0.95)).astype(np.float32)
        s = np.array(Image.fromarray((s * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.7)), np.float32) / 255 * 3
        a += np.clip(s, 0, 1)[..., None] * 255 * (1 - c[..., None])
    return a


def body(a, cx, cy, radius, color, glow, glow_radius):
    h, w = a.shape[:2]
    yy = np.arange(h, dtype=np.float32)[:, None]
    xx = np.arange(w, dtype=np.float32)[None, :]
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    disc = np.clip(radius + 1 - d, 0, 1)
    halo = np.exp(-(d / glow_radius) ** 2) * glow
    a[:] = a * (1 - disc[..., None]) + np.array(color, np.float32) * disc[..., None]
    a += halo[..., None] * np.array(color, np.float32) / 255 * 160
    return a


def sea(a, hy, night, light_x, seed):
    h, w = a.shape[:2]
    yy = np.arange(hy, h, dtype=np.float32)[:, None]
    xx = np.arange(w, dtype=np.float32)[None, :]
    d = (yy - hy + 1) / (h - hy)          # 0 at the horizon … 1 at the bottom
    z = 1 / (d + 0.015)                    # distance
    tex = spectral_noise(512, seed, 2.4)
    tex2 = spectral_noise(256, seed + 5, 1.6)
    u = (xx - w / 2) / w * z * 34
    v = z * 22
    hgt = sample(tex, u, v) * 0.7 + sample(tex2, u * 3.1, v * 2.3) * 0.3
    # Long swells across.
    hgt += 0.07 * np.sin(v * 0.55 + sample(tex, u * 0.3, v * 0.3) * 9) * (0.5 + sample(tex2, u * 0.2, v * 0.2))
    dd = d * np.ones((1, w), np.float32)
    hgt = gaussian_filter(hgt, 0.6) * (1 - dd) + gaussian_filter(hgt, 3.5) * dd
    slope = np.zeros_like(hgt)
    slope[1:] = hgt[1:] - hgt[:-1]
    slope = slope * (0.6 + 6 * d)          # nearer waves show more relief
    fresnel = np.clip(1 - d * 1.3, 0, 1) ** 2.5
    if night:
        deep, shallow, skyc = (4, 14, 30), (14, 40, 70), (40, 62, 104)
    else:
        deep, shallow, skyc = (14, 70, 110), (40, 120, 155), (190, 222, 240)
    base = lerp(shallow, deep, np.clip(d ** 0.7, 0, 1)[..., None])
    refl = np.clip(fresnel[..., None] + np.clip(-slope, 0, 1)[..., None] * 4, 0, 1)
    col = base * (1 - refl) + np.array(skyc, np.float32) * refl
    col += np.clip(slope, 0, 1)[..., None] * (-60 if night else -90)
    # Glitter on the path of the sun or the moon.
    width = w * (0.015 + 0.16 * d)
    path = np.exp(-((xx - light_x) / width) ** 2)
    sparkle = np.clip((sample(tex2, u * 9, v * 6) - 0.62) * 8, 0, 1) * np.clip(-slope * 25, 0, 1)
    sparkle = gaussian_filter(sparkle, 0.8)
    glint = path * (0.35 * fresnel + sparkle)
    col += glint[..., None] * (np.array((230, 230, 200), np.float32) if night else np.array((255, 250, 225), np.float32))
    a[hy:] = col
    return a


def cliff_mask(w, h, hy, seed, g):
    """A headland on the right, in the middle distance: its face down to the sea, its grassy top."""
    r = random.Random(seed)
    top = int(h * g["top"])
    base_left = hy + h * g["base_left"]
    base_right = hy + h * g["base_right"]
    pts = []
    steps = 70
    for i in range(steps + 1):
        t = i / steps
        y = base_left - t * (base_left - top)
        x = w * g["face"] + t * w * g["face_dx"] + math.sin(t * 11 + seed) * w * 0.012 * g["rough"] + r.uniform(-1, 1) * w * 0.006 * g["rough"]
        pts.append((x, y))
    for i in range(1, 21):
        t = i / 20
        edge = g["face"] + g["face_dx"]
        pts.append((w * edge + t * w * (1.01 - edge), top - h * 0.012 * t + r.uniform(-1, 1) * 2))
    pts.append((w + 10, base_right))
    # The foot of the cliff, with rocks.
    for i in range(1, 41):
        t = i / 40
        x = w + 10 - t * (w + 10 - w * (g["face"] - 0.04))
        y = base_right + t * (base_left - base_right) + r.uniform(-1, 1) * h * 0.004
        pts.append((x, y))
    m = Image.new('L', (w, h), 0)
    ImageDraw.Draw(m).polygon(pts, fill=255)
    return m, top


def rock(w, h, seed, night, top, base):
    tex = spectral_noise(512, seed + 9, 2.0)
    tex2 = spectral_noise(256, seed + 13, 1.4)
    yy = np.arange(h, dtype=np.float32)[:, None]
    xx = np.arange(w, dtype=np.float32)[None, :]
    n = sample(tex, xx * 0.5, yy * 0.5)
    # Vertical fractures and a few strata.
    cracks = 1 - np.abs(2 * sample(tex2, xx * 2.4, yy * 0.25) - 1)
    strata = 0.5 + 0.5 * np.sin(yy / 31 + n * 5)
    v = 0.45 * n + 0.35 * cracks ** 3 + 0.2 * strata
    # Lit from the left at the face, darker toward the foot.
    face = np.clip((yy - top) / max(1, base - top), 0, 1)
    v = v * (1.05 - 0.45 * face)
    if night:
        dark, lightc = (14, 16, 24), (60, 66, 84)
    else:
        dark, lightc = (70, 60, 52), (196, 178, 150)
    return lerp(dark, lightc, np.clip(v, 0, 1)[..., None])


def lighthouse(draw_img, x, base_y, scale, night):
    d = ImageDraw.Draw(draw_img, 'RGBA')
    tw_b, tw_t, th = 70 * scale, 48 * scale, 300 * scale
    top = base_y - th
    white = (210, 214, 225) if night else (250, 250, 248)
    red = (120, 40, 40) if night else (200, 40, 40)
    d.polygon([(x - tw_b / 2, base_y), (x + tw_b / 2, base_y), (x + tw_t / 2, top), (x - tw_t / 2, top)], fill=white)
    for k in range(3):
        y1 = base_y - th * (0.18 + 0.27 * k)
        y2 = y1 - th * 0.12
        f1 = (base_y - y1) / th
        f2 = (base_y - y2) / th
        w1 = tw_b / 2 * (1 - f1) + tw_t / 2 * f1
        w2 = tw_b / 2 * (1 - f2) + tw_t / 2 * f2
        d.polygon([(x - w1, y1), (x + w1, y1), (x + w2, y2), (x - w2, y2)], fill=red)
    # Shade on the right side.
    d.polygon([(x + tw_b * 0.15, base_y), (x + tw_b / 2, base_y), (x + tw_t / 2, top), (x + tw_t * 0.12, top)], fill=(0, 0, 30, 50))
    # Gallery, lantern room and roof.
    d.rectangle((x - tw_t * 0.75, top - 8 * scale, x + tw_t * 0.75, top), fill=(40, 40, 46))
    lamp = (255, 226, 120) if night else (150, 180, 190)
    d.rectangle((x - tw_t * 0.42, top - 52 * scale, x + tw_t * 0.42, top - 8 * scale), fill=lamp)
    for k in (-1, 0, 1):
        d.line((x + k * tw_t * 0.21, top - 52 * scale, x + k * tw_t * 0.21, top - 8 * scale), fill=(40, 40, 46), width=max(1, int(3 * scale)))
    d.polygon([(x - tw_t * 0.62, top - 52 * scale), (x + tw_t * 0.62, top - 52 * scale), (x, top - 92 * scale)], fill=red)
    d.rectangle((x - 3 * scale, top - 108 * scale, x + 3 * scale, top - 92 * scale), fill=(40, 40, 46))
    return (x, top - 30 * scale)


# Where things are in the portrait picture; LANDSCAPE puts the lighthouse further left, for a gap
# between the cover and the controls of Now playing in landscape.
PORTRAIT = dict(horizon=0.47, top=0.25, base_left=0.075, base_right=0.13, face=0.56, face_dx=0.20, rough=1.0,
                light=0.915, light_scale=0.95, moon=(0.20, 0.094))
LANDSCAPE = dict(horizon=0.60, top=0.50, base_left=0.10, base_right=0.17, face=0.39, face_dx=0.045, rough=0.5,
                 light=0.48, light_scale=1.15, moon=(0.62, 0.17))


def coast(w, h, night, seed=61, g=PORTRAIT):
    hy = int(h * g["horizon"])
    a = sky(w, h, hy, night, seed)
    s = min(w, h)
    lx, ly = w * g["moon"][0], h * g["moon"][1]
    if night:
        a = body(a, lx, ly, s * 0.045, (245, 242, 225), 1.0, s * 0.16)
    else:
        a = body(a, lx, ly, s * 0.04, (255, 248, 225), 0.9, s * 0.13)
    a = sea(a, hy, night, lx, seed)
    img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).convert('RGB')
    m, top = cliff_mask(w, h, hy, seed, g)
    rk = Image.fromarray(np.clip(rock(w, h, seed, night, top, hy + h * g["base_right"]), 0, 255).astype(np.uint8))
    # Light from the left (the sun or the moon): the cliff's face is a little brighter at its edge.
    img.paste(rk, (0, 0), m)
    # Grass on the top.
    gd = ImageDraw.Draw(img, 'RGBA')
    gcol = (30, 52, 34) if night else (96, 150, 70)
    rr = random.Random(seed)
    edge = g["face"] + g["face_dx"]
    gd.polygon([(w * (edge - 0.005), top + s * 0.008), (w * (edge + 0.10), top - s * 0.017), (w + 10, top - s * 0.027), (w + 10, top + s * 0.04), (w * (edge + 0.015), top + s * 0.03)], fill=gcol)
    for _ in range(int(450 * w / 1080)):
        x = rr.uniform(w * edge, w)
        y = top + rr.uniform(-s * 0.02, s * 0.04)
        gd.line((x, y, x + rr.uniform(-6, 6), y - rr.uniform(8, 26)), fill=gcol, width=2)
    # Foam where the sea meets the rock.
    edge = np.array(m, np.float32) / 255
    near = np.array(m.filter(ImageFilter.MaxFilter(11)), np.float32) / 255 - edge
    near[: int(hy + h * g["base_left"] * 0.8)] = 0
    yy = np.arange(h)[:, None]
    foam = near * (yy > hy) * np.clip((sample(spectral_noise(256, seed + 21, 1.2), np.arange(w)[None, :] * 0.8, yy * 0.8) - 0.35) * 2.5, 0, 1)
    foam = np.array(Image.fromarray((foam * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(3)), np.float32) / 255
    arr = np.array(img, np.float32)
    arr += foam[..., None] * (np.array((90, 110, 130)) if night else np.array((200, 210, 215))) * 0.8
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
    # The lighthouse on top.
    # Standing on the ground where it is: the top rises a little towards the right.
    lx_ = w * g["light"]
    edge = g["face"] + g["face_dx"]
    ground = top - h * 0.012 * (lx_ / w - edge) / (1.01 - edge) + s * 0.006
    lamp = lighthouse(img, lx_, ground, g["light_scale"], night)
    if night:
        # The beam over the sea, toward the left, and the glow of the lamp.
        beam = Image.new('L', (w, h), 0)
        bd = ImageDraw.Draw(beam)
        bd.polygon([lamp, (-w * 0.1, lamp[1] - s * 0.074), (-w * 0.1, lamp[1] + s * 0.095)], fill=100)
        beam = beam.filter(ImageFilter.GaussianBlur(18))
        arr = np.array(img, np.float32)
        b = np.array(beam, np.float32)[..., None] / 255
        arr = arr + b * np.array((255, 230, 150), np.float32) * 0.55
        yy = np.arange(h, dtype=np.float32)[:, None]
        xx = np.arange(w, dtype=np.float32)[None, :]
        dd = np.sqrt((xx - lamp[0]) ** 2 + (yy - lamp[1]) ** 2)
        arr += (np.exp(-(dd / 26) ** 2) * 255 + np.exp(-(dd / 120) ** 2) * 90)[..., None] * np.array((1.0, 0.88, 0.55), np.float32)
        img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
    return img


def wheat_sky(w, h, hy, dusk, seed):
    t = np.clip(np.arange(h, dtype=np.float32) / hy, 0, 1)[:, None, None]
    if dusk:
        a = lerp((70, 92, 150), (255, 214, 120), t ** 1.6) * np.ones((1, w, 1), np.float32)
    else:
        a = lerp((80, 165, 235), (250, 244, 205), t ** 1.3) * np.ones((1, w, 1), np.float32)
    yy = np.arange(h, dtype=np.float32)[:, None]
    xx = np.arange(w, dtype=np.float32)[None, :]
    tex = spectral_noise(512, seed + 1, 2.6)
    c = sample(tex, xx / w * 70, yy / hy * 150)
    c = np.clip((c - 0.62) * 3, 0, 1) ** 1.4 * (yy < hy) * np.clip(1.2 - yy / hy, 0, 1)
    cloud = np.array((255, 226, 160) if dusk else (255, 255, 250), np.float32)
    a = a * (1 - c[..., None] * 0.7) + cloud * c[..., None] * 0.7
    return a


def field(img, hy, dusk, seed):
    w, h = img.size
    r = random.Random(seed)
    d = ImageDraw.Draw(img, 'RGBA')
    # Far hills, hazy.
    for k, (col, amp, base) in enumerate((((214, 200, 120) if not dusk else (150, 135, 80), 40, -30),
                                          ((200, 180, 70) if not dusk else (130, 112, 50), 50, 0))):
        pts = [(0, h)]
        for x in range(0, w + 20, 20):
            pts.append((x, hy + (base + amp * math.sin(x / w * math.pi * (1.1 + k * 0.7) + seed + k)) * h / 2280))
        pts.append((w, h))
        d.polygon(pts, fill=col)
    # The field: golden texture with rows going away.
    arr = np.array(img, np.float32)
    yy = np.arange(h, dtype=np.float32)[:, None]
    xx = np.arange(w, dtype=np.float32)[None, :]
    fy = hy + h * 0.035
    dd = np.clip((yy - fy) / (h - fy), 0, 1)
    z = 1 / (dd + 0.02)
    tex = spectral_noise(512, seed + 3, 1.6)
    u = (xx - w / 2) / w * z * 30
    v = z * 16
    n = sample(tex, u, v)
    rows = 0.5 + 0.5 * np.sin(u * 0.9) * np.clip(dd * 4, 0, 1)
    gold_hi = np.array((255, 232, 110) if not dusk else (238, 204, 92), np.float32)
    gold_lo = np.array((214, 172, 48) if not dusk else (140, 108, 36), np.float32)
    v = np.clip(0.55 * n + 0.25 * rows + 0.2 * (1 - dd), 0, 1)
    col = gold_lo * (1 - v[..., None]) + gold_hi * v[..., None]
    mask = (yy >= fy + 6 * np.sin(xx / 70))[..., None]
    arr = np.where(mask, col, arr)
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(img, 'RGBA')
    # Stalks with their ears, bigger towards the bottom.
    for _ in range(3400):
        t = r.random() ** 0.45
        y = fy + t * (h - fy) + 30
        x = r.random() * w
        L = 14 + 190 * t ** 1.6
        lean = r.uniform(-0.25, 0.35) + 0.15
        top = (x + lean * L, y - L)
        stem = (214, 182, 70, 230) if not dusk else (150, 118, 46, 230)
        d.line((x, y, top[0], top[1]), fill=stem, width=max(1, int(1 + 3 * t)))
        if t > 0.08:
            ew, eh = 3 + 11 * t, 12 + 60 * t
            ear = (255, 236, 130, 245) if not dusk else (246, 212, 108, 245)
            ang = math.atan2(top[1] - y, top[0] - x)
            # The ear: grains stacked along the stalk's direction.
            for k in range(6):
                f = k / 6
                cx = top[0] + math.cos(ang) * eh * f
                cy = top[1] + math.sin(ang) * eh * f
                d.ellipse((cx - ew / 2, cy - ew * 0.8, cx + ew / 2, cy + ew * 0.8), fill=ear)
            # Awns.
            for k in range(4):
                cx = top[0] + math.cos(ang) * eh * k / 4
                cy = top[1] + math.sin(ang) * eh * k / 4
                d.line((cx, cy, cx + math.cos(ang - 0.4) * eh * 0.6, cy + math.sin(ang - 0.4) * eh * 0.6), fill=ear[:3] + (160,), width=1)
    # A few poppies.
    for _ in range(38):
        t = r.random() ** 0.8
        y = fy + t * (h - fy) - 40 * t
        x = r.random() * w
        s = 2 + 11 * t
        c = (215, 45, 35, 230) if not dusk else (150, 35, 35, 230)
        for k in range(4):
            a = k * math.pi / 2 + 0.4
            px, py = x + s * 0.55 * math.cos(a), y + s * 0.45 * math.sin(a)
            d.ellipse((px - s * 0.62, py - s * 0.5, px + s * 0.62, py + s * 0.5), fill=c)
        d.ellipse((x - s * 0.18, y - s * 0.18, x + s * 0.18, y + s * 0.18), fill=(50, 30, 30, 220))
    return img


def wheat(w, h, dusk, seed=71, horizon=0.44, sun=True):
    hy = int(h * horizon)
    a = wheat_sky(w, h, hy, dusk, seed)
    if sun:
        if dusk:
            a = body(a, w * 0.68, hy * 0.86, w * 0.06, (255, 226, 130), 1.2, w * 0.26)
        else:
            a = body(a, w * 0.72, hy * 0.30, w * 0.042, (255, 250, 225), 0.8, w * 0.12)
    img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).convert('RGB')
    img = field(img, hy, dusk, seed)
    return img.filter(ImageFilter.GaussianBlur(0.5))


def mars_sky(w, h, hy, night, seed):
    t = np.clip(np.arange(h, dtype=np.float32) / hy, 0, 1)[:, None, None]
    if night:
        a = lerp((6, 4, 10), (46, 22, 24), t ** 2.2) * np.ones((1, w, 1), np.float32)
    else:
        a = lerp((196, 150, 110), (236, 196, 160), t ** 1.2) * np.ones((1, w, 1), np.float32)
    yy = np.arange(h, dtype=np.float32)[:, None]
    xx = np.arange(w, dtype=np.float32)[None, :]
    r = np.random.default_rng(seed)
    # Dust haze.
    tex = spectral_noise(512, seed + 2, 2.8)
    c = sample(tex, xx / w * 60, yy / hy * 120)
    c = np.clip((c - 0.5) * 2, 0, 1) * (yy < hy)
    a += c[..., None] * (np.array((20, 8, 8), np.float32) if night else np.array((24, 12, 4), np.float32))
    if night:
        stars = r.random((h, w)) > 0.9975
        mag = r.random((h, w)) ** 3
        s = (stars * mag * (yy < hy * 0.97)).astype(np.float32)
        s = np.array(Image.fromarray((s * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.7)), np.float32) / 255 * 3
        a += np.clip(s, 0, 1)[..., None] * 255
    return a


def terrain(img, hy, night, seed):
    w, h = img.size
    r = random.Random(seed)
    arr = np.array(img, np.float32)
    yy = np.arange(h, dtype=np.float32)[:, None]
    xx = np.arange(w, dtype=np.float32)[None, :]
    tex = spectral_noise(512, seed + 4, 2.1)
    # Ridges far to near: a mesa, hills, dunes.
    layers = (
        (hy - h * 0.06, 0.07, 1.0, ((150, 86, 62), (52, 24, 22))),
        (hy - h * 0.015, 0.05, 1.8, ((170, 92, 58), (66, 30, 24))),
        (hy + h * 0.03, 0.04, 2.6, ((186, 96, 54), (84, 36, 26))),
    )
    for k, (base, amp, freq, (day, nightc)) in enumerate(layers):
        prof = base + h * amp * ((sample(tex, xx[0] * freq * 0.035 + k * 97, np.full(w, 40.0 * k)) - 0.5) * 2.4 + 0.12 * (sample(tex, xx[0] * freq * 0.2, np.full(w, 13.0 * k + 5)) - 0.5))
        if k == 0:
            # A flat-topped mesa on the left.
            mesa = np.clip(1 - np.abs((xx[0] - w * 0.25) / (w * 0.18)) ** 6, 0, 1)
            prof = prof - mesa * h * 0.05
        mask = yy >= prof[None, :]
        col = np.array(nightc if night else day, np.float32)
        shade = sample(tex, xx * 1.3 + k * 50, yy * 1.3) * 0.35 + 0.8
        arr = np.where(mask[..., None], col * shade[..., None], arr)
    # The ground near: dunes with ripples and rocks.
    gy = hy + h * 0.07
    dd = np.clip((yy - gy) / (h - gy), 0, 1)
    z = 1 / (dd + 0.03)
    u = (xx - w / 2) / w * z * 18
    v = z * 10
    n = sample(tex, u, v)
    ripples = 0.5 + 0.5 * np.sin(v * 3.2 + n * 8)
    lit = np.clip(0.5 + (n - 0.5) * 1.6 + (ripples - 0.5) * 0.25 * (0.3 + dd), 0, 1)
    lo, hi = ((60, 24, 18), (120, 56, 40)) if night else ((150, 70, 40), (226, 132, 84))
    ground = lerp(lo, hi, lit[..., None])
    groundmask = (yy >= gy + h * 0.012 * np.sin(xx / 90))[..., None]
    arr = np.where(groundmask, ground, arr)
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(img, 'RGBA')
    for _ in range(48):
        t = r.random() ** 1.3
        y = gy + t * (h - gy)
        x = r.random() * w
        s = 3 + 48 * t ** 1.7 * r.uniform(0.6, 1.3)
        base = (70, 32, 26) if night else (120, 56, 38)
        pts = [(x + s * math.cos(a) * r.uniform(0.7, 1.1), y + s * 0.55 * math.sin(a) * r.uniform(0.7, 1.1) - (s * 0.3 if math.sin(a) < 0 else 0))
               for a in np.linspace(0, 2 * math.pi, 9)[:-1]]
        d.polygon(pts, fill=base + (255,))
        # Light from the left on top, shadow on the right.
        d.ellipse((x - s * 0.7, y - s * 0.6, x - s * 0.05, y - s * 0.15), fill=(255, 220, 190, 40 if night else 70))
        d.ellipse((x + s * 0.1, y - s * 0.05, x + s * 1.6, y + s * 0.35), fill=(20, 6, 6, 70))
    return img


def mars(w, h, night, seed=81, horizon=0.47):
    hy = int(h * horizon)
    a = mars_sky(w, h, hy, night, seed)
    if night:
        a = body(a, w * 0.72, hy * 0.26, w * 0.022, (225, 205, 190), 0.6, w * 0.06)   # Phobos
        a = body(a, w * 0.34, hy * 0.42, w * 0.010, (215, 200, 190), 0.4, w * 0.03)   # Deimos
        a = body(a, w * 0.18, hy * 0.16, w * 0.006, (120, 170, 255), 0.9, w * 0.02)   # the Earth
    else:
        # A small, pale sun with a bluish halo, as it looks from Mars.
        a = body(a, w * 0.70, hy * 0.32, w * 0.026, (250, 248, 240), 0.7, w * 0.09)
        yy = np.arange(h, dtype=np.float32)[:, None]
        xx = np.arange(w, dtype=np.float32)[None, :]
        dd = np.sqrt((xx - w * 0.70) ** 2 + (yy - hy * 0.32) ** 2)
        a += (np.exp(-(dd / (w * 0.14)) ** 2) * 40)[..., None] * np.array((-0.6, 0.1, 0.9), np.float32)
    img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).convert('RGB')
    img = terrain(img, hy, night, seed)
    return img.filter(ImageFilter.GaussianBlur(0.6))
