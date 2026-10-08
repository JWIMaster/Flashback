# Headless checks

```bash
devtools/checks/run.sh
```

Runs the suites that check the parts of the editor which can be reasoned about without a game
running:

| Suite | Covers |
| --- | --- |
| `MigrationTest` | old projects gaining cameras without losing data |
| `EvaluationTest` | which camera a tick resolves to, and which tracks may animate it |
| `LoadCompatTest` | project JSON in the shape stock 0.43.6 wrote |
| `LayoutTest` | timeline rows, ordering, selection and panel geometry |
| `OrbitTest` | what "the orbit camera" means: fixed point, or the subject it follows |
| `ScrollBindingsTest` | the scroll gestures surviving the config, including a config that lost them |
| `ImGuiPairingCheck` | every ImGui begin has its end, and nothing returns out of one |

`stubs/` holds the stand-in for the game client that some of them need.

`../uitest/` renders the real timeline window offscreen, for looking at the interface.
