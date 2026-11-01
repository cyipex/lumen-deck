# Generates the Lumen Desk icon (dark glass, emerald glow, a grid of tiles with one switched on).
# Output: ui/icon.png (settings window), icon.ico (desktop icon). Run: python make-icon.py
import os
from PIL import Image, ImageDraw, ImageFilter

S = 1024
HERE = os.path.dirname(os.path.abspath(__file__))


def rounded_mask(size, box, radius):
    m = Image.new('L', size, 0)
    ImageDraw.Draw(m).rounded_rectangle(box, radius=radius, fill=255)
    return m


def glow(size, center, radius, color, strength):
    # Soft round glow: a filled circle blurred heavily, so its edges fade smoothly to zero.
    alpha = Image.new('L', size, 0)
    cx, cy = center
    k = radius // 2
    ImageDraw.Draw(alpha).ellipse((cx - k, cy - k, cx + k, cy + k), fill=int(255 * strength))
    alpha = alpha.filter(ImageFilter.GaussianBlur(radius / 2.6))
    layer = Image.new('RGBA', size, color + (0,))
    layer.putalpha(alpha)
    return layer


def build():
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    shape = rounded_mask((S, S), (0, 0, S - 1, S - 1), 232)

    bg = Image.new('RGBA', (S, S), (10, 10, 11, 255))
    bg = Image.alpha_composite(bg, glow((S, S), (250, 200), 640, (46, 140, 116), 0.85))
    bg = Image.alpha_composite(bg, glow((S, S), (920, 940), 460, (31, 94, 78), 0.40))
    img.paste(bg, (0, 0), shape)

    d = ImageDraw.Draw(img)
    # Subtle light rim like glass.
    d.rounded_rectangle((3, 3, S - 4, S - 4), radius=230, outline=(255, 255, 255, 26), width=5)

    pad, size, gap, r = 224, 256, 64, 72
    for row in range(2):
        for col in range(2):
            x = pad + col * (size + gap)
            y = pad + row * (size + gap)
            box = (x, y, x + size, y + size)
            if row == 0 and col == 0:
                # Switched-on tile: white with a glow.
                halo = Image.new('RGBA', (S, S), (0, 0, 0, 0))
                ImageDraw.Draw(halo).rounded_rectangle((x - 10, y - 10, x + size + 10, y + size + 10), radius=r + 10, fill=(255, 255, 255, 120))
                img.alpha_composite(halo.filter(ImageFilter.GaussianBlur(26)))
                d = ImageDraw.Draw(img)
                d.rounded_rectangle(box, radius=r, fill=(255, 255, 255, 255))
            else:
                glass = Image.new('RGBA', (S, S), (0, 0, 0, 0))
                ImageDraw.Draw(glass).rounded_rectangle(box, radius=r, fill=(255, 255, 255, 30), outline=(255, 255, 255, 115), width=8)
                img.alpha_composite(glass)
                d = ImageDraw.Draw(img)
    return img


def main():
    icon = build()
    icon.resize((512, 512), Image.LANCZOS).save(os.path.join(HERE, 'ui', 'icon.png'))
    icon.resize((256, 256), Image.LANCZOS).save(
        os.path.join(HERE, 'icon.ico'), sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    )
    print('ui/icon.png and icon.ico done')


if __name__ == '__main__':
    main()
