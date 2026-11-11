"""Validate and normalize a single still image without persisting it."""

from io import BytesIO

from PIL import Image, ImageOps, UnidentifiedImageError

from app.config import MAX_IMAGE_BYTES, MAX_IMAGE_EDGE, MAX_IMAGE_PIXELS
from app.errors import AssistantError


ALLOWED_FORMATS = {"image/jpeg": "JPEG", "image/png": "PNG"}


def normalize_image(data: bytes, content_type: str | None) -> bytes:
    if len(data) > MAX_IMAGE_BYTES:
        raise AssistantError(413, "image_too_large", "Choose a photograph smaller than 4 MiB.")
    expected_format = ALLOWED_FORMATS.get((content_type or "").lower())
    if expected_format is None:
        raise AssistantError(415, "unsupported_image", "Choose a JPEG or PNG photograph.")
    if not data:
        raise AssistantError(400, "invalid_image", "The photograph is empty. Please take another.")

    try:
        with Image.open(BytesIO(data)) as source:
            if source.format != expected_format:
                raise AssistantError(415, "unsupported_image", "The image contents do not match its JPEG or PNG file type.")
            if source.width * source.height > MAX_IMAGE_PIXELS:
                raise AssistantError(413, "image_dimensions_too_large", "Use a photograph of 20 megapixels or less.")
            if getattr(source, "n_frames", 1) != 1:
                raise AssistantError(415, "animated_image", "Use one still photograph, not an animated image.")
            source.verify()

        with Image.open(BytesIO(data)) as source:
            with ImageOps.exif_transpose(source) as oriented:
                oriented.thumbnail((MAX_IMAGE_EDGE, MAX_IMAGE_EDGE), Image.Resampling.LANCZOS)
                # Composite transparent PNGs on white before producing a JPEG.
                with oriented.convert("RGBA") as rgba:
                    with Image.new("RGB", rgba.size, "white") as clean:
                        with rgba.getchannel("A") as alpha:
                            clean.paste(rgba, mask=alpha)
                        with BytesIO() as output:
                            clean.save(output, format="JPEG", quality=85)
                            return output.getvalue()
    except Image.DecompressionBombError:
        raise AssistantError(413, "image_dimensions_too_large", "Use a photograph of 20 megapixels or less.") from None
    except (UnidentifiedImageError, OSError, SyntaxError, ValueError):
        raise AssistantError(400, "invalid_image", "The photograph could not be read. Please take another.") from None
