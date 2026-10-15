# Gridline on Android

How TickTock draws itself and how it talks. Every screen, component, dialog, sheet, notification
and empty state follows this page; the project site uses the same tokens. Later milestones add
components to the inventory below and change neither the inventory nor the rules without a reason
written here.

**The copy rule comes first: show, don't tell.** The UI never describes itself in prose. An empty or
gated state is the real layout in a ghost state (dimmed "—" values, the needle parked in gray) with
an icon for the cause. Labels, tabs, keys, pane titles and status segments use the fewest words that
name the thing or the state, with no colons and no explanations: "Mic busy", "Bluetooth", "Paused".
An explanation is one short line in a pane notice, and appears only when the player must act.

## 1. The system in one paragraph

Square corners everywhere. In the Gridline theme, System Gray `#C6C6C6` is structure: panes, the
tab row, the status bar, key faces. White `#FFFFFF` is content: readouts, tables, plots, text
fields, each inside an ink `#101010` frame with one unit of inset relief. Active Blue `#0000A8`
names things (title strips) and marks what is chosen (the selected key, the primary action). Depth
is a one-unit bevel, light on the top and left edges and dark on the bottom and right; pressed and
inset faces reverse it. There are no shadows, gradients, rounded corners, pills, ripples or emoji,
and the `:design` module has no API for any of them. IBM Plex Sans carries words; IBM Plex Mono with
tabular figures carries numbers and status facts.

Green, amber and cyan are reserved for meaning: green is in tune, amber is close or needs attention,
cyan is live (a tool making sound or listening). Red is off or refused. A colour never stands alone;
the digits or the icon beside it say the same thing (A11Y-03).

## 2. Themes and tokens

Four themes, chosen in Settings and stored in prefs (`theme`, device-bound):

| Theme | For | Character |
|---|---|---|
| Gridline | the reference | System Gray structure, white content, Active Blue chrome |
| Daylight | glare, direct sun | white surfaces, black ink, softer relief, white key faces |
| Dark | evening practice | charcoal surfaces, light ink, blue chrome one step deeper, a lighter blue for accent text and the selected key's outline |
| Pit | the orchestra pit | black everywhere, dim ink, the accent as a line and never as a fill; every text colour at least 4.5:1 on black |

`design/src/main/kotlin/ticktock/design/Tokens.kt` is generated from the token sheet in
`tools/gen-tokens/tokens/*.css`; `themes.css` holds one block of overrides per theme. A test checks
the generator reproduces the committed file. Use the generated values and nothing else:

| Name | Holds |
|---|---|
| `GridlineThemes` | The four `GridlinePalette`s, in picker order (`all`) |
| `GridlineColors` | The current palette, from `GridlineTheme`: base colours, semantic roles (`surface`, `field`, `textBody`, `positive`, `attention`, `danger`, `live`, …) and the theme roles below |
| `GridlineRelief` | The current palette's `reliefRaised`, `reliefPressed`, `reliefInset`, `frame`, `frameStrong` |
| `GridlineSpacing` | `sp1` (4 dp) to `sp8` (32 dp); strip, row and bar heights; `radius` = 0 |
| `GridlineMotion` | 120 ms and 160 ms durations and the one easing curve |
| `GridlineTypeScale` | Sizes in sp, line heights in em, caption tracking and weight, the `tnum` feature |
| `GridlineMetrics` (hand-written) | `line` = 1 dp, `touchTarget` = 48 dp |

Theme roles let a theme move the accent without component code: `accentText` (the accent as text),
`controlFace` (key faces), `titleBg`, `titleFg`, `titleRule` (title strips), `selectionBg`,
`selectionFg`, `selectionOutline` (the selected key and the primary action). Components read roles;
a component that names `blue` or `gray` directly is wrong in three themes out of four.

`GridlineType` binds the scale to the bundled families: `dense`, `ui`, `uiStrong`, `caption`,
`heading`, `title`, `measure` (mono, large readouts), `value` (mono, small facts), `titleBar`,
`statusBar`. Styles carry no colour; `GlText` takes the theme's ink unless told otherwise.

## 3. Component inventory

All components live in `ticktock.design.components`, take a `tag` for tests where they are
interactive or carry text that tests read, and are built from Compose Foundation only.

| Component | What it is | Rules |
|---|---|---|
| `GlText` | The one text primitive. | Every label goes through it with a `GridlineType` style; sizes are chosen in the scale and nowhere else. |
| `TitleBar` | The strip that names the app or a pane. | Title on the left; `state` on the right is one or two words ("Idle", "Listening", "Stopped"). Holds at most one `TitleBarAction`. Pit draws it black over a rule. |
| `TitleBarAction` | A framed action inside a strip ("Settings", "Back"). | 48 dp target; the strip grows to hold it. |
| `Pane` | A framed column with its own title strip. | The unit of layout: one per tool on a phone, two side by side in landscape. Its `state` is the pane's own fact. |
| `Field` | A content box with an ink frame and inset relief. | Where readouts, tables, plots and text live. Structure around it, content in it. |
| `Caption` | An upper-case tracked label above a group. | Plex Sans 600 at 13 sp with 0.04 em tracking. |
| `Button` | A raised square key with a label. | 48 dp minimum each way, the whole key the target. A key is a rule-bordered face with the bevel inside the border. `Default` uses `controlFace`; `Primary` uses the selection roles for the one action a pane is about. The bevel flips while pressed; nothing else moves. |
| `Segmented` | Mutually exclusive keys; also the tab bar. | Equal shares while every label fits with 8 dp to its border; otherwise an even number of keys wraps into two columns and an odd number into one, so the grid never has a hole. Nothing scrolls out of view and no label is cut. The chosen key takes the selection roles with pressed relief. `Segment.running` draws the live square, cyan inside an ink edge. |
| `SettingRow` | Label left, mono value right, light rule beneath. | 48 dp minimum; the whole row is the target. |
| `Notice` | A full-width strip with a thick left rule, one short line and, usually, the key that acts on it ("Try again"), 8 dp inside the strip. | Only when the player must act or would be surprised. `Attention`, `Danger`, `Plain`. Lives inside the pane it concerns. |
| `StatusBar` | The bar along the bottom of every screen. | One or two words per segment, a rule after each; wraps at large font sizes; never hides. |

Press feedback: `GridlineTheme` installs `PressIndication`, which draws the bevel flip in the
current theme's colours, as the default indication. Any clickable that draws its own bevel passes
`indication = null` and uses its pressed state instead, so there is exactly one bevel.

### Scheduled for later milestones

`Stepper` (value with − and + keys, 48 dp each), `Needle` and `CentsScale`, `Histogram` (101 bars with
the ±5 and ±12 bands as background rules), `LevelMeter`, `BeatRow` (numbered cells; the current beat
in the selection roles, the downbeat framed in ink), `Ghost` (the dimmed state of a readout, with a
cause icon), the 24 dp icon set, `Dialog` (a pane over a dimmed scrim with keys along its bottom
edge), `TextField` (a `Field` holding a `BasicTextField`). Each arrives in the component zoo and a
row in this table.

## 4. The frame

Top to bottom: the app title strip extending up behind the system status bar; the tab row
(`Segmented`); the work area, scrolling vertically; the status bar, padded past the navigation bar.
Settings replaces the strip and the tab row with a "Settings" strip and a "Back" action; the tab that
was open stays open underneath and the tools keep running. The system back gesture closes Settings,
otherwise returns to Tune. The status bar icons are light on every theme's title strip; the
navigation bar icons follow the theme.

Phones stack panes; landscape splits the same panes into two columns. Every control carries a
`testTag`.

## 5. The consistency gate

These run in `tools/verify.sh` on every change:

- **Component zoo** (`ComponentZooTest`): every component in its states, short and long labels, in
  all four themes, at font scales 1.0, 1.3, 1.5 and 2.0, at 360 and 600 dp. Each case asserts that no
  single-line label wraps, clips or is cut short, that every label keeps 8 dp to its key's edge, that
  every control is at least 48 dp each way and that every control has a label. Each case's golden is
  compared pixel by pixel (Roborazzi verify mode); the design owner signs off the goldens.
- **Contrast** (`ThemeContrastTest`): every text role against every surface it sits on, 4.5:1, and
  every meaningful mark and key edge, 3:1, in all four themes (the light rule between list rows is a
  divider and is exempt). A failing pair is fixed in the tokens.
- **Source guards** (`SourceGuardTest` in `:app`): no raw `clickable`, `selectable`, `toggleable` or
  `pointerInput`, no `dp` or `sp` literal, no raw `TextStyle` or font size in screen code; status
  strings of one or two words.
- **Screen goldens** (`FrameScreenshotTest`): every screen at the four font scales.

A label that cannot fit is a design defect: shorten the label or change the layout, never shrink the
type.

## 6. Words

Tone: a calm instrument. Present tense, the device named when there is one, as few words as name the
thing.

### Status words

The status bar and the pane `state` slot hold one or two words per fact. A state is shown only while
it is true.

| Thing | Words |
|---|---|
| Microphone | "Mic off", "Listening", "USB mic", "Mic busy", "Silence", "No mic" |
| Output | "Output closed", "Speaker", "Headphones", "Bluetooth", "Unplugged" |
| Pane state | "Idle", "Listening", "Stopped", "Running", "Paused" |

Two independent facts stay two segments. A closed output never implies a closed microphone, and a
lost route never implies a stopped tool.

### Labels and actions

Nouns name things: "Tune", "Drone", "Metronome", "Table", "Settings". Verbs name actions, with their
object only when it is not obvious: "Start", "Stop", "Reset note", "Clear session", "Save". A control
that changes a value shows the value ("A4 442").

### Notices

A notice is one short line that says what the player can do: "Reconnect or use the speaker",
"Storage full". It sits in the pane concerned. It never restates what the screen already shows.

### Empty and gated states

The real layout, dimmed: "—" for the note, "— c", "— Hz", the needle parked at centre in gray, and
an icon for the cause (mic slash, busy). A key that fixes it sits beside the icon when there is one
("Allow mic").

### What copy never does

No slogans, taglines or marketing. No exclamation marks. No emoji. No colons in labels. No prose
about the interface. No explanatory negatives: say what the thing does. No jokes. "Never" and
"always" appear only for a real constraint the app enforces.

### Confirmations

Destructive actions (clear a session, delete history, reset a note) confirm in a dialog whose title
names the object and whose keys are the verb ("Clear session") and "Keep". Nothing destructive
happens from a single tap.

## 7. Accessibility

Sizes in sp so the system font scale applies; every interactive control at least 48 × 48 dp
(A11Y-04); every label whole and visible at font scale 2.0, by wrapping keys into rows (A11Y-02);
every band colour accompanied by its signed digits (A11Y-03); contrast checked in all four themes;
tabs expose selection, buttons expose their role and enabled state.
