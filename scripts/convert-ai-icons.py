"""Convert the pinned Lobe SVG assets to Android vectors without losing SVG arc flags.

Usage: python scripts/convert-ai-icons.py build/icon-assets/icons-static-svg-1.95.0.tgz
SVG permits adjacent arc flags, e.g. "0 01-4.45". Android/Compose PathParser reads
numbers instead, so every argument (especially the two flags) must be separated.
"""

import argparse
import re
import tarfile
from pathlib import Path
from xml.etree import ElementTree as ET
from xml.sax.saxutils import quoteattr

SOURCES = {
    "openai": "openai.svg",
    "claude": "claude.svg",
    "gemini": "gemini-color.svg",
    "deepseek": "deepseek.svg",
    "qwen": "qwen.svg",
    "zhipu": "zai.svg",
    "minimax": "minimax-color.svg",
    "ollama": "ollama.svg",
    "meta": "meta.svg",
    "kimi": "kimi.svg",
    "openrouter": "openrouter.svg",
    "firecrawl": "firecrawl.svg",
    "exa": "exa.svg",
    "tavily": "tavily.svg",
    "mistral": "mistral.svg",
    "grok": "grok.svg",
    "volcengine": "volcengine-color.svg",
    "siliconflow": "siliconcloud-color.svg",
}
ARITY = {"m": 2, "l": 2, "h": 1, "v": 1, "c": 6, "s": 4, "q": 4, "t": 2, "a": 7, "z": 0}
NUMBER = re.compile(r"[-+]?(?:\d*\.\d+|\d+\.?\d*)(?:[eE][-+]?\d+)?")
SVG = {"s": "http://www.w3.org/2000/svg"}


def normalize_path(value):
    output, cursor = [], 0

    def skip():
        nonlocal cursor
        while cursor < len(value) and value[cursor] in " \t\r\n,":
            cursor += 1

    while cursor < len(value):
        skip()
        if cursor == len(value):
            break
        command = value[cursor]
        if command.lower() not in ARITY:
            raise ValueError(f"Expected path command at {cursor}: {value[cursor:cursor + 20]}")
        output.append(command)
        cursor += 1
        arity = ARITY[command.lower()]
        if arity == 0:
            continue
        groups = 0
        while True:
            skip()
            if cursor == len(value) or value[cursor].isalpha():
                break
            for argument in range(arity):
                skip()
                if command.lower() == "a" and argument in (3, 4):
                    if cursor == len(value) or value[cursor] not in "01":
                        raise ValueError(f"Invalid arc flag at {cursor}")
                    output.append(value[cursor])
                    cursor += 1
                else:
                    match = NUMBER.match(value, cursor)
                    if match is None:
                        raise ValueError(f"Missing {command} argument at {cursor}")
                    output.append(match.group())
                    cursor = match.end()
            groups += 1
        if not groups:
            raise ValueError(f"Empty {command} command")
    return " ".join(output)


def color(value, opacity="1"):
    if value == "currentColor":
        value = "#000000"
    if value.startswith("#") and len(value) == 4:
        value = "#" + "".join(c * 2 for c in value[1:])
    if not value.startswith("#") or len(value) != 7:
        raise ValueError(f"Unsupported color: {value}")
    return "#" + f"{round(255 * float(opacity)):02X}" + value[1:].upper()


def fraction(value):
    return float(value[:-1]) / 100 if value.endswith("%") else float(value)


def convert(data, source):
    svg = ET.fromstring(data)
    if svg.get("viewBox") != "0 0 24 24":
        raise ValueError(f"Unexpected viewBox: {source}")
    allowed = {"svg", "title", "defs", "path", "linearGradient", "stop"}
    for node in svg.iter():
        if node.tag.split("}")[-1] not in allowed or node.get("transform"):
            raise ValueError(f"Unsupported SVG element: {node.tag}")
    gradients = {g.attrib["id"]: g for g in svg.findall(".//s:linearGradient", SVG)}
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             f'<!-- Lobe Icons 1.95.0: {source}; MIT, see licenses/lobe-icons-MIT.txt. -->',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"',
             '    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">']
    for path in svg.findall("s:path", SVG):
        fill = path.get("fill", svg.get("fill", "#000000"))
        rule = path.get("fill-rule", svg.get("fill-rule", "nonzero"))
        prefix = f'    <path android:pathData={quoteattr(normalize_path(path.attrib["d"]))} android:fillType="{"evenOdd" if rule == "evenodd" else "nonZero"}"'
        if not fill.startswith("url(#"):
            lines.append(prefix + f' android:fillColor="{color(fill, path.get("fill-opacity", "1"))}" />')
            continue
        gradient = gradients[fill[5:-1]]
        if gradient.get("gradientTransform"):
            raise ValueError("Gradient transforms require explicit conversion")
        if gradient.get("gradientUnits") == "userSpaceOnUse":
            points = [float(gradient.attrib[k]) for k in ["x1", "y1", "x2", "y2"]]
        else:
            if source != "minimax-color.svg":
                raise ValueError("Object bounds must be known for this gradient")
            # The MiniMax path bounds are x=0..24 and y=2..22.
            points = [24 * fraction(gradient.attrib["x1"]), 2 + 20 * fraction(gradient.attrib["y1"]),
                      24 * fraction(gradient.attrib["x2"]), 2 + 20 * fraction(gradient.attrib["y2"])]
        lines += [prefix + ">", '        <aapt:attr name="android:fillColor">',
                  '            <gradient android:type="linear" ' +
                  " ".join(f'android:{k}="{v:g}"' for k, v in zip(["startX", "startY", "endX", "endY"], points)) + ">"]
        for stop in gradient.findall("s:stop", SVG):
            lines.append(f'                <item android:offset="{fraction(stop.get("offset", "0")):g}" android:color="{color(stop.attrib["stop-color"], stop.get("stop-opacity", "1"))}" />')
        lines += ["            </gradient>", "        </aapt:attr>", "    </path>"]
    return "\n".join(lines + ["</vector>", ""])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    target = Path(__file__).resolve().parents[1] / "app/src/main/res/drawable"
    with tarfile.open(args.archive) as archive:
        # Validate every source before replacing any generated resource.
        vectors = {name: convert(archive.extractfile("package/icons/" + source).read(), source)
                   for name, source in SOURCES.items()}
    for name, vector in vectors.items():
        (target / f"ic_ai_{name}.xml").write_text(vector, encoding="utf-8")
    print(f"Converted {len(vectors)} icons with separated arc flags and original gradients.")


if __name__ == "__main__":
    main()
