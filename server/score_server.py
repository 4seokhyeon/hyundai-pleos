"""Local SingMOS scoring API. No YouTube proxy; no automatic client upload."""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import logging
from pathlib import Path
import socket
import sys
from tempfile import TemporaryDirectory
from threading import BoundedSemaphore, Lock
import time
from types import SimpleNamespace
from uuid import uuid4
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

# Reuse the already verified local evaluation implementation.
EXPERIMENT = Path(__file__).resolve().parents[1] / "experiments" / "singmos"
sys.path.insert(0, str(EXPERIMENT))
from evaluate import REPOSITORY, evaluate, load_predictor

LOG = logging.getLogger("SHSH_SCORE")
MAX_UPLOAD_BYTES = 6 * 1024 * 1024
RESULT_TTL_SECONDS = 15 * 60
MAX_JOBS = 100


class InvalidAudio(Exception):
    pass


class JobStore:
    def __init__(self, directory: Path, predictor, model: str, threads: int):
        self.directory, self.predictor = directory, predictor
        self.model, self.threads = model, threads
        self.jobs: dict[str, dict] = {}
        self.lock = Lock()
        self.slots = BoundedSemaphore(3)  # At most three uploads/queued/running jobs combined.
        self.worker = ThreadPoolExecutor(max_workers=1, thread_name_prefix="singmos")

    def prune(self):
        now = time.monotonic()
        with self.lock:
            expired = [key for key, job in self.jobs.items()
                       if job["status"] in ("succeeded", "failed")
                       and now - job["updated"] > RESULT_TTL_SECONDS]
            for key in expired:
                del self.jobs[key]

    def enqueue(self, path: Path, input_kind: str) -> str | None:
        self.prune()
        job_id = uuid4().hex
        with self.lock:
            if len(self.jobs) >= MAX_JOBS:
                return None
            self.jobs[job_id] = {"status": "queued", "updated": time.monotonic(),
                                 "input_kind": input_kind, "result": None, "error": None}
        try:
            self.worker.submit(self.process, job_id, path)
        except RuntimeError:
            with self.lock:
                self.jobs.pop(job_id, None)
            return None
        return job_id

    def process(self, job_id: str, path: Path):
        try:
            with self.lock:
                job = self.jobs.get(job_id)
                if job is None:  # Deleted while queued.
                    return
                job["status"] = "processing"
                input_kind = job["input_kind"]
            LOG.info("Job processing; id=%s, input_kind=%s", job_id, input_kind)
            result = evaluate(SimpleNamespace(audio=path, model=self.model, threads=self.threads), predictor=self.predictor)
            result["input_kind"] = input_kind
            result["limitations"].append("Backing audio and noise can affect this quality estimate; not a singer-only grade.")
            with self.lock:
                if job_id in self.jobs:
                    self.jobs[job_id].update(status="succeeded", result=result, updated=time.monotonic())
            LOG.info("Job succeeded; id=%s, raw_mos=%.4f", job_id, result["raw_mos"])
        except Exception as error:
            reason = "audio_rejected" if isinstance(error, ValueError) else "inference_failed"
            with self.lock:
                if job_id in self.jobs:
                    self.jobs[job_id].update(status="failed", error=reason, updated=time.monotonic())
            LOG.error("Job failed; id=%s, reason=%s, type=%s", job_id, reason, type(error).__name__)
        finally:
            path.unlink(missing_ok=True)
            self.slots.release()
            LOG.info("Upload file removed; id=%s", job_id)

    def snapshot(self, job_id: str):
        self.prune()
        with self.lock:
            job = self.jobs.get(job_id)
            if job is None:
                return None
            return {"job_id": job_id, "status": job["status"], "input_kind": job["input_kind"],
                    "result": job["result"], "error": job["error"]}

    def delete(self, job_id: str):
        # Drop visible metadata/result. An active inference finishes, discards its result,
        # and removes its temporary file in finally. Python cannot interrupt it safely.
        with self.lock:
            return self.jobs.pop(job_id, None) is not None


class ScoreServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, store: JobStore, model_load_seconds: float):
        self.store, self.model_load_seconds = store, model_load_seconds
        super().__init__(address, Handler)


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(15)

    def log_message(self, *_):
        pass

    def reply(self, code: int, payload: dict):
        body = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def job_id(self):
        parts = urlsplit(self.path).path.strip("/").split("/")
        if len(parts) == 2 and parts[0] == "scores" and len(parts[1]) == 32:
            if all(char in "0123456789abcdef" for char in parts[1]):
                return parts[1]
        return None

    def do_GET(self):
        store = self.server.store
        store.prune()
        if urlsplit(self.path).path == "/health":
            self.reply(200, {"status": "ready", "model": store.model, "repository": REPOSITORY,
                             "model_load_seconds": self.server.model_load_seconds,
                             "limits": {"max_upload_bytes": MAX_UPLOAD_BYTES, "min_seconds": 3, "max_seconds": 30},
                             "score_kind": "singing_naturalness_mos_prediction"})
            return
        job_id = self.job_id()
        snapshot = store.snapshot(job_id) if job_id else None
        self.reply(200, snapshot) if snapshot else self.reply(404, {"error": "job_not_found_or_expired"})

    def do_POST(self):
        if urlsplit(self.path).path != "/scores":
            self.reply(404, {"error": "not_found"})
            return
        if self.headers.get("Transfer-Encoding"):
            self.reply(400, {"error": "content_length_required"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if not 44 < length <= MAX_UPLOAD_BYTES:
            self.reply(413, {"error": "invalid_upload_size"})
            return
        if self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower() != "audio/wav":
            self.reply(415, {"error": "use_audio_wav_raw_body"})
            return
        input_kind = self.headers.get("X-Input-Kind", "unknown")
        if input_kind not in ("vocal_only", "mixed", "unknown"):
            self.reply(400, {"error": "invalid_input_kind"})
            return
        store = self.server.store
        if not store.slots.acquire(blocking=False):
            self.reply(503, {"error": "queue_full_retry_later"})
            return
        path = store.directory / (uuid4().hex + ".wav")
        submitted = False
        try:
            remaining = length
            with path.open("xb") as output:
                while remaining:
                    chunk = self.rfile.read(min(65536, remaining))
                    if not chunk:
                        raise InvalidAudio("incomplete_upload")
                    output.write(chunk)
                    remaining -= len(chunk)
            import soundfile as sf
            try:
                info = sf.info(str(path))
            except (RuntimeError, ValueError):
                raise InvalidAudio("invalid_wav") from None
            if (info.format not in {"WAV", "WAVEX", "RF64"}
                    or not 3 <= info.duration <= 30
                    or info.channels not in (1, 2)
                    or not 8000 <= info.samplerate <= 96000):
                raise InvalidAudio("wav_limits_exceeded")
            job_id = store.enqueue(path, input_kind)
            if job_id is None:
                self.reply(503, {"error": "job_capacity_reached"})
                return
            submitted = True
            LOG.info("Upload accepted; id=%s, bytes=%d, input_kind=%s", job_id, length, input_kind)
            self.reply(202, {"job_id": job_id, "status": "accepted", "status_url": "/scores/" + job_id})
        except InvalidAudio as error:
            self.reply(422, {"error": str(error)})
        except (TimeoutError, socket.timeout):
            self.reply(408, {"error": "upload_timeout"})
        except OSError:
            self.reply(500, {"error": "upload_io_error"})
        finally:
            if not submitted:
                path.unlink(missing_ok=True)
                store.slots.release()

    def do_DELETE(self):
        job_id = self.job_id()
        if job_id and self.server.store.delete(job_id):
            self.reply(200, {"status": "deleted", "note": "Active inference cleanup completes when that work exits."})
        else:
            self.reply(404, {"error": "job_not_found_or_expired"})


def main():
    parser = argparse.ArgumentParser(description="Local SingMOS WAV scoring API")
    parser.add_argument("--port", type=int, default=8091)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--model", choices=("singmos_pro", "singmos_v1"), default="singmos_pro")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or args.threads < 1:
        parser.error("Invalid port or threads")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
    predictor, load_seconds = load_predictor(args.model, args.threads)
    upload_root = Path(__file__).resolve().parent / "uploads"
    upload_root.mkdir(mode=0o700, exist_ok=True)
    with TemporaryDirectory(prefix="score-", dir=upload_root) as directory:
        store = JobStore(Path(directory), predictor, args.model, args.threads)
        try:
            with ScoreServer(("127.0.0.1", args.port), store, load_seconds) as server:
                LOG.info("Scoring API ready; host=127.0.0.1, port=%d, model=%s", args.port, args.model)
                try:
                    server.serve_forever(poll_interval=0.5)
                except KeyboardInterrupt:
                    LOG.info("Shutdown requested; waiting for accepted work")
        finally:
            store.worker.shutdown(wait=True)


if __name__ == "__main__":
    main()
