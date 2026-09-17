#!/usr/bin/env python3
"""
Screen lookups for scripts/generate-screenshots.sh.

  screenshot_ui.py find-text <ui.xml> <text> [n]
      Prints "x y", the centre of the n-th (default 0) element whose text
      or content description is exactly <text> in a `uiautomator dump`;
      exits 1 if there is none.

  screenshot_ui.py find-field <ui.xml> [n]
      Same for the n-th text input field.

  screenshot_ui.py has-text <ui.xml> <regex>
      Exits 0 if an element's text matches <regex>.

  screenshot_ui.py open-pois <screen.png>
      Prints "x y" per line for the grocery / bakery markers drawn fully
      opaque (open now; closed ones are faded) in the map area, nearest to
      the screen centre first.
"""

import re
import sys
import xml.etree.ElementTree as ET

# MapComposable.createPoiBitmap fill colours: grocery, bakery.
MARKER_COLOURS = {(46, 125, 50), (198, 124, 0)}
# A marker's fill is a ~14 px circle minus its letter.
MIN_MARKER_PIXELS = 150
# 1080x2400: stats card on top, controls on the right, bottom bar below.
MAP_AREA = (0, 240, 900, 2150)


def nodes(path):
    try:
        root = ET.parse(path)
    except ET.ParseError:
        return
    for node in root.iter("node"):
        bounds = [int(v) for v in re.findall(r"\d+", node.get("bounds", ""))]
        if len(bounds) == 4:
            yield node, bounds


def print_nth(matches, n):
    for i, (x1, y1, x2, y2) in enumerate(matches):
        if i == n:
            print((x1 + x2) // 2, (y1 + y2) // 2)
            return 0
    return 1


def find_text(path, text, n="0"):
    return print_nth(
        (b for node, b in nodes(path) if text in (node.get("text"), node.get("content-desc"))),
        int(n),
    )


def find_field(path, n="0"):
    return print_nth(
        (b for node, b in nodes(path) if node.get("class") == "android.widget.EditText"),
        int(n),
    )


def has_text(path, pattern):
    regex = re.compile(pattern)
    return 0 if any(regex.search(node.get("text", "")) for node, _ in nodes(path)) else 1


def open_pois(path):
    from PIL import Image

    img = Image.open(path).convert("RGB")
    width, height = img.size
    pixels = img.load()
    left, top, right, bottom = MAP_AREA
    seen = set()
    markers = []
    for y in range(top, min(bottom, height)):
        for x in range(left, min(right, width)):
            if (x, y) in seen or pixels[x, y] not in MARKER_COLOURS:
                continue
            # Flood-fill the marker's fill (8-neighbourhood, same colour set).
            stack = [(x, y)]
            seen.add((x, y))
            xs = ys = n = 0
            while stack:
                px, py = stack.pop()
                xs += px
                ys += py
                n += 1
                for dx in (-1, 0, 1):
                    for dy in (-1, 0, 1):
                        q = (px + dx, py + dy)
                        if q in seen or not (left <= q[0] < right and top <= q[1] < bottom):
                            continue
                        if pixels[q] in MARKER_COLOURS:
                            seen.add(q)
                            stack.append(q)
            if n >= MIN_MARKER_PIXELS:
                markers.append((xs // n, ys // n))
    cx, cy = (left + right) // 2, (top + bottom) // 2
    markers.sort(key=lambda m: (m[0] - cx) ** 2 + (m[1] - cy) ** 2)
    for mx, my in markers:
        print(mx, my)
    return 0


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    command, args = sys.argv[1], sys.argv[2:]
    if command == "find-text" and len(args) in (2, 3):
        return find_text(*args)
    if command == "find-field" and len(args) in (1, 2):
        return find_field(*args)
    if command == "has-text" and len(args) == 2:
        return has_text(*args)
    if command == "open-pois" and len(args) == 1:
        return open_pois(*args)
    sys.exit(__doc__)


if __name__ == "__main__":
    sys.exit(main())
