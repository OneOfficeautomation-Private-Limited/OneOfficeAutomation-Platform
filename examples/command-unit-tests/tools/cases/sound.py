"""Play sound cases with a short, quiet test tone the case writes itself (a 0.3 s WAV). Makes sound."""
import base64
import io
import math
import struct
import wave

from helpers_desktop import add_converted, find, fix_samples


def tone_base64(seconds=0.3, rate=8000, freq=440, volume=0.05) -> str:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"".join(struct.pack("<h", int(32767 * volume * math.sin(2 * math.pi * freq * i / rate)))
                               for i in range(int(seconds * rate))))
    return base64.b64encode(buf.getvalue()).decode("ascii")


def add(ua):
    fix_samples(ua, ["ooa.sound"])
    for s in find("ooa.sound/", requires="desktop"):
        add_converted(ua, s)
    ua.var("ut_n", "Number", 0)
    ua.var("ut_ok", "Boolean", False)
    tone = tone_base64()

    def write_tone(f):
        f.act("ooa.file/from_base64", desc="Write the quiet test tone", base64=tone, path="ut_tone.wav", overwrite=True)

    with ua.case("ooa.sound/play", "the test tone twice, quietly",
                 "Play plays a WAV file, here twice at 20 % volume, and waits until it has finished.") as f:
        write_tone(f)
        f.act("ooa.sound/play", desc="Play the tone twice", path="ut_tone.wav", wait=True, volume_percent=20, repeat=2)

    with ua.case("ooa.sound/play_media", "the tone through the media player",
                 "Play media plays an audio or video file (no window for audio) and gives its length in seconds.") as f:
        write_tone(f)
        f.act("ooa.sound/play_media", desc="Play the tone", path="ut_tone.wav", wait=True, volume_percent=20, max_seconds=5,
              out={"duration_seconds": "ut_n"})
        ua.check("ge", "$ut_n$", 0, what="duration")

    with ua.case("ooa.sound/stop_media", "stop what is playing",
                 "Stop media stops a file Play media started without waiting, and says whether something was playing.") as f:
        write_tone(f)
        f.act("ooa.sound/play_media", desc="Start the tone, 20 times, without waiting", path="ut_tone.wav", wait=False,
              volume_percent=20, repeat=20)
        f.act("ooa.sound/stop_media", desc="Stop it", out={"stopped": "ut_ok"})
        ua.check_eq("ut_ok", True)
