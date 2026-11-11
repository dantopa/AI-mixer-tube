# Bagracho DJ stems server

Demucs is too heavy for the phone (it would cook it), so it runs here, on your own computer or a cloud machine. The app
sends each track's audio once (about 10 MB, by default only on Wi-Fi) and gets back a small map: where the voice, the
bass, the drums and the rest play, every 250 ms. The audio is not kept (only that map, cached by the audio's hash).

## Run it

With Docker:

    docker build -t bagracho-stems .
    docker run -d -p 8765:8765 -e STEMS_TOKEN=<a long random string> -v $PWD/cache:/app/cache bagracho-stems

Or with Python 3.11:

    pip install torch torchaudio && pip install -r requirements.txt
    STEMS_TOKEN=<a long random string> uvicorn server:app --host 0.0.0.0 --port 8765

Check it: `curl http://<machine>:8765/health`.

## In the app

Settings -> AI DJ -> Stems server: the address (`http://192.168.1.20:8765`, or your Tailscale address to reach it from
anywhere), the same token, and the switch. From then on the playing and the next track are sent once each; the DJ log
says `stems of <id>: ... sung ranges` when a map lands.

Speed: on a CPU expect about 0.5x real time (2 minutes for a 4-minute song; the next track is sent while the current one
plays). A GPU (CUDA) or an Apple Silicon Mac (MPS) is many times faster.

Keep the token secret, and do not open the port to the whole internet without it (use your home network or Tailscale).
