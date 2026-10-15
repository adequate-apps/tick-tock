#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate design/src/main/kotlin/ticktock/design/Tokens.kt from the Gridline token CSS.

The token sheet in tokens/*.css is the one definition of every Gridline colour,
spacing, relief, motion and type value. This script turns it into Kotlin so the
app can never disagree with the design system about a value. The output is
deterministic: the same CSS always yields byte-identical Kotlin, and `--check`
fails when the committed file has drifted from the sheet.

Only the Python standard library is used, so the generator runs anywhere the
build runs.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOKENS_DIR = HERE / "tokens"
DEFAULT_OUTPUT = HERE.parent.parent / "design" / "src" / "main" / "kotlin" / "ticktock" / "design" / "Tokens.kt"

# Files are read in this order so the generated objects keep a stable order.
FILES = ["colors.css", "relief.css", "spacing.css", "fonts.css", "typography.css"]

# CSS names that have no Android counterpart. Each one is listed with the reason
# so a reviewer can see the omission was deliberate.
SKIPPED = {
    "focus-ring": "keyboard focus rings are drawn by the platform on Android",
    "ease": "emitted separately as a cubic bezier (see motion)",
    "caption-case": "emitted as a boolean flag (see typography)",
    "num": "emitted as a font feature tag (see typography)",
}

# Hyphenated CSS names that read badly when converted mechanically.
RENAMES = {
    "row-h": "rowHeight",
    "row-h-dense": "rowHeightDense",
    "strip-h": "stripHeight",
    "titlebar-h": "titleBarHeight",
    "statusbar-h": "statusBarHeight",
    "toolbar-h": "toolbarHeight",
    "dur-fast": "durationFastMillis",
    "dur": "durationMillis",
    "fs-dense": "fontSizeDense",
    "fs-ui": "fontSizeUi",
    "fs-caption": "fontSizeCaption",
    "fs-heading": "fontSizeHeading",
    "fs-title": "fontSizeTitle",
    "fs-measure": "fontSizeMeasure",
    "fs-small": "fontSizeSmall",
    "fs-stepper": "fontSizeStepper",
    "fs-cents": "fontSizeCents",
    "fs-note": "fontSizeNote",
    "lh-dense": "lineHeightDense",
    "lh-ui": "lineHeightUi",
    "font-ui": "ui",
    "font-mono": "mono",
}

COMMENT_RE = re.compile(r"/\*(.*?)\*/", re.DOTALL)
ROOT_RE = re.compile(r":root\s*\{(.*?)\}", re.DOTALL)
THEME_RE = re.compile(r"\.t-([a-z]+)\s*\{(.*?)\}", re.DOTALL)

# The themes, in the order the app offers them. Gridline is the :root sheet;
# the others are blocks in tokens/themes.css. The flag says whether the system
# bars need light icons.
THEMES = [("gridline", "Gridline", False), ("daylight", "Daylight", False), ("dark", "Dark", True), ("pit", "Pit", True)]
VAR_RE = re.compile(r"var\(--([a-z0-9-]+)\)")
HEX_RE = re.compile(r"^#([0-9A-Fa-f]{6})$")
PX_RE = re.compile(r"^(-?\d+(?:\.\d+)?)px$")
MS_RE = re.compile(r"^(\d+)ms$")
EM_RE = re.compile(r"^(-?\d+(?:\.\d+)?)em$")
NUMBER_RE = re.compile(r"^-?\d+(?:\.\d+)?$")
BEZIER_RE = re.compile(r"^cubic-bezier\(([^)]*)\)$")
# Relief is matched on the raw sheet text so the Kotlin keeps the sheet's own
# colour names (bevelLight) rather than whichever token first shared the hex.
RELIEF_RE = re.compile(
    r"^inset\s+1px\s+1px\s+0\s+var\(--([a-z0-9-]+)\),\s*inset\s+-1px\s+-1px\s+0\s+var\(--([a-z0-9-]+)\)$"
)
LINE_RE = re.compile(r"^(\d+)px\s+solid\s+var\(--([a-z0-9-]+)\)$")


class Token:
    """One `--name: value` declaration, with the comment group it sits under."""

    def __init__(self, name: str, raw: str, group: str | None):
        self.name = name
        self.raw = raw
        self.group = group
        self.resolved: str | None = None


def declarations(block: str, where: str) -> list[Token]:
    """Split one `{ ... }` body into tokens, keeping the group comments."""
    tokens: list[Token] = []
    group: str | None = None
    for piece in re.split(r";", block):
        piece = piece.strip()
        if not piece:
            continue
        leading = COMMENT_RE.match(piece)
        if leading:
            group = leading.group(1).strip()
            piece = piece[leading.end():].strip()
        piece = COMMENT_RE.sub("", piece).strip()
        if not piece:
            continue
        if not piece.startswith("--"):
            raise SystemExit(f"{where}: unexpected declaration {piece!r}")
        name, _, value = piece[2:].partition(":")
        tokens.append(Token(name.strip(), value.strip(), group))
    return tokens


def parse_themes(path: Path, colors: dict[str, Token]) -> dict[str, dict[str, str]]:
    """Read each `.t-name { ... }` block of the theme sheet into raw overrides."""
    text = COMMENT_RE.sub("", path.read_text(encoding="utf-8"))
    themes: dict[str, dict[str, str]] = {}
    for name, block in THEME_RE.findall(text):
        overrides = {t.name: t.raw for t in declarations(block, f"themes.css .t-{name}")}
        unknown = sorted(set(overrides) - set(colors))
        if unknown:
            raise SystemExit(f"themes.css .t-{name}: unknown colour tokens {unknown}")
        themes[name] = overrides
    expected = [key for key, _, _ in THEMES if key != "gridline"]
    if list(themes) != expected:
        raise SystemExit(f"themes.css: expected theme blocks {expected}, found {list(themes)}")
    return themes


def parse(path: Path) -> list[Token]:
    """Read the :root block of one CSS file into tokens, in source order.

    Comments inside the block that stand on their own line name a group
    (`/* base */`); comments trailing a declaration (`/* @kind other */`) are
    annotations in the source sheet and are dropped.
    """
    text = path.read_text(encoding="utf-8")
    tokens: list[Token] = []
    for block in ROOT_RE.findall(text):
        tokens.extend(declarations(block, path.name))
    return tokens


def resolve(tokens: dict[str, Token]) -> None:
    """Replace every var(--x) with the literal it points to, recursively."""

    def value_of(name: str, seen: tuple[str, ...]) -> str:
        if name in seen:
            raise SystemExit(f"token cycle: {' -> '.join(seen + (name,))}")
        token = tokens[name]
        if token.resolved is None:
            token.resolved = VAR_RE.sub(lambda m: value_of(m.group(1), seen + (name,)), token.raw)
        return token.resolved

    for name in tokens:
        value_of(name, ())


def kotlin_name(css_name: str) -> str:
    if css_name in RENAMES:
        return RENAMES[css_name]
    stripped = css_name.removeprefix("gl-")
    head, *rest = stripped.split("-")
    return head + "".join(part.capitalize() for part in rest)


def color_literal(hex_value: str) -> str:
    return f"Color(0xFF{hex_value.upper()})"


def number_literal(text: str, suffix: str) -> str:
    value = float(text)
    body = str(int(value)) if value.is_integer() else repr(value)
    return f"{body}{suffix}"


def resolve_theme(base: dict[str, Token], overrides: dict[str, str]) -> dict[str, str]:
    """Resolve every token for one theme: its overrides first, the base sheet for the rest."""
    themed = {name: Token(name, overrides.get(name, token.raw), token.group) for name, token in base.items()}
    resolve(themed)
    return {name: token.resolved or "" for name, token in themed.items()}


def emit_colors(tokens: list[Token], by_name: dict[str, Token], themes: dict[str, dict[str, str]]) -> list[str]:
    names = [t for t in tokens if t.name not in SKIPPED]
    lines = [
        "/**",
        " * One theme's colours, by role. Components read the current palette through",
        " * [GridlineColors]; green, amber and cyan carry meaning and appear nowhere else.",
        " */",
        "class GridlinePalette(",
        "    /** The name the theme picker shows. */",
        "    val name: String,",
        "    /** True when the surfaces are dark, so the system bars need light icons. */",
        "    val isDark: Boolean,",
    ]
    group: str | None = None
    for token in names:
        if token.group != group:
            group = token.group
            if group:
                lines.append(f"    // {group}")
        lines.append(f"    val {kotlin_name(token.name)}: Color,")
    lines.append(") {")
    lines.append("    /** The bevels and frames drawn in this palette's colours. */")
    lines.append("    val relief: GridlineReliefSet = GridlineReliefSet(this)")
    lines.append("}")
    lines.append("")
    lines.append("/** The four themes, in the order the picker offers them. */")
    lines.append("object GridlineThemes {")
    for key, label, dark in THEMES:
        resolved = resolve_theme(by_name, themes.get(key, {}))
        lines.append(f"    val {key} = GridlinePalette(")
        lines.append(f'        name = "{label}",')
        lines.append(f"        isDark = {str(dark).lower()},")
        for token in names:
            match = HEX_RE.match(resolved[token.name])
            if not match:
                raise SystemExit(f"colors.css: {token.name} is not a colour in {label}: {resolved[token.name]!r}")
            lines.append(f"        {kotlin_name(token.name)} = {color_literal(match.group(1))},")
        lines.append("    )")
        lines.append("")
    lines.append(f"    val all: List<GridlinePalette> = listOf({', '.join(k for k, _, _ in THEMES)})")
    lines.append("}")
    return lines


def emit_relief(tokens: list[Token], by_name: dict[str, Token]) -> list[str]:
    lines = [
        "/** Relief and frames, as pairs of colours from one palette; Relief.kt draws them one unit wide. */",
        "class GridlineReliefSet(colors: GridlinePalette) {",
    ]
    for token in tokens:
        name = kotlin_name(token.name)
        relief = RELIEF_RE.match(token.raw)
        line = LINE_RE.match(token.raw)
        if relief:
            top_left, bottom_right = (kotlin_name(c) for c in relief.groups())
            for c in relief.groups():
                if c not in by_name:
                    raise SystemExit(f"relief.css: {token.name} refers to unknown --{c}")
            if token.group:
                lines.append(f"    /** {token.group} */")
            lines.append(f"    val {name} = Bevel(topLeft = colors.{top_left}, bottomRight = colors.{bottom_right})")
        elif line:
            width, color = line.groups()
            if color not in by_name:
                raise SystemExit(f"relief.css: {token.name} refers to unknown --{color}")
            lines.append(f"    val {name} = Line(width = {width}.dp, color = colors.{kotlin_name(color)})")
        else:
            raise SystemExit(f"relief.css: cannot express {token.name}: {token.raw!r}")
    lines.append("}")
    return lines


def emit_spacing_and_motion(tokens: list[Token], by_name: dict[str, Token]) -> list[str]:
    spacing = ["/** Spacing and fixed heights in dp. The radius is zero: Gridline has square corners only. */", "object GridlineSpacing {"]
    motion = ["/** Motion: durations in milliseconds and the one easing curve. */", "object GridlineMotion {"]
    for token in tokens:
        resolved = by_name[token.name].resolved or ""
        name = kotlin_name(token.name)
        if token.name == "ease":
            bezier = BEZIER_RE.match(resolved)
            if not bezier:
                raise SystemExit(f"spacing.css: ease is not a cubic bezier: {resolved!r}")
            parts = [number_literal(p.strip(), "f") for p in bezier.group(1).split(",")]
            motion.append(f"    val ease = CubicBezierEasing({', '.join(parts)})")
            continue
        px = PX_RE.match(resolved)
        ms = MS_RE.match(resolved)
        if px:
            spacing.append(f"    val {name} = {number_literal(px.group(1), '.dp')}")
        elif ms:
            motion.append(f"    const val {name} = {ms.group(1)}")
        else:
            raise SystemExit(f"spacing.css: cannot express {token.name}: {resolved!r}")
    spacing.append("}")
    motion.append("}")
    return spacing + [""] + motion


def emit_fonts(tokens: list[Token], by_name: dict[str, Token]) -> list[str]:
    lines = [
        "/** Font families named by the sheet. The TTFs are bundled in res/font; Type.kt binds them. */",
        "object GridlineFontStack {",
    ]
    for token in tokens:
        resolved = by_name[token.name].resolved or ""
        families = [f.strip().strip("'\"") for f in resolved.split(",")]
        literal = ", ".join(f'"{f}"' for f in families)
        lines.append(f"    val {kotlin_name(token.name)} = listOf({literal})")
    lines.append("}")
    return lines


def emit_typography(tokens: list[Token], by_name: dict[str, Token]) -> list[str]:
    lines = [
        "/** The type scale. Sizes are sp so the system font scale applies; line heights are multiples of the size. */",
        "object GridlineTypeScale {",
    ]
    for token in tokens:
        resolved = by_name[token.name].resolved or ""
        name = kotlin_name(token.name)
        if token.name == "caption-case":
            lines.append(f"    const val captionUppercase = {str(resolved == 'uppercase').lower()}")
            continue
        if token.name == "num":
            if resolved != "tabular-nums":
                raise SystemExit(f"typography.css: unexpected numeral setting {resolved!r}")
            lines.append('    /** OpenType feature tag for tabular figures, so a changing readout never jitters. */')
            lines.append('    const val numeralFeature = "tnum"')
            continue
        px = PX_RE.match(resolved)
        em = EM_RE.match(resolved)
        if px:
            lines.append(f"    val {name} = {number_literal(px.group(1), '.sp')}")
        elif em:
            lines.append(f"    val {name} = {number_literal(em.group(1), '.em')}")
        elif NUMBER_RE.match(resolved):
            if token.name.startswith("lh-"):
                lines.append(f"    val {name} = {number_literal(resolved, '.em')}")
            else:
                lines.append(f"    val {name} = FontWeight({int(float(resolved))})")
        else:
            raise SystemExit(f"typography.css: cannot express {token.name}: {resolved!r}")
    lines.append("}")
    return lines


def generate() -> str:
    per_file = {name: parse(TOKENS_DIR / name) for name in FILES}
    by_name: dict[str, Token] = {}
    for tokens in per_file.values():
        for token in tokens:
            if token.name in by_name:
                raise SystemExit(f"duplicate token --{token.name}")
            by_name[token.name] = token
    themes = parse_themes(TOKENS_DIR / "themes.css", {t.name: t for t in per_file["colors.css"]})
    resolve(by_name)

    header = [
        "// SPDX-License-Identifier: GPL-3.0-or-later",
        "//",
        "// GENERATED by tools/gen-tokens/gen_tokens.py from tools/gen-tokens/tokens/*.css.",
        "// Edit the CSS and rerun the generator; a test fails when this file drifts.",
        "package ticktock.design",
        "",
        "import androidx.compose.animation.core.CubicBezierEasing",
        "import androidx.compose.ui.graphics.Color",
        "import androidx.compose.ui.text.font.FontWeight",
        "import androidx.compose.ui.unit.dp",
        "import androidx.compose.ui.unit.em",
        "import androidx.compose.ui.unit.sp",
        "",
    ]
    body = (
        emit_colors(per_file["colors.css"], by_name, themes)
        + [""]
        + emit_relief(per_file["relief.css"], by_name)
        + [""]
        + emit_spacing_and_motion(per_file["spacing.css"], by_name)
        + [""]
        + emit_fonts(per_file["fonts.css"], by_name)
        + [""]
        + emit_typography(per_file["typography.css"], by_name)
    )
    skipped = [f"// Skipped on purpose: --{k} ({v})." for k, v in SKIPPED.items() if k == "focus-ring"]
    return "\n".join(header + body + [""] + skipped) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="Kotlin file to write")
    parser.add_argument("--check", action="store_true", help="exit 1 when the output differs from the file on disk")
    args = parser.parse_args()
    text = generate()
    if args.check:
        current = args.output.read_text(encoding="utf-8") if args.output.exists() else ""
        if current != text:
            sys.stderr.write(f"{args.output} is out of date; rerun tools/gen-tokens/gen_tokens.py\n")
            return 1
        print(f"{args.output} matches the token sheet")
        return 0
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(text, encoding="utf-8")
    print(f"wrote {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
