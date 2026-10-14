# Gridline token generator

`tokens/*.css` is the Gridline token sheet: every colour, relief, spacing, motion and type value
the design system defines, as CSS custom properties. `gen_tokens.py` turns the sheet into
`design/src/main/kotlin/ticktock/design/Tokens.kt`, so the app and the design system share one
definition of each value.

```sh
python3 -I tools/gen-tokens/gen_tokens.py            # rewrite Tokens.kt
python3 -I tools/gen-tokens/gen_tokens.py --check    # exit 1 when Tokens.kt has drifted
```

`:design:testDebugUnitTest` runs the `--check` form (`TokensGeneratedTest`), so a hand edit to
`Tokens.kt`, or a CSS change without a rerun, fails the build.

## What the generator does with each file

| File | Kotlin | Notes |
|---|---|---|
| `colors.css` | `GridlineColors` | `gl-` prefixes drop; a plain `var()` alias stays an alias (`val surface = gray`). |
| `relief.css` | `GridlineRelief` | Each inset-shadow pair becomes a `Bevel(topLeft, bottomRight)`; each frame becomes a `Line`. |
| `spacing.css` | `GridlineSpacing`, `GridlineMotion` | Pixels become `dp`; durations become milliseconds; the easing becomes a `CubicBezierEasing`. |
| `fonts.css` | `GridlineFontStack` | The family names only. The TTFs are bundled; see `tools/fonts/`. |
| `typography.css` | `GridlineTypeScale` | Pixels become `sp`; unitless line heights become `em`; `tabular-nums` becomes the `tnum` feature tag. |

Skipped on purpose, with the reason written into the generated file: `--focus-ring` (the platform
draws keyboard focus on Android).

The sheet copies here hold token values only. The original `fonts.css` fetched the families from a
web font service; that line is removed because the app bundles the fonts and never fetches them.

Two phone measurements the sheet does not carry live by hand in `design/.../Metrics.kt`: the one-unit
line (1 dp) and the 48 dp touch target.
