# Gridline on Android

How TickTock draws itself and how it talks. Every screen, component, dialog, sheet, notification
and empty state follows this page; the project site uses the same tokens. Later milestones add
components to the inventory below and copy to the messaging rules, and change neither without a
reason written here.

## 1. The system in one paragraph

Square corners everywhere. System Gray `#C6C6C6` is structure: panes, the tab row, the status
bar, button faces. White `#FFFFFF` is content: readouts, tables, plots, text fields, each inside
an ink `#101010` frame with one unit of inset relief. Active Blue `#0000A8` names things (title
strips) and marks what is chosen (the selected tab, the primary action). Depth is a one-unit bevel,
light on the top and left edges and dark on the bottom and right; pressed and inset faces reverse
it. There are no shadows, gradients, rounded corners, pills, ripples or emoji, and the `:design`
module has no API for any of them. IBM Plex Sans carries words; IBM Plex Mono with tabular figures
carries numbers and status facts.

Green `#18743A`, amber `#B87900` and cyan `#008EA1` are reserved for meaning: green is in tune,
amber is close or needs attention, cyan is live (a tool making sound or listening). Red `#B3261E` is
off or refused. A colour never stands alone; the words beside it say the same thing (A11Y-03).

## 2. Tokens

`design/src/main/kotlin/ticktock/design/Tokens.kt` is generated from the token sheet in
`tools/gen-tokens/tokens/*.css` and checked by a test. Use the generated objects and nothing else:

| Object | Holds |
|---|---|
| `GridlineColors` | Every colour, base and semantic (`active`, `surface`, `field`, `positive`, `attention`, `danger`, `live`, …) |
| `GridlineRelief` | `reliefRaised`, `reliefPressed`, `reliefInset` as colour pairs; `frame` and `frameStrong` |
| `GridlineSpacing` | `sp1` (4 dp) to `sp8` (32 dp); strip, row and bar heights; `radius` = 0 |
| `GridlineMotion` | 120 ms and 160 ms durations and the one easing curve |
| `GridlineTypeScale` | Sizes in sp, line heights in em, caption tracking and weight, the `tnum` feature |
| `GridlineFontStack` | The family names the sheet asks for |
| `GridlineMetrics` (hand-written) | `line` = 1 dp, `touchTarget` = 48 dp |

`GridlineType` binds the scale to the bundled families: `dense`, `ui`, `uiStrong`, `caption`,
`heading`, `title`, `measure` (mono, large readouts), `value` (mono, small facts), `titleBar`,
`statusBar`.

## 3. Component inventory

All components live in `ticktock.design.components`, take a `tag` for tests where they are
interactive or carry text that tests read, and are built from Compose Foundation only.

| Component | What it is | Rules |
|---|---|---|
| `GlText` | The one text primitive. | Every label goes through it with a `GridlineType` style; sizes are chosen in the scale and nowhere else. |
| `TitleBar` | The Active Blue strip that names the app or a pane. | Title on the left in Plex Sans 600; `state` on the right is a short fact ("Idle", "Listening", "Stopped"). Holds at most one `TitleBarAction`. |
| `TitleBarAction` | A framed white-on-blue action inside a strip ("Settings", "Back"). | 48 dp target; the strip grows to hold it. |
| `Pane` | A framed System Gray column with its own title strip. | The unit of layout: one per tool on a phone, two side by side in landscape. Its `state` is the pane's own fact. |
| `Field` | A white box with an ink frame and inset relief. | Where readouts, tables, plots and text live. Gray is structure; white is content. |
| `Caption` | An upper-case tracked label above a group. | Plex Sans 600 at 13 sp with 0.04 em tracking. |
| `Button` | A raised square key with a label. | 48 dp minimum each way. `Default` is a gray face with ink text; `Primary` is Active Blue with white text for the one action a pane is about. The bevel flips while pressed; nothing else moves. Disabled text is `textDisabled`. |
| `Segmented` | A row of mutually exclusive keys; also the tab bar. | Equal shares while every label fits; otherwise the row scrolls sideways as one row and no label is cut. The chosen key is Active Blue with pressed relief. `Segment.running` draws the live cyan square. Tab semantics for accessibility. |
| `SettingRow` | Label left, mono value right, light rule beneath. | 48 dp minimum; the whole row is the target. |
| `Notice` | A full-width strip with a thick left rule and one or two sentences. | `Attention` (amber on `yellowBg`), `Danger` (red on `redBg`), `Plain` (blue on `surface2`). First sentence states the fact; second says what the player can do. Lives inside the pane it concerns. |
| `StatusBar` | The bar along the bottom of every screen. | One mono fact per segment with a rule after it; wraps at large font sizes; never hides; never a symbol without words. |

Press feedback: `GridlineTheme` installs `PressIndication`, which draws the bevel flip, as the
default indication. Any clickable that draws its own bevel passes `indication = null` and uses its
pressed state instead, so there is exactly one bevel.

### Scheduled for later milestones

`Stepper` (value with − and + keys, 48 dp each), `Needle` and `CentsScale`, `Histogram` (101 bars with
the ±5 and ±12 bands as background rules), `LevelMeter`, `BeatRow` (numbered cells; the current beat
filled Active Blue, the downbeat framed in ink), `Dialog` (a pane over a dimmed gray scrim with
`Button`s along its bottom edge), `TextField` (a `Field` holding a `BasicTextField`). Each arrives
with a screenshot test and a row in this table.

## 4. The frame

Top to bottom: the app title strip extending up behind the system status bar; the tab row
(`Segmented`); the work area in System Gray, scrolling vertically; the status bar, padded past the
navigation bar. Settings replaces the strip and the tab row with a "Settings" strip and a "Back"
action; the tab that was open stays open underneath and the tools keep running. The system back
gesture closes Settings, otherwise returns to Tune.

Phones stack panes; landscape splits the same panes into two columns. Every control carries a
`testTag`. Screenshots are taken at 360 × 640 dp at font scales 1.0 and 2.0 (later also 1.3 and 1.5,
and 800 × 480), and every label must be displayed or reachable by scrolling at every scale.

## 5. Messaging

Tone: a calm technician reading the instruments. Plain sentences, present tense, the device named
when there is one. Copy describes what a thing is and what an action does.

### Status sentences

The status bar and the pane `state` slot state facts, one per segment. They are the whole truth of
the moment, and a stale state is a defect: a state is shown only while it is true.

| Thing | Sentences |
|---|---|
| Microphone | "Microphone closed", "Listening", "Listening on USB interface", "Microphone busy: another app holds it", "Microphone is delivering silence", "Microphone permission denied" |
| Output | "Output closed", "Output open on speaker", "Output open on headphones", "Output open on Bluetooth: adds delay", "Paused: headphones disconnected", "Paused: another app took audio" |
| Pane state | "Idle", "Listening", "Stopped", "Running", "Paused: headphones disconnected" |

Two independent facts stay two segments. The microphone and the output are separate; a closed
output never implies a closed microphone, and a lost route never implies a stopped tool.

### Labels and actions

Nouns name things: "Tune", "Drone", "Metronome", "Intonation table", "Settings". Verbs name actions,
with their object when it is not obvious: "Start metronome", "Stop", "Reset this note", "Clear
session", "Save session", "Open Settings", "Try again". A control that changes a value shows the
value beside its name ("A4 442 Hz").

### Errors

An error names the device and what was refused, then what the player can do, in that order.
"Output would not open on Bluetooth headphones at 48 000 or 44 100 Hz. Try the speaker or reconnect
the headphones." "Could not save the session. Storage is full; free some space and try again."
Errors never blame the player and never guess at a cause they cannot see; an unknown cause is said
to be unknown.

### Empty states

An empty pane says what will appear there and what makes it appear: "Every note you hold builds a
curve here." The placeholder panes in the current build add which milestone brings the content.

### Onboarding

Two steps on first run, each one pane. The microphone step says what the microphone is for and what
the app does with the audio (analyses it on the device and keeps readings, never recordings, unless
the player records a take), then asks. The instrument step asks "What do you play?" and shows the
picker with transpositions on every row. Either step can be left and returned to from Settings.

### What copy never does

No slogans, taglines or marketing. No exclamation marks. No emoji or decorative symbols. No
explanatory negatives: say what the thing does. No jokes in the interface. "Never" and "always"
appear only for a real constraint the app enforces.

### Confirmations

Destructive actions (clear a session, delete history, reset a note) confirm in a dialog whose title
names the object, whose sentence states the outcome, and whose keys are the verb ("Clear session")
and "Keep". Nothing destructive happens from a single tap.

## 6. Accessibility

Sizes in sp so the system font scale applies; every interactive control at least 48 × 48 dp
(A11Y-04); every label readable at font scale 2.0, by wrapping or by scrolling and never by
clipping (A11Y-02); every band colour accompanied by its signed digits (A11Y-03); tabs expose
selection, buttons expose their role and enabled state.
