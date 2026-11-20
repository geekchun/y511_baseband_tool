"""把 AI 生成的原图处理成各密度 ic_launcher.png：去水印、居中、圆角"""
import glob
import os

from PIL import Image, ImageChops, ImageDraw

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC_DIR = os.path.join(ROOT, 'icon_src')
S = 512
PAD = 0.86  # 内容占画布比例


def latest_src():
    files = sorted(glob.glob(os.path.join(SRC_DIR, '*.png')), key=os.path.getmtime)
    return files[-1]


def clean(img):
    """抹掉顶部两条弧线（易看成虫子）与右下角生成水印"""
    w, h = img.size
    bg = img.getpixel((4, 4))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, w, int(h * 0.235)], fill=bg)
    d.rectangle([int(w * 0.82), int(h * 0.86), w, h], fill=bg)
    d.rectangle([0, int(h * 0.93), int(w * 0.18), h], fill=bg)
    return img


def content_box(img):
    w, h = img.size
    bg = img.getpixel((4, 4))
    solid = Image.new('RGBA', (w, h), bg)
    diff = ImageChops.difference(img.convert('RGBA'), solid).convert('L')
    return diff.point(lambda v: 255 if v > 26 else 0).getbbox()


def build(src):
    img = clean(Image.open(src).convert('RGBA'))
    box = content_box(img)
    part = img.crop(box)
    pw, ph = part.size
    canvas = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    bg = img.getpixel((4, 4))
    ImageDraw.Draw(canvas).rounded_rectangle([0, 0, S - 1, S - 1], radius=104, fill=bg)
    k = min(S * PAD / pw, S * PAD / ph)
    part = part.resize((max(1, int(pw * k)), max(1, int(ph * k))), Image.LANCZOS)
    canvas.alpha_composite(part, ((S - part.size[0]) // 2, (S - part.size[1]) // 2))
    mask = Image.new('L', (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=104, fill=255)
    out = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    out.paste(canvas, (0, 0), mask)
    return out


def main():
    src = latest_src()
    print('src', src)
    big = build(src)
    for folder, px in [('mipmap-mdpi', 48), ('mipmap-hdpi', 72), ('mipmap-xhdpi', 96),
                       ('mipmap-xxhdpi', 144), ('mipmap-xxxhdpi', 192)]:
        p = os.path.join(ROOT, 'res', folder)
        os.makedirs(p, exist_ok=True)
        f = os.path.join(p, 'ic_launcher.png')
        big.resize((px, px), Image.LANCZOS).save(f)
        print('icon', f, px)
    big.resize((256, 256), Image.LANCZOS).save(os.path.join(ROOT, 'icon_preview.png'))


if __name__ == '__main__':
    main()