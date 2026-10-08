"""Harmonic compatibility of DJ transitions from stored analyses, with Tonal Interval Vectors.

Reads the analyses of an "Export analyses" zip (analysis/<id>.json with structureFrames: per 500 ms, 12 chroma values
(sum 1) then 5 MFCCs) and measures, for every ordered pair of tracks, the dissonance of the mixed chroma of a window
near the end of A and a window near the start of B, at every transposition of B (-6..+5 semitones).

TIV (Bernardes et al. 2016, TIV.lib, DAFx-20): T(k) = w(k) * DFT_k(chroma / sum), k = 1..6,
w = {3, 8, 11.5, 15, 14.5, 7.5}. A mix is the energy-weighted sum of TIVs; its dissonance is 1 - |T| / |w|.

Usage: python3 -I tiv_compat.py <export>/analysis
Prints the self-test (a track's end vs its own start must prefer no transposition), the share of pairs that are
already within 0.03 of their best transposition, and how much the choice of exit window moves the dissonance.
Not validated by listening: the 0.03 threshold is arbitrary.
"""
import glob
import json
import sys

import numpy as np

W = np.array([3, 8, 11.5, 15, 14.5, 7.5])
K = np.exp(-2j * np.pi * np.arange(1, 7)[:, None] * np.arange(12)[None, :] / 12)


def tiv(c):
    c = np.maximum(np.asarray(c, float), 0)
    s = c.sum()
    return W * (K @ (c / s)), s


def diss(t):
    return 1 - np.linalg.norm(t) / np.linalg.norm(W)


def mix_diss(a, b, shift=0):
    ta, ea = tiv(a)
    tb, eb = tiv(np.roll(b, shift))
    return diss((ta * ea + tb * eb) / (ea + eb))


def main(folder):
    tracks = {}
    for f in glob.glob(folder + "/*.json"):
        a = json.load(open(f))
        fr = a.get("structureFrames") or []
        if not fr:
            continue
        x = np.array(fr, float).reshape(-1, 17)[:, :12]
        if len(x) >= 160:
            tracks[a["videoId"]] = x
    ids = sorted(tracks)
    shifts = range(-6, 6)
    self_ok = sum(
        min(shifts, key=lambda p: mix_diss(tracks[i][int(.62 * len(tracks[i])):int(.95 * len(tracks[i]))].mean(0),
                                           tracks[i][int(.02 * len(tracks[i])):int(.35 * len(tracks[i]))].mean(0), p)) == 0
        for i in ids
    )
    print(f"tracks with structure frames: {len(ids)}; self-test (best shift 0): {self_ok}/{len(ids)}")
    fine = total = 0
    spread = []
    for i in ids:
        a = tracks[i]
        n = len(a)
        wins = [a[s:s + 32].mean(0) for s in np.linspace(n * 0.5, n * 0.92 - 32, 4).astype(int)]
        for j in ids:
            if i == j:
                continue
            b = tracks[j][4:36].mean(0)
            d = [mix_diss(w, b) for w in wins]
            spread.append(max(d) - min(d))
            per_shift = {p: mix_diss(wins[-1], b, p) for p in shifts}
            total += 1
            fine += per_shift[0] - min(per_shift.values()) < 0.03
    print(f"pairs {total}: plain mix within 0.03 of its best transposition: {100 * fine / total:.0f}%")
    print(f"four exit windows of the same pair: dissonance spread median {np.median(spread):.3f}")


if __name__ == "__main__":
    main(sys.argv[1])
