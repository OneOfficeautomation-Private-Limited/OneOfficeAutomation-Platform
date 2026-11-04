# Play sound

Toolkit `ooa.sound`, version 1.2.0. Beep, play a WAV file, or play MP3, WMA, M4A and video files, e.g. to alert an attended user.

[All toolkits](README.md)

## Contents

- **Sound**: Beep, Play sound file, Play media file, Stop media

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

*Time limit 600 s; needs: desktop, filesystem.*

### Play media file

`ooa.sound/play_media` (action)

Play an MP3, WMA, M4A, WAV or other audio file, or a video (MP4, WMV, AVI...) in a window, with the media support of Windows; wait until it ends or continue while it plays.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Audio or video file, e.g. C:\alerts\done.mp3. |
| Show video | `show_video` | Yes/No | No | No | Main | Show the picture in a window (for video files); audio files then show an empty window. Esc closes it. |
| Wait | `wait` | Yes/No | No | Yes | More options | Wait until playing has finished; false continues while it plays (Stop media ends it). |
| Volume percent | `volume_percent` | Number | No | `100` | More options | Playback volume from 0 to 100 percent. Range: 0 to 100 |
| Repeat | `repeat` | Number | No | `1` | More options | How many times to play the file. Range: 1 to 100 |
| Maximum (seconds) | `max_seconds` | Number | No | `0` | More options | Stop after this many seconds; 0 plays to the end. Range: 0 to  |
| Full screen | `full_screen` | Yes/No | No | No | More options; tick Video window | Show the video full screen. Shown when: `{"show_video": ["true"]}` |
| Window width | `window_width` | Number | No | `800` | More options; tick Video window | Width of the video window in pixels. Range: 100 to ; Shown when: `{"show_video": ["true"]}` |
| Window height | `window_height` | Number | No | `480` | More options; tick Video window | Height of the video window in pixels. Range: 100 to ; Shown when: `{"show_video": ["true"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `duration_seconds` | Number | Length of the media in seconds (0 when not known, e.g. when not waiting). |

*Time limit 3600 s; needs: desktop, filesystem.*

### Stop media

`ooa.sound/stop_media` (action)

Stop media files started by Play media file without waiting.

No settings.

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `stopped` | Number | Number of players stopped. |

*Time limit 60 s; needs: desktop.*
