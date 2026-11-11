from io import BytesIO

import pytest
from PIL import Image, PngImagePlugin

from app.config import MAX_IMAGE_BYTES
from app.errors import AssistantError
from app.images import normalize_image


def test_normalizes_orientation_size_and_removes_metadata():
    with Image.new("RGB", (2000, 1000), "red") as source, BytesIO() as upload:
        exif = Image.Exif()
        exif[274] = 6
        exif[270] = "private camera description"
        source.save(upload, "JPEG", exif=exif)
        result = normalize_image(upload.getvalue(), "image/jpeg")

    with Image.open(BytesIO(result)) as normalized:
        assert normalized.format == "JPEG"
        assert normalized.mode == "RGB"
        assert normalized.size == (800, 1600)
        assert not normalized.getexif()
        assert "icc_profile" not in normalized.info
    assert b"private camera description" not in result


def test_transparent_png_uses_white_background_without_upscaling():
    with Image.new("RGBA", (20, 10), (0, 0, 0, 0)) as source, BytesIO() as upload:
        metadata = PngImagePlugin.PngInfo()
        metadata.add_text("Comment", "private comment")
        source.save(upload, "PNG", pnginfo=metadata)
        result = normalize_image(upload.getvalue(), "image/png")
    with Image.open(BytesIO(result)) as normalized:
        assert normalized.size == (20, 10)
        assert normalized.getpixel((5, 5)) == (255, 255, 255)
        assert "Comment" not in normalized.info


@pytest.mark.parametrize("content_type", ["image/gif", "image/webp", "text/plain", None])
def test_rejects_unsupported_mime_types(jpeg_bytes, content_type):
    with pytest.raises(AssistantError) as caught:
        normalize_image(jpeg_bytes, content_type)
    assert caught.value.status_code == 415


def test_rejects_false_mime_type(jpeg_bytes):
    with pytest.raises(AssistantError) as caught:
        normalize_image(jpeg_bytes, "image/png")
    assert caught.value.status_code == 415


@pytest.mark.parametrize("data", [b"", b"not a photograph", b"\xff\xd8\xff\xe0"])
def test_rejects_empty_or_corrupt_image(data):
    with pytest.raises(AssistantError) as caught:
        normalize_image(data, "image/jpeg")
    assert caught.value.status_code == 400


def test_rejects_truncated_jpeg(jpeg_bytes):
    with pytest.raises(AssistantError) as caught:
        normalize_image(jpeg_bytes[:len(jpeg_bytes) // 2], "image/jpeg")
    assert caught.value.status_code == 400


def test_image_size_boundary(jpeg_bytes):
    data = jpeg_bytes + b"\0" * (MAX_IMAGE_BYTES - len(jpeg_bytes))
    assert normalize_image(data, "image/jpeg").startswith(b"\xff\xd8")
    with pytest.raises(AssistantError) as caught:
        normalize_image(data + b"\0", "image/jpeg")
    assert caught.value.code == "image_too_large"


def test_rejects_excessive_pixel_count():
    with Image.new("1", (5000, 4001)) as source, BytesIO() as upload:
        source.save(upload, "PNG")
        with pytest.raises(AssistantError) as caught:
            normalize_image(upload.getvalue(), "image/png")
    assert caught.value.code == "image_dimensions_too_large"


def test_rejects_animated_png():
    with Image.new("RGB", (10, 10), "red") as first, Image.new("RGB", (10, 10), "blue") as second, BytesIO() as upload:
        first.save(upload, "PNG", save_all=True, append_images=[second], duration=100)
        with pytest.raises(AssistantError) as caught:
            normalize_image(upload.getvalue(), "image/png")
    assert caught.value.code == "animated_image"
