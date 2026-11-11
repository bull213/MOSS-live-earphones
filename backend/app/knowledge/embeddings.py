"""Replaceable local embeddings; loading never performs a network request."""

from __future__ import annotations

from pathlib import Path
import hashlib
import threading
from typing import Protocol


MODEL_ID = "sentence-transformers/all-MiniLM-L6-v2"
MODEL_REVISION = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
MODEL_VERSION = f"{MODEL_ID}@{MODEL_REVISION}"
MODEL_WEIGHTS_SHA256 = "53aa51172d142c89d9012cce15ae4d6cc0ca6895895114379cacb4fab128d9db"
DIMENSION = 384


class EmbeddingProvider(Protocol):
    @property
    def version(self) -> str: ...
    def encode(self, texts: list[str]) -> list[list[float]]: ...


class LocalMiniLM:
    def __init__(self, model_dir: Path) -> None:
        self.model_dir = model_dir
        self._model = None
        self._lock = threading.Lock()

    @property
    def version(self) -> str:
        return MODEL_VERSION

    def encode(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        with self._lock:
            if self._model is None:
                if not (self.model_dir / "modules.json").is_file() or not (self.model_dir / "model.safetensors").is_file():
                    raise RuntimeError("Local embedding model is missing. Run the explicit fetch-model command first.")
                verify_weights(self.model_dir)
                from sentence_transformers import SentenceTransformer
                self._model = SentenceTransformer(str(self.model_dir), device="cpu", local_files_only=True, trust_remote_code=False,
                                                  model_kwargs={"use_safetensors": True})
            vectors = self._model.encode(texts, normalize_embeddings=True, show_progress_bar=False, batch_size=32)
            return vectors.tolist()


def fetch_model(destination: Path) -> None:
    """Explicit setup action; no private book content is sent to the model host."""
    from huggingface_hub import snapshot_download
    snapshot_download(
        MODEL_ID, revision=MODEL_REVISION, token=False, local_dir=destination,
        allow_patterns=["config.json", "config_sentence_transformers.json", "modules.json", "sentence_bert_config.json",
                        "model.safetensors", "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json",
                        "vocab.txt", "1_Pooling/config.json"],
    )
    verify_weights(destination)


def verify_weights(destination: Path) -> None:
    try:
        digest = hashlib.sha256()
        with (destination / "model.safetensors").open("rb") as source:
            while block := source.read(1024 * 1024):
                digest.update(block)
    except OSError:
        raise RuntimeError("The local embedding model weights are missing.") from None
    if digest.hexdigest() != MODEL_WEIGHTS_SHA256:
        raise RuntimeError("The local embedding model weights failed verification.")
