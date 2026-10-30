# Play sound

Toolkit `ooa.sound`, version 1.1.0. Beep or play a WAV file, e.g. to alert an attended user.

[All toolkits](README.md)

## Contents

- **Sound**: Beep, Play sound file

## Sound

### Beep

`ooa.sound/beep` (action)

Play the system beep, once or several times.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Times | `times` | Number | No | `1` | Main | How many beeps (1 to 20). Range: 1 to 100 |
| Interval (milliseconds) | `interval_ms` | Number | No | `300` | Main | Pause between beeps. Range: 0 to  |

*Time limit 60 s; needs: desktop.*

### Play sound file

`ooa.sound/play` (action)

Play a WAV, AIFF or AU sound file, waiting until it ends or continuing while it plays.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Sound file (WAV recommended). |
| Wait | `wait` | Yes/No | No | Yes | More options | Wait until the sound has finished; false continues while it plays. |
| Volume percent | `volume_percent` | Number | No | `100` | More options | Playback volume from 1 to 100 percent of the file's level. Range: 0 to 100 |
| Repeat | `repeat` | Number | No | `1` | More options | How many times to play the sound (1 to 20). Range: 1 to 100 |

*Time limit 600 s; needs: desktop.*
