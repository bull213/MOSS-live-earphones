"""In-memory LRU cache for recently captured photos with sliding TTL."""

from __future__ import annotations

from collections import OrderedDict
from dataclasses import dataclass
import time
from uuid import UUID


@dataclass
class CachedPhoto:
    jpeg_bytes: bytes
    last_accessed: float


class PhotoCache:
    """
    In-memory bounded LRU cache for recent photos.
    Guards memory with maxsize (default 5 items) and sliding TTL (default 30 min).
    """

    def __init__(self, maxsize: int = 5, ttl_seconds: float = 1800.0) -> None:
        self.maxsize = maxsize
        self.ttl_seconds = ttl_seconds
        self._cache: OrderedDict[str, CachedPhoto] = OrderedDict()

    @staticmethod
    def _validate_id(photo_id: str) -> str:
        try:
            return str(UUID(photo_id))
        except (ValueError, TypeError, AttributeError):
            raise ValueError(f"Invalid photo_id: {photo_id!r} is not a valid UUID") from None

    def store_photo(self, photo_id: str, jpeg_bytes: bytes) -> None:
        valid_id = self._validate_id(photo_id)
        if not jpeg_bytes:
            raise ValueError("jpeg_bytes cannot be empty")

        self.cleanup_expired()

        if valid_id in self._cache:
            self._cache.move_to_end(valid_id)
            self._cache[valid_id] = CachedPhoto(jpeg_bytes=jpeg_bytes, last_accessed=time.time())
            return

        if len(self._cache) >= self.maxsize:
            # Pop least recently used (first item)
            self._cache.popitem(last=False)

        self._cache[valid_id] = CachedPhoto(jpeg_bytes=jpeg_bytes, last_accessed=time.time())

    def get_photo(self, photo_id: str) -> bytes | None:
        try:
            valid_id = self._validate_id(photo_id)
        except ValueError:
            return None

        entry = self._cache.get(valid_id)
        if entry is None:
            return None

        now = time.time()
        if now - entry.last_accessed > self.ttl_seconds:
            self._cache.pop(valid_id, None)
            return None

        # Update access time and move to end (most recently used)
        entry.last_accessed = now
        self._cache.move_to_end(valid_id)
        return entry.jpeg_bytes

    def delete_photo(self, photo_id: str) -> bool:
        try:
            valid_id = self._validate_id(photo_id)
        except ValueError:
            return False
        return self._cache.pop(valid_id, None) is not None

    def cleanup_expired(self) -> int:
        now = time.time()
        expired = [
            pid for pid, entry in self._cache.items()
            if now - entry.last_accessed > self.ttl_seconds
        ]
        for pid in expired:
            self._cache.pop(pid, None)
        return len(expired)

    def clear(self) -> None:
        self._cache.clear()

    def __len__(self) -> int:
        return len(self._cache)
