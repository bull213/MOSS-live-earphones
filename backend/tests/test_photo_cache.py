import time
import pytest
from app.photo_cache import PhotoCache


def test_photo_cache_store_and_get():
    cache = PhotoCache(maxsize=3, ttl_seconds=10.0)
    photo_id = "550e8400-e29b-41d4-a716-446655440000"
    data = b"\xff\xd8\xff\xe0testjpeg"

    cache.store_photo(photo_id, data)
    assert len(cache) == 1
    retrieved = cache.get_photo(photo_id)
    assert retrieved == data


def test_photo_cache_invalid_uuid():
    cache = PhotoCache()
    with pytest.raises(ValueError, match="is not a valid UUID"):
        cache.store_photo("../etc/passwd", b"fake")

    assert cache.get_photo("../etc/passwd") is None


def test_photo_cache_lru_eviction():
    cache = PhotoCache(maxsize=2, ttl_seconds=10.0)
    id1 = "11111111-1111-1111-1111-111111111111"
    id2 = "22222222-2222-2222-2222-222222222222"
    id3 = "33333333-3333-3333-3333-333333333333"

    cache.store_photo(id1, b"data1")
    cache.store_photo(id2, b"data2")

    # Access id1 to make it most recently used
    assert cache.get_photo(id1) == b"data1"

    # Store id3, should evict id2 (least recently used)
    cache.store_photo(id3, b"data3")

    assert len(cache) == 2
    assert cache.get_photo(id1) == b"data1"
    assert cache.get_photo(id2) is None
    assert cache.get_photo(id3) == b"data3"


def test_photo_cache_ttl_expiration():
    cache = PhotoCache(maxsize=5, ttl_seconds=0.1)
    photo_id = "550e8400-e29b-41d4-a716-446655440000"

    cache.store_photo(photo_id, b"data")
    assert cache.get_photo(photo_id) == b"data"

    time.sleep(0.15)
    assert cache.get_photo(photo_id) is None
    assert len(cache) == 0


def test_photo_cache_delete():
    cache = PhotoCache()
    photo_id = "550e8400-e29b-41d4-a716-446655440000"
    cache.store_photo(photo_id, b"data")

    assert cache.delete_photo(photo_id) is True
    assert cache.get_photo(photo_id) is None
    assert cache.delete_photo(photo_id) is False
