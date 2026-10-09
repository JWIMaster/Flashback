# Headless checks

```bash
devtools/checks/run.sh
```

Runs the suites that check the parts of the editor which can be reasoned about without a game
running. The replay inventory suite also exercises an open → update → close → seek → restore
state sequence using the production slot conversion. It is a headless model, not a substitute
for recording and replaying in game.


| Suite | Covers |
| --- | --- |
| `MigrationTest` | old projects gaining cameras without losing data |
| `EvaluationTest` | which camera a tick resolves to, and which tracks may animate it |
| `CameraObjectTest` | independent camera position/rotation tracks, and a camera's static values |
| `LoadCompatTest` | project JSON in the shape stock 0.43.6 wrote |
| `LayoutTest` | timeline rows, ordering, selection and panel geometry |
| `OrbitTest` | what "the orbit camera" means: fixed point, or the subject it follows |
| `ScrollBindingsTest` | the scroll gestures surviving the config, including a config that lost them |
| `GuiLogTest` | reading container changes as "what moved where" |
| `ReplayInventorySlotsTest` | hotbar/menu/armour conversion and headless replay transitions |
| `ImGuiPairingCheck` | every ImGui begin has its end, and nothing returns out of one |

`stubs/` holds the stand-in for the game client that some of them need.

`../uitest/` renders the real timeline window offscreen, for looking at the interface.
