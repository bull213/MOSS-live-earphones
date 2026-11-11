import json
from pathlib import Path

from fastapi.testclient import TestClient
from pypdf import PdfWriter

from app.config import Settings
from app.main import create_app


class FixtureEmbedder:
    version = "phone-import-fixture-v1"

    def encode(self, texts):
        vectors = []
        for text in texts:
            vector = [0.0] * 384
            vector[0] = 1.0 if "headline" in text.lower() else 0.0
            vector[1] = 0.01
            vectors.append(vector)
        return vectors


def _blank_pdf(path: Path) -> bytes:
    writer = PdfWriter()
    writer.add_blank_page(width=612, height=792)
    with path.open("wb") as output:
        writer.write(output)
    return path.read_bytes()


def test_phone_import_only_ocr_pages_and_keep_local_index(tmp_path):
    pdf_bytes = _blank_pdf(tmp_path / "scan.pdf")
    settings = Settings(_env_file=None, openai_api_key=None, knowledge_data_dir=tmp_path / "knowledge")
    app = create_app(settings)
    with TestClient(app) as client:
        app.state.knowledge_index.embedder = FixtureEmbedder()
        inspected = client.post("/v1/knowledge/inspect", content=pdf_bytes, headers={
            "content-type": "application/pdf", "x-document-name": "Breakthrough%20Book.pdf",
        })
        assert inspected.status_code == 200
        scan = inspected.json()
        assert scan["title"] == "Breakthrough Book"
        assert scan["page_count"] == 1
        assert scan["pages_requiring_ocr"] == [1]

        recognized = "A headline earns attention by clarifying the promise."
        imported = client.post("/v1/knowledge/import", content=json.dumps({
            "job_id": scan["job_id"], "page_texts": {"1": recognized},
        }), headers={"content-type": "application/json"})
        assert imported.status_code == 200, imported.text
        result = imported.json()
        assert result["status"] == "indexed"
        assert result["title"] == "Breakthrough Book"
        assert result["passages"] == 1

        passages = app.state.knowledge_index.search("headline attention", document_id=result["document_id"])
        assert passages and recognized in passages[0].text
        assert passages[0].page == 1
        assert (settings.knowledge_data_dir / "sources" / f"{scan['file_hash']}.pdf").is_file()
        with app.state.knowledge_index._connect() as db:
            provenance = json.loads(db.execute("SELECT provenance FROM sections WHERE page=1").fetchone()[0])
        assert provenance["provider"] == "google_mlkit"
        assert provenance["model_version"] == "text-recognition-16.0.1"

        repeated = client.post("/v1/knowledge/import", content=json.dumps({
            "job_id": scan["job_id"], "page_texts": {"1": recognized},
        }), headers={"content-type": "application/json"})
        assert repeated.status_code == 200
        assert repeated.json() == result
        changed = client.post("/v1/knowledge/import", content=json.dumps({
            "job_id": scan["job_id"], "page_texts": {"1": "Different OCR text."},
        }), headers={"content-type": "application/json"})
        assert changed.status_code == 409


def test_phone_import_requires_exact_ocr_page_set(tmp_path):
    pdf_bytes = _blank_pdf(tmp_path / "scan.pdf")
    app = create_app(Settings(_env_file=None, openai_api_key=None, knowledge_data_dir=tmp_path / "knowledge"))
    with TestClient(app) as client:
        scan = client.post("/v1/knowledge/inspect", content=pdf_bytes,
                           headers={"content-type": "application/pdf"}).json()
        response = client.post("/v1/knowledge/import", json={"job_id": scan["job_id"], "page_texts": {}})
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "incomplete_ocr_results"


def test_phone_import_rejects_non_string_job_id(tmp_path):
    app = create_app(Settings(_env_file=None, openai_api_key=None, knowledge_data_dir=tmp_path / "knowledge"))
    with TestClient(app) as client:
        response = client.post("/v1/knowledge/import", json={"job_id": 7, "page_texts": {}})
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "invalid_ocr_results"
