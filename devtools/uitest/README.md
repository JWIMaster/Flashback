# Offscreen UI harness

Runs the real editor window without Minecraft, so the interface can be looked at and clicked
without launching the game.

```bash
devtools/uitest/run.sh uitest.TimelineScenario three-cameras
```

Writes `devtools/uitest/out/<scenario>.png` and prints the scene's cuts and shots, so a driven
interaction can be checked rather than merely seen.

## How it works

- `Harness` creates an ImGui context, builds the same fonts and glyph coverage the real UI builds
  (the icon list is read from `ReplayUI.buildMaterialIconRanges`, so it cannot drift), and runs a
  frame loop: queue input events, `newFrame`, position the window, run the window, `render`.
- `SoftwareRenderer` rasterises ImGui's draw data into a PNG. ImGui hands a backend triangles, a
  font atlas and scissored draw commands, so implementing that contract in Java is all it takes.
  Two details matter: the binding returns one *shared* staging buffer, so each buffer must be copied
  out before the next accessor call, and its vertex data is little-endian.
- `stubs/` shadows `I18n`, `Flashback`, `ReplayServer`, `EditorStateManager`, `ReplayUI` and the
  raw-input helper - the parts that need a game client. They go first on the classpath.

## Scenarios

| Scenario | What it exercises |
| --- | --- |
| `three-cameras` | three cameras, two cuts, a spectate camera: the ordinary case |
| `hover-band` | hovering a shot in the cut lane (tooltip) |
| `drag-edge` | dragging a cut to retime it |
| `drag-edge-snapped` | the same drag with the magnet catching |
| `drag-body` | sliding a whole shot, neighbours stretching |
| `select-shot` | selecting a shot |
| `shot-menu` | right-clicking a shot: the camera list and shot actions |
| `cut-menu` | the blade button: adding a cut at the playhead |
| `collapsed` | collapse-all on the cameras heading |
| `rows` | a tall window showing the whole row list and its hierarchy |
| `delete-shot` | pressing Delete on a selected shot |
| `scroll-down`, `scroll-up` | the wheel scrolling the row list, asserting the offset |
| `pan-time` | shift and the wheel moving along the replay |
| `zoom-time` | the command modifier and the wheel zooming about the pointer |
| `crowded` | many cameras, to check the list and scrolling |
| `one-camera`, `empty` | the degenerate cases |

Coordinates live in `TimelineScenario` relative to the window, and were read off a first render -
they need updating if the layout changes.

## Not covered

Gradients, rounded corners and antialiasing are approximated by the software rasteriser, so do not
judge fine visual detail from these images. It is faithful for layout, text, colours, spacing,
hit-testing and interaction.
