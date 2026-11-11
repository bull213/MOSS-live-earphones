from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile
import os

import pytest

from app.knowledge.extraction import ExtractionError, extract_document
from app.knowledge.index import KnowledgeIndex


class FixtureEmbedder:
    version = "fixture-v1"

    def encode(self, texts):
        vectors = []
        for text in texts:
            lower = text.lower()
            vector = [0.0] * 384
            vector[0] = 1.0 if any(word in lower for word in ("headline", "attention", "awareness")) else 0.0
            vector[1] = 1.0 if any(word in lower for word in ("orchid", "watering", "plant")) else 0.0
            vector[2] = 0.01
            vectors.append(vector)
        return vectors


@pytest.fixture
def index(tmp_path):
    return KnowledgeIndex(tmp_path / "private" / "library.sqlite3", FixtureEmbedder())


def test_text_ingestion_hybrid_search_stable_ids_and_delete(index, tmp_path):
    book = tmp_path / "book.txt"
    book.write_text("# Chapter One\nA headline should earn attention.\n\n# Chapter Two\nCheck customer awareness first.", encoding="utf-8")
    indexed = index.add(book)
    assert indexed["status"] == "indexed"
    matches = index.search("headline attention", document_id=indexed["document_id"])
    assert matches and matches[0].title == "book"
    assert matches[0].chapter == "Chapter One"
    assert matches[0].page is None
    assert "headline" in matches[0].text
    assert index.open_section(matches[0].section_id)[0].id == matches[0].id
    assert index.add(book)["status"] == "unchanged"
    assert index.search("headline")[0].id == matches[0].id
    assert index.remove(indexed["document_id"])
    assert index.list_documents() == []
    assert index.search("headline") == []
    assert book.is_file()


def test_reindex_is_atomic_and_updates_derived_passages(index, tmp_path):
    book = tmp_path / "book.txt"
    book.write_text("A headline earns attention.", encoding="utf-8")
    before = index.add(book)
    original = index.search("headline")[0]
    book.write_text("An orchid needs regular watering.", encoding="utf-8")
    class BrokenEmbedder(FixtureEmbedder):
        version = "broken"
        def encode(self, texts):
            raise RuntimeError("no model")
    broken = KnowledgeIndex(index.database, BrokenEmbedder())
    with pytest.raises(RuntimeError):
        broken.add(book)
    assert index.search("headline")[0].id == original.id
    updated = index.add(book)
    assert updated["document_id"] == before["document_id"]
    assert "orchid" in index.search("watering")[0].text.lower()
    assert all(item.id != original.id for item in index.search("headline"))


def test_search_rejects_mismatched_embedding_version(index, tmp_path):
    book = tmp_path / "book.txt"
    book.write_text("A headline earns attention.", encoding="utf-8")
    index.add(book)
    class DifferentEmbedder(FixtureEmbedder):
        version = "different-model"
    with pytest.raises(RuntimeError, match="reindex"):
        KnowledgeIndex(index.database, DifferentEmbedder()).search("headline")


def test_filters_apply_before_lexical_cutoff(index, tmp_path):
    for number in range(25):
        book = tmp_path / f"book-{number}.txt"
        book.write_text(f"headline attention number {number}", encoding="utf-8")
        result = index.add(book)
    filtered = index.search("headline", limit=1, document_id=result["document_id"])
    assert len(filtered) == 1 and filtered[0].document_id == result["document_id"]
    assert index.search('" OR *', document_id="missing") == []


def make_epub(path: Path, href: str = "chapter.xhtml"):
    with ZipFile(path, "w", compression=ZIP_DEFLATED) as epub:
        epub.writestr("META-INF/container.xml", '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/book.opf"/></rootfiles></container>')
        epub.writestr("OEBPS/book.opf", f'''<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
            <metadata><dc:title>Fixture Book</dc:title><dc:creator>Fixture Author</dc:creator></metadata>
            <manifest><item id="one" href="{href}" media-type="application/xhtml+xml"/></manifest>
            <spine><itemref idref="one"/></spine></package>''')
        epub.writestr("OEBPS/chapter.xhtml", "<html><body><h1>Actual Heading</h1><p>A headline attracts attention.</p><script>ignore me</script></body></html>")


def test_epub_metadata_spine_and_source_text(tmp_path):
    path = tmp_path / "book.epub"
    make_epub(path)
    result = extract_document(path)
    assert result["title"] == "Fixture Book"
    assert result["author"] == "Fixture Author"
    assert result["sections"][0]["chapter"] == "Actual Heading"
    assert "headline" in result["sections"][0]["text"]
    assert "ignore me" not in result["sections"][0]["text"]
    assert result["sections"][0]["page"] is None


@pytest.mark.parametrize("href", ["https://example.com/x", "../../escape.xhtml", "/absolute.xhtml"])
def test_epub_does_not_access_external_or_unsafe_paths(tmp_path, href):
    path = tmp_path / "book.epub"
    make_epub(path, href)
    with pytest.raises(ExtractionError):
        extract_document(path)


def test_scanned_or_empty_pdf_reports_ocr_limitation(tmp_path):
    from pypdf import PdfWriter
    path = tmp_path / "scanned.pdf"
    writer = PdfWriter()
    writer.add_blank_page(width=100, height=100)
    with path.open("wb") as out:
        writer.write(out)
    with pytest.raises(ExtractionError, match="OCR"):
        extract_document(path)


def test_pdf_text_and_page_metadata_are_extracted(tmp_path):
    from pypdf import PdfWriter
    from pypdf.generic import DecodedStreamObject, DictionaryObject, NameObject
    path = tmp_path / "digital.pdf"
    writer = PdfWriter()
    page = writer.add_blank_page(width=200, height=200)
    font = DictionaryObject({NameObject("/Type"): NameObject("/Font"),
                             NameObject("/Subtype"): NameObject("/Type1"),
                             NameObject("/BaseFont"): NameObject("/Helvetica")})
    page[NameObject("/Resources")] = DictionaryObject({NameObject("/Font"): DictionaryObject({NameObject("/F1"): font})})
    content = DecodedStreamObject()
    content.set_data(b"BT /F1 12 Tf 20 100 Td (Headline attracts attention.) Tj ET")
    page[NameObject("/Contents")] = writer._add_object(content)
    writer.add_metadata({"/Title": "Real PDF title", "/Author": "Fixture Author"})
    with path.open("wb") as out:
        writer.write(out)
    result = extract_document(path)
    assert result["title"] == "Real PDF title"
    assert result["author"] == "Fixture Author"
    assert result["sections"][0]["page"] == 1
    assert "Headline attracts attention" in result["sections"][0]["text"]


def test_native_pdf_quality_assessment_flags_sparse_and_corrupt_text():
    from app.knowledge.extract_worker import _native_text_quality
    assert _native_text_quality(("A complete native paragraph with readable book text. " * 4), 500_000) == (True, None)
    assert _native_text_quality("", 500_000) == (False, "no_text")
    assert _native_text_quality("abc", 500_000) == (False, "sparse_text")
    assert _native_text_quality("bad\ufffd" * 20, 500_000) == (False, "corrupt_text")


def test_extraction_provenance_and_fingerprint_are_persisted(index, tmp_path, monkeypatch):
    source = tmp_path / "book.pdf"
    source.write_bytes(b"fixture")
    metadata = {"provider": "tesseract", "model_version": "test-version", "pages_processed": 1}
    extracted = {
        "source_path": str(source.resolve()), "content_hash": "ocr-fingerprint-1", "title": "OCR Book",
        "author": None, "warnings": [], "extraction_metadata": metadata,
        "sections": [{"chapter": "Chapter One", "section": None, "page": 4,
                      "text": "A headline attracts attention and supports retrieval.",
                      "provenance": {"source": "ocr", "provider": "tesseract", "model_version": "test-version"}}],
    }
    monkeypatch.setattr("app.knowledge.index.extract_document", lambda _path: extracted)
    result = index.add(source)
    assert result["status"] == "indexed"
    listed = index.list_documents()[0]
    assert listed["extraction_metadata"] == metadata
    with index._connect() as db:
        section = db.execute("SELECT page,provenance FROM sections").fetchone()
    assert section["page"] == 4
    assert '"source": "ocr"' in section["provenance"]


def test_unlimited_ocr_adapter_requires_loopback_and_maps_page_markers(tmp_path, monkeypatch):
    import json
    from app.knowledge import ocr

    monkeypatch.setenv("UNLIMITED_OCR_NGRAM_PROCESSOR", "pinned-processor")
    monkeypatch.setenv("UNLIMITED_OCR_ENDPOINT", "http://example.com/v1/chat/completions")
    with pytest.raises(RuntimeError, match="loopback"):
        ocr.UnlimitedOCR2Provider()

    monkeypatch.setenv("UNLIMITED_OCR_ENDPOINT", "http://127.0.0.1:10000/v1/chat/completions")
    provider = ocr.UnlimitedOCR2Provider()
    pages = [tmp_path / "page-1.jpg", tmp_path / "page-2.jpg"]
    for page in pages:
        page.write_bytes(b"image")

    class Response:
        def __enter__(self):
            return self
        def __exit__(self, *_args):
            return False
        def read(self, _limit):
            return json.dumps({"choices": [{"finish_reason": "stop", "message": {
                "content": "<PAGE>First page text<PAGE>Second page text"}}]}).encode()

    monkeypatch.setattr(ocr, "urlopen", lambda *_args, **_kwargs: Response())
    results = provider.recognize_many(pages)
    assert [result.text for result in results] == ["First page text", "Second page text"]
    assert all(result.provider == "unlimited-ocr-2" for result in results)


def test_bad_or_empty_input_is_not_indexed(index, tmp_path):
    path = tmp_path / "bad.txt"
    path.write_bytes(b"")
    with pytest.raises(ExtractionError):
        index.add(path)
    assert index.list_documents() == []


@pytest.mark.skipif(os.environ.get("RUN_LOCAL_EMBEDDING_TESTS") != "1", reason="Explicit local model check only")
def test_real_local_model_finds_related_passage(tmp_path):
    from app.config import BACKEND_DIR
    from app.knowledge.embeddings import LocalMiniLM
    model = BACKEND_DIR / ".data" / "knowledge" / "models" / "all-MiniLM-L6-v2"
    index = KnowledgeIndex(tmp_path / "library.sqlite3", LocalMiniLM(model))
    first = tmp_path / "advertising.txt"
    first.write_text("Write a compelling headline that attracts customers to an advertisement.", encoding="utf-8")
    second = tmp_path / "gardening.txt"
    second.write_text("Water the garden plants and remove dead leaves from the soil.", encoding="utf-8")
    advertising = index.add(first)
    index.add(second)
    assert index.search("Create a persuasive ad title", limit=1)[0].document_id == advertising["document_id"]
