from pathlib import Path
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "sample_books" / "moread-sample.epub"


def main() -> None:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    chapters = [
        ("第一章 雨落书页", "江南的雨落在旧书页上，墨色缓慢晕开。"),
        ("第二章 灯下相逢", "夜色渐深，书店只剩一盏暖灯。"),
        ("第三章 风从字里来", "翻页时，一阵并不存在的风掠过指尖。"),
    ]
    with ZipFile(OUTPUT, "w") as epub:
        epub.writestr("mimetype", "application/epub+zip", compress_type=ZIP_STORED)
        epub.writestr(
            "META-INF/container.xml",
            '<?xml version="1.0"?><container version="1.0" '
            'xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
            '<rootfiles><rootfile full-path="OEBPS/content.opf" '
            'media-type="application/oebps-package+xml"/></rootfiles></container>',
            compress_type=ZIP_DEFLATED,
        )
        manifest = []
        spine = []
        nav = []
        for index, (title, body) in enumerate(chapters, start=1):
            href = f"text/chapter-{index:05d}.xhtml"
            manifest.append(
                f'<item id="chapter-{index}" href="{href}" media-type="application/xhtml+xml"/>'
            )
            spine.append(f'<itemref idref="chapter-{index}"/>')
            nav.append(f'<li><a href="{href}">{title}</a></li>')
            epub.writestr(
                f"OEBPS/{href}",
                '<?xml version="1.0" encoding="UTF-8"?>'
                '<html xmlns="http://www.w3.org/1999/xhtml" lang="zh-CN">'
                f"<head><title>{title}</title></head><body><h1>{title}</h1><p>{body}</p></body></html>",
                compress_type=ZIP_DEFLATED,
            )
        epub.writestr(
            "OEBPS/content.opf",
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">'
            '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
            '<dc:identifier id="id">urn:uuid:moread-sample</dc:identifier>'
            '<dc:title>墨知 EPUB 示例</dc:title><dc:creator>墨知项目组</dc:creator>'
            '<dc:language>zh-CN</dc:language>'
            '<meta property="dcterms:modified">2026-07-21T00:00:00Z</meta></metadata>'
            '<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>'
            + "".join(manifest)
            + "</manifest><spine>"
            + "".join(spine)
            + "</spine></package>",
            compress_type=ZIP_DEFLATED,
        )
        epub.writestr(
            "OEBPS/nav.xhtml",
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<html xmlns="http://www.w3.org/1999/xhtml" '
            'xmlns:epub="http://www.idpf.org/2007/ops" lang="zh-CN">'
            '<head><title>目录</title></head><body><nav epub:type="toc"><ol>'
            + "".join(nav)
            + "</ol></nav></body></html>",
            compress_type=ZIP_DEFLATED,
        )
    print(OUTPUT)


if __name__ == "__main__":
    main()
