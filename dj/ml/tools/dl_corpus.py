import os, subprocess, time, urllib.parse, urllib.request
import imageio_ffmpeg

FF = imageio_ffmpeg.get_ffmpeg_exe()
ROOT = os.path.dirname(os.path.abspath(__file__)) + "/corpus"
os.makedirs(ROOT + "/audio", exist_ok=True)
os.makedirs(ROOT + "/raw", exist_ok=True)

# (short name, item id, file path inside item, genre tag)
TRACKS = [
    ("club_diver", "Kevin-MacLeod_Hard-Electronic_2014_FullAlbum", "Hard Electronic/Kevin MacLeod - 03 - Club Diver.mp3", "techno/electronic"),
    ("pinball_spring_160", "Kevin-MacLeod_Hard-Electronic_2014_FullAlbum", "Hard Electronic/Kevin MacLeod - 14 - Pinball Spring 160.mp3", "fast electronic 160bpm"),
    ("disco_con_tutti", "Kevin-MacLeod_Disco-Ultralounge_2014_FullAlbum", "Disco Ultralounge/Kevin MacLeod - 08 - Disco con Tutti.mp3", "disco/house-ish 4/4"),
    ("george_street_shuffle", "Kevin-MacLeod_Disco-Ultralounge_2014_FullAlbum", "Disco Ultralounge/Kevin MacLeod - 11 - George Street Shuffle.mp3", "swing/shuffle feel"),
    ("funkorama", "Kevin-MacLeod_Funkorama_2014_FullAlbum", "Funkorama/Kevin MacLeod - 09 - Funkorama.mp3", "funk/hip-hop groove"),
    ("rock_on_chicago", "Kevin-MacLeod_HappyRock_2014_FullAlbum", "Happyrock/Kevin MacLeod - 15 - Rock on Chicago.mp3", "rock"),
    ("malt_shop_bop", "Kevin-MacLeod_HappyRock_2014_FullAlbum", "Happyrock/Kevin MacLeod - 08 - Malt Shop Bop.mp3", "50s rock'n'roll / shuffle"),
    ("waltz_tschaikovsky_op40", "Kevin-MacLeod_Famous-Classics_2008_FullAlbum", "Famous Classics/Kevin MacLeod - 15 - Waltz - Tschikovsky Op. 40.mp3", "3/4 waltz, classical, rubato"),
    ("canon_in_d", "Kevin-MacLeod_Famous-Classics_2008_FullAlbum", "Famous Classics/Kevin MacLeod - 02 - Canon in D Major.mp3", "classical strings, no drums (hard)"),
    ("tea_roots", "Kevin-MacLeod_Reggae-and-Ska_2009_FullAlbum", "Reggae & Ska/Kevin MacLeod - 08 - Tea Roots.mp3", "reggae (off-beat)"),
    ("street_party", "Kevin-MacLeod_Miami-Nights_2018_FullAlbum", "Miami Nights/Kevin MacLeod - 11 - Street Party.mp3", "synth-pop / retrowave"),
    ("laserpack", "Kevin-MacLeod_Laserpack_2018_FullAlbum", "Laserpack/Kevin MacLeod - 01 - Laserpack.mp3", "electronic / synthwave"),
    ("groove_grove", "Kevin-MacLeod_Rollin-at-5_2014_FullAlbum", "Rollin’ at 5/Kevin MacLeod - 08 - Groove Grove.mp3", "downtempo / hip-hop-ish groove"),
]

for name, ident, path, tag in TRACKS:
    raw = f"{ROOT}/raw/{name}.mp3"
    if not os.path.exists(raw):
        url = f"https://archive.org/download/{ident}/" + urllib.parse.quote(path)
        for attempt in range(8):
            try:
                urllib.request.urlretrieve(url, raw)
                break
            except Exception as e:
                print("retry", name, e)
                time.sleep(8)
    for sr in (22050, 44100):
        out = f"{ROOT}/audio/{name}_{sr}.wav"
        if not os.path.exists(out):
            subprocess.run([FF, "-y", "-loglevel", "error", "-i", raw, "-ac", "1", "-ar", str(sr), out], check=True)
    print("ok", name, os.path.getsize(raw))
