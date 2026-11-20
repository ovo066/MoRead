#!/usr/bin/env python3
"""生成 MOBI / AZW3 测试夹具：先拼一本自制的小样书（EPUB），再用 ebook-convert 转出几种文件。

样书全部内容都是为测试写的，不含任何受版权保护的文字。
用法：python3 make_fixtures.py <输出目录>     （需要本机装有 calibre 的 ebook-convert）
"""
import os
import struct
import subprocess
import sys
import zipfile
import zlib

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
epub = os.path.join(out, "sample.epub")


def png(width, height, rgb):
    """手工拼一张纯色 PNG：不依赖图像库，校验和由 zlib 现算。"""
    raw = b"".join(bytes([0]) + bytes(rgb) * width for _ in range(height))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    signature = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
    return (signature + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


PNG = png(16, 16, (170, 51, 51))
NEWLINE = chr(10)
XML_HEAD = '<?xml version="1.0" encoding="UTF-8"?>' + NEWLINE


def page(title, body):
    return (
        XML_HEAD +
        '<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh" lang="zh"><head>'
        f"<title>{title}</title>"
        '<link rel="stylesheet" type="text/css" href="style.css"/></head>'
        f"<body><h1>{title}</h1>{body}</body></html>"
    )


chapters = [
    ("第一章 起点",
     "<p>晨光落在码头的石阶上。</p>"
     '<p><img src="dot.png" alt="插图"/></p>'
     "<p>她把信折好，放进袖中。</p>"),
    ("第二章 中途",
     "<p>雨下了<b>一整夜</b>，江水漫过了堤岸。</p>"
     "<blockquote><p>逝者如斯夫，不舍昼夜。</p></blockquote>"),
    ("第三章 终点",
     "<p>船靠岸的时候，天已经亮了。</p>"
     '<p class="note">（全书完）</p>'),
]

manifest = "".join(
    f'<item id="c{i}" href="c{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(len(chapters))
)
spine = "".join(f'<itemref idref="c{i}"/>' for i in range(len(chapters)))
nav_items = "".join(f'<li><a href="c{i}.xhtml">{t}</a></li>' for i, (t, _) in enumerate(chapters))
ncx_points = "".join(
    f'<navPoint id="n{i}" playOrder="{i + 1}"><navLabel><text>{t}</text></navLabel><content src="c{i}.xhtml"/></navPoint>'
    for i, (t, _) in enumerate(chapters)
)

opf = (
    XML_HEAD +
    '<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">'
    '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
    '<dc:identifier id="id">urn:uuid:6f1d2c0a-0000-4000-8000-000000000001</dc:identifier>'
    "<dc:title>墨知样书</dc:title><dc:creator>测试作者</dc:creator><dc:language>zh</dc:language>"
    '<meta property="dcterms:modified">2000-01-01T00:00:00Z</meta></metadata>'
    '<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>'
    '<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>'
    '<item id="css" href="style.css" media-type="text/css"/>'
    '<item id="dot" href="dot.png" media-type="image/png"/>'
    f"{manifest}</manifest>"
    f'<spine toc="ncx">{spine}</spine></package>'
)
nav = (
    XML_HEAD +
    '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head>'
    f'<body><nav epub:type="toc"><ol>{nav_items}</ol></nav></body></html>'
)
ncx = (
    XML_HEAD +
    '<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head>'
    '<meta name="dtb:uid" content="urn:uuid:6f1d2c0a-0000-4000-8000-000000000001"/></head>'
    f"<docTitle><text>墨知样书</text></docTitle><navMap>{ncx_points}</navMap></ncx>"
)

with zipfile.ZipFile(epub, "w") as z:
    z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)
    z.writestr("META-INF/container.xml",
               '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
               '<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
    z.writestr("OEBPS/content.opf", opf)
    z.writestr("OEBPS/nav.xhtml", nav)
    z.writestr("OEBPS/toc.ncx", ncx)
    z.writestr("OEBPS/style.css", "body { line-height: 1.6; }" + NEWLINE + ".note { color: #aa3333; }" + NEWLINE)
    z.writestr("OEBPS/dot.png", PNG)
    for i, (title, body) in enumerate(chapters):
        z.writestr(f"OEBPS/c{i}.xhtml", page(title, body))

# 三个主夹具用默认设置（正文走 PalmDOC 压缩）；另出一份不压缩的旧式文件，两条解码路径都有真实数据把关。
jobs = [
    ("sample-old.mobi", ["--mobi-file-type", "old"]),
    ("sample-both.mobi", ["--mobi-file-type", "both"]),
    ("sample-kf8.azw3", []),
    ("sample-old-plain.mobi", ["--mobi-file-type", "old", "--dont-compress"]),
]
for name, extra in jobs:
    subprocess.run(["ebook-convert", epub, os.path.join(out, name)] + extra, check=True, stdout=subprocess.DEVNULL)
    print(name, os.path.getsize(os.path.join(out, name)))
