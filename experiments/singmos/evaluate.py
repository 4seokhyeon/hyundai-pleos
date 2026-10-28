"""Local SingMOS probe; see README.md before installing or running."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from importlib.metadata import version
import json
import logging
import math
from pathlib import Path
import platform
import sys
import time

REPOSITORY = "South-Twilight/SingMOS:v1.1.2"
SAMPLE_RATE = 16000
LOG = logging.getLogger("SHSH_SCORE")


def load_predictor(model: str, threads: int = 4):
    """Load once for a server, or per invocation for the original CLI."""
    import torch

    if model not in ("singmos_pro", "singmos_v1") or threads < 1:
        raise ValueError("Invalid model or threads")
    torch.set_num_threads(threads)
    torch.manual_seed(0)
    LOG.info("Loading model; repository=%s, model=%s, device=cpu. First run downloads code and weights.", REPOSITORY, model)
    started = time.perf_counter()
    predictor = torch.hub.load(REPOSITORY, model, trust_repo=True)
    predictor = predictor.to("cpu").eval()
    seconds = time.perf_counter() - started
    LOG.info("Model loaded; seconds=%.2f", seconds)
    return predictor, seconds


def evaluate(args: argparse.Namespace, predictor=None) -> dict:
    # Import only when evaluating; --help needs no ML packages.
    import librosa
    import numpy as np
    import soundfile as sf
    import torch

    source = args.audio.expanduser().resolve()
    if not source.is_file() or source.suffix.lower() != ".wav":
        raise ValueError("Input must be an existing WAV file; convert other formats first.")
    info = sf.info(str(source))
    duration = info.frames / info.samplerate
    if info.format not in {"WAV", "WAVEX", "RF64"}:
        raise ValueError("File contents must be WAV audio.")
    if not 3 <= duration <= 30:
        raise ValueError("First probe accepts 3–30 seconds; export a short clip without silently truncating it.")
    total_started = time.perf_counter()
    LOG.info("Input accepted; duration=%.2fs, sample_rate=%d, channels=%d", duration, info.samplerate, info.channels)
    audio, original_sr = sf.read(str(source), dtype="float32", always_2d=True)
    wave = audio.mean(axis=1)
    if not np.isfinite(wave).all():
        raise ValueError("Audio contains non-finite samples.")
    rms = float(np.sqrt(np.mean(wave.astype(np.float64) ** 2)))
    peak = float(np.max(np.abs(wave)))
    if peak <= 1e-7:
        raise ValueError("Silent input: no score generated. Record a vocal clip.")
    if rms < 0.001:
        LOG.warning("Low input level; quality score may reflect recording conditions.")
    if original_sr != SAMPLE_RATE:
        wave = librosa.resample(wave, orig_sr=original_sr, target_sr=SAMPLE_RATE)
    wave = np.ascontiguousarray(wave, dtype=np.float32)
    if predictor is None:
        predictor, load_seconds = load_predictor(args.model, args.threads)
    else:
        load_seconds = 0.0
        LOG.info("Using preloaded model; model=%s, device=cpu", args.model)
    samples = torch.from_numpy(wave).unsqueeze(0)
    lengths = torch.tensor([samples.shape[1]], dtype=torch.long)
    LOG.info("Inference started")
    inference_started = time.perf_counter()
    with torch.inference_mode():
        raw_score = float(predictor(samples, lengths).detach().cpu().item())
    inference_seconds = time.perf_counter() - inference_started
    if not math.isfinite(raw_score):
        raise ValueError("Model returned a non-finite score; no success result saved.")
    LOG.info("Inference finished; raw_mos=%.4f, seconds=%.2f", raw_score, inference_seconds)
    return {
        "status": "succeeded",
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "repository": REPOSITORY,
        "model": args.model,
        "device": "cpu",
        "score_kind": "singing_naturalness_mos_prediction",
        "raw_mos": raw_score,
        "display_score_100": None,
        "audio": {"duration_seconds": duration, "original_sample_rate": original_sr,
                  "original_channels": info.channels, "model_sample_rate": SAMPLE_RATE,
                  "rms": rms, "peak": peak},
        "timing": {"model_load_seconds": load_seconds, "inference_seconds": inference_seconds,
                   "total_seconds": time.perf_counter() - total_started},
        "environment": {"python": platform.python_version(),
                        **{name: version(name) for name in ("torch", "torchaudio", "librosa", "s3prl", "soundfile")}},
        "limitations": ["Not pitch or rhythm accuracy against an original song.",
                        "Korean singing and mixed backing-track suitability not verified."],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate a 3–30 second WAV clip with official SingMOS.")
    parser.add_argument("audio", type=Path)
    parser.add_argument("--model", choices=("singmos_pro", "singmos_v1"), default="singmos_pro")
    parser.add_argument("--output", type=Path, help="New JSON file; existing files are never overwritten.")
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()
    if args.threads < 1:
        parser.error("--threads must be at least 1")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
    output = args.output or Path(__file__).parent / "results" / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + ".json")
    if output.exists():
        LOG.error("Output already exists; choose another output path.")
        return 1
    try:
        result = evaluate(args)
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("x", encoding="utf-8") as handle:
            json.dump(result, handle, indent=2, ensure_ascii=False, allow_nan=False)
            handle.write("\n")
        LOG.info("Result JSON saved; raw_mos=%.4f. No audio uploaded.", result["raw_mos"])
        print(json.dumps(result, indent=2, ensure_ascii=False, allow_nan=False))
        return 0
    except KeyboardInterrupt:
        LOG.warning("Evaluation canceled; no score fabricated.")
        return 130
    except Exception:
        LOG.exception("Evaluation failed; no success score generated. See README troubleshooting.")
        return 1


if __name__ == "__main__":
    sys.exit(main())
