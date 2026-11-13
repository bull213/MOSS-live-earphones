"""Small single-owner SQLite index with lexical and local semantic retrieval."""

from __future__ import annotations

from dataclasses import dataclass
from contextlib import contextmanager
import json
import math
from pathlib import Path
import re
import sqlite3
import struct
from uuid import UUID, uuid4, uuid5

from app.knowledge.embeddings import DIMENSION, EmbeddingProvider
from app.knowledge.extraction import extract_document


INDEX_VERSION = 1
CHUNK_CHARS = 1200
OVERLAP_CHARS = 180
RRF_K = 60
MAX_PASSAGES = 50_000


@dataclass(frozen=True)
class Passage:
    id: str
    document_id: str
    title: str
    author: str | None
    chapter_id: str
    chapter: str | None
    section_id: str
    section: str | None
    page: int | None
    text: str
    context: str
    ordinal: int
    score: float = 0.0
    similarity: float = 0.0


def _chunks(text: str):
    start = 0
    while start < len(text):
        end = min(start + CHUNK_CHARS, len(text))
        if end < len(text):
            boundary = text.rfind(" ", start + CHUNK_CHARS // 2, end)
            if boundary > start:
                end = boundary
        excerpt = text[start:end].strip()
        if excerpt:
            yield start, end, excerpt
        if end == len(text):
            break
        start = max(start + 1, end - OVERLAP_CHARS)


def _vector_blob(vector: list[float]) -> bytes:
    if len(vector) != DIMENSION or not all(math.isfinite(value) for value in vector):
        raise ValueError("Invalid embedding vector.")
    return struct.pack(f"<{DIMENSION}f", *vector)


def _cosine(left: list[float], blob: bytes) -> float:
    if len(blob) != DIMENSION * 4:
        raise ValueError("Invalid stored embedding.")
    right = struct.unpack(f"<{DIMENSION}f", blob)
    dot = sum(a * b for a, b in zip(left, right))
    norm_left = math.sqrt(sum(a * a for a in left))
    norm_right = math.sqrt(sum(b * b for b in right))
    return dot / (norm_left * norm_right) if norm_left and norm_right else 0.0


class KnowledgeIndex:
    def __init__(self, database: Path, embedder: EmbeddingProvider) -> None:
        self.database = database
        self.embedder = embedder

    @contextmanager
    def _connect(self):
        self.database.parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(self.database, timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute("PRAGMA secure_delete=ON")
        try:
            connection.executescript("""
        CREATE TABLE IF NOT EXISTS documents (
          id TEXT PRIMARY KEY, source_path TEXT NOT NULL UNIQUE, content_hash TEXT NOT NULL,
          title TEXT NOT NULL, author TEXT, index_version INTEGER NOT NULL, embedding_version TEXT NOT NULL,
          contextual INTEGER NOT NULL DEFAULT 0, warnings TEXT NOT NULL DEFAULT '[]',
          extraction_metadata TEXT NOT NULL DEFAULT '{}'
        );
        CREATE TABLE IF NOT EXISTS chapters (
          id TEXT PRIMARY KEY, document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
          ordinal INTEGER NOT NULL, title TEXT
        );
        CREATE TABLE IF NOT EXISTS sections (
          id TEXT PRIMARY KEY, document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
          chapter_id TEXT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
          ordinal INTEGER NOT NULL, title TEXT, page INTEGER, provenance TEXT NOT NULL DEFAULT '{}'
        );
        CREATE TABLE IF NOT EXISTS passages (
          id TEXT PRIMARY KEY, document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
          section_id TEXT NOT NULL REFERENCES sections(id) ON DELETE CASCADE, ordinal INTEGER NOT NULL,
          start_char INTEGER NOT NULL, end_char INTEGER NOT NULL, text TEXT NOT NULL,
          context TEXT NOT NULL, embedding BLOB NOT NULL
        );
        CREATE VIRTUAL TABLE IF NOT EXISTS passage_fts USING fts5(passage_id UNINDEXED, text, tokenize='unicode61');
        CREATE TABLE IF NOT EXISTS method_versions (
          method_id TEXT NOT NULL, version INTEGER NOT NULL,
          document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
          review_status TEXT NOT NULL, stale INTEGER NOT NULL DEFAULT 0, payload TEXT NOT NULL,
          embedding BLOB NOT NULL, embedding_version TEXT NOT NULL, created_at TEXT NOT NULL,
          PRIMARY KEY(method_id,version)
        );
        CREATE TABLE IF NOT EXISTS method_evidence (
          method_id TEXT NOT NULL, version INTEGER NOT NULL, passage_id TEXT NOT NULL,
          PRIMARY KEY(method_id,version,passage_id),
          FOREIGN KEY(method_id,version) REFERENCES method_versions(method_id,version) ON DELETE CASCADE
        );
            """)
            columns = {row[1] for row in connection.execute("PRAGMA table_info(documents)")}
            if "warnings" not in columns:
                connection.execute("ALTER TABLE documents ADD COLUMN warnings TEXT NOT NULL DEFAULT '[]'")
            if "extraction_metadata" not in columns:
                connection.execute("ALTER TABLE documents ADD COLUMN extraction_metadata TEXT NOT NULL DEFAULT '{}'")
            if "notebooklm_id" not in columns:
                connection.execute("ALTER TABLE documents ADD COLUMN notebooklm_id TEXT DEFAULT NULL")
            if "notebooklm_status" not in columns:
                connection.execute("ALTER TABLE documents ADD COLUMN notebooklm_status TEXT NOT NULL DEFAULT 'NONE'")
            if "privacy_tier" not in columns:
                connection.execute("ALTER TABLE documents ADD COLUMN privacy_tier TEXT NOT NULL DEFAULT 'TIER_1_PUBLIC'")
            section_columns = {row[1] for row in connection.execute("PRAGMA table_info(sections)")}
            if "provenance" not in section_columns:
                connection.execute("ALTER TABLE sections ADD COLUMN provenance TEXT NOT NULL DEFAULT '{}'")
            with connection:
                yield connection
        finally:
            connection.close()

    def add(self, path: Path, *, contextual: bool = False, mobile_ocr: dict | None = None) -> dict:
        extracted = extract_document(path) if mobile_ocr is None else extract_document(path, mobile_ocr=mobile_ocr)
        with self._connect() as db:
            previous = db.execute("SELECT * FROM documents WHERE source_path=?", (extracted["source_path"],)).fetchone()
            if previous and previous["content_hash"] == extracted["content_hash"] and previous["index_version"] == INDEX_VERSION and previous["embedding_version"] == self.embedder.version and previous["contextual"] == int(contextual):
                return {"document_id": previous["id"], "status": "unchanged", "warnings": json.loads(previous["warnings"])}
            doc_id = previous["id"] if previous else str(uuid4())

        chapters, sections, passages = [], [], []
        last_chapter = object()
        chapter_id = ""
        for section_no, item in enumerate(extracted["sections"]):
            if item["chapter"] != last_chapter:
                last_chapter = item["chapter"]
                chapter_id = str(uuid5(UUID(doc_id), f"chapter:{section_no}:{last_chapter}"))
                chapters.append((chapter_id, doc_id, len(chapters), item["chapter"]))
            section_id = str(uuid5(UUID(doc_id), f"section:{section_no}:{item['page']}:{item['section']}"))
            sections.append((section_id, doc_id, chapter_id, section_no, item["section"], item["page"],
                             json.dumps(item.get("provenance", {}), ensure_ascii=False, sort_keys=True)))
            context = " | ".join(dict.fromkeys(str(value) for value in (extracted["title"], item["chapter"], item["section"]) if value))
            for start, end, text in _chunks(item["text"]):
                passage_id = str(uuid5(UUID(section_id), f"{start}:{end}:{text}"))
                passages.append((passage_id, doc_id, section_id, len(passages), start, end, text, context))
        vectors = self.embedder.encode([f"{p[7]}\n{p[6]}" if contextual else p[6] for p in passages])
        if len(vectors) != len(passages):
            raise ValueError("The embedding model returned an unexpected number of vectors.")
        rows = [(*passage, _vector_blob(vector)) for passage, vector in zip(passages, vectors)]
        with self._connect() as db:
            # Replace source data as one transaction; any failure leaves the old index intact.
            count = db.execute("SELECT COUNT(*) FROM passages WHERE document_id != ?", (doc_id,)).fetchone()[0]
            if count + len(rows) > MAX_PASSAGES:
                raise ValueError("The library exceeds the local passage limit.")
            db.execute("DELETE FROM passage_fts WHERE passage_id IN (SELECT id FROM passages WHERE document_id=?)", (doc_id,))
            db.execute("DELETE FROM chapters WHERE document_id=?", (doc_id,))
            db.execute("INSERT INTO documents(id,source_path,content_hash,title,author,index_version,embedding_version,contextual,warnings,extraction_metadata) VALUES (?,?,?,?,?,?,?,?,?,?) "
                       "ON CONFLICT(id) DO UPDATE SET source_path=excluded.source_path,content_hash=excluded.content_hash,title=excluded.title,"
                       "author=excluded.author,index_version=excluded.index_version,embedding_version=excluded.embedding_version,"
                       "contextual=excluded.contextual,warnings=excluded.warnings,extraction_metadata=excluded.extraction_metadata",
                       (doc_id, extracted["source_path"], extracted["content_hash"], extracted["title"], extracted["author"],
                        INDEX_VERSION, self.embedder.version, int(contextual), json.dumps(extracted["warnings"]),
                        json.dumps(extracted.get("extraction_metadata", {}), ensure_ascii=False, sort_keys=True)))
            db.executemany("INSERT INTO chapters VALUES (?,?,?,?)", chapters)
            db.executemany("INSERT INTO sections(id,document_id,chapter_id,ordinal,title,page,provenance) VALUES (?,?,?,?,?,?,?)", sections)
            db.executemany("INSERT INTO passages VALUES (?,?,?,?,?,?,?,?,?)", rows)
            db.executemany("INSERT INTO passage_fts(passage_id,text) VALUES (?,?)", [(row[0], row[6]) for row in rows])
            db.execute("""UPDATE method_versions SET stale=1 WHERE document_id=? AND EXISTS (
                SELECT 1 FROM method_evidence e WHERE e.method_id=method_versions.method_id
                AND e.version=method_versions.version AND NOT EXISTS
                (SELECT 1 FROM passages p WHERE p.id=e.passage_id AND p.document_id=method_versions.document_id))""", (doc_id,))
        return {"document_id": doc_id, "status": "indexed", "passages": len(rows), "warnings": extracted["warnings"]}

    def list_documents(self) -> list[dict]:
        with self._connect() as db:
            result = [dict(row) for row in db.execute(
                "SELECT id,title,author,content_hash,index_version,embedding_version,contextual,warnings,extraction_metadata,"
                "notebooklm_id,notebooklm_status,privacy_tier FROM documents ORDER BY title"
            )]
            for row in result:
                row["warnings"] = json.loads(row["warnings"])
                row["extraction_metadata"] = json.loads(row["extraction_metadata"])
            return result

    def update_notebooklm_metadata(self, document_id: str, *, notebooklm_id: str | None = None,
                                  notebooklm_status: str | None = None, privacy_tier: str | None = None) -> bool:
        updates = []
        params = []
        if notebooklm_id is not None:
            updates.append("notebooklm_id = ?")
            params.append(notebooklm_id)
        if notebooklm_status is not None:
            updates.append("notebooklm_status = ?")
            params.append(notebooklm_status)
        if privacy_tier is not None:
            updates.append("privacy_tier = ?")
            params.append(privacy_tier)
        if not updates:
            return False
        params.append(document_id)
        with self._connect() as db:
            return db.execute(f"UPDATE documents SET {', '.join(updates)} WHERE id = ?", params).rowcount > 0

    def remove(self, document_id: str) -> bool:
        with self._connect() as db:
            db.execute("DELETE FROM passage_fts WHERE passage_id IN (SELECT id FROM passages WHERE document_id=?)", (document_id,))
            return db.execute("DELETE FROM documents WHERE id=?", (document_id,)).rowcount == 1

    def search(self, query: str, *, limit: int = 8, document_id: str | None = None,
               chapter_id: str | None = None) -> list[Passage]:
        query = query.strip()
        if not query or len(query) > 4000 or not 1 <= limit <= 30:
            raise ValueError("Invalid search query or limit.")
        vector = self.embedder.encode([query])[0]
        _vector_blob(vector)
        terms = re.findall(r"\w+", query, flags=re.UNICODE)[:32]
        match = " OR ".join('"' + term.replace('"', '""') + '"' for term in terms)
        conditions, args = [], []
        if document_id:
            conditions.append("p.document_id=?")
            args.append(document_id)
        if chapter_id:
            conditions.append("s.chapter_id=?")
            args.append(chapter_id)
        where = " WHERE " + " AND ".join(conditions) if conditions else ""
        sql = """SELECT p.*,d.title AS document_title,d.author,s.title AS section_title,s.page,
                 c.id AS chapter_id,c.title AS chapter_title FROM passages p
                 JOIN documents d ON p.document_id=d.id JOIN sections s ON p.section_id=s.id
                 JOIN chapters c ON s.chapter_id=c.id"""
        with self._connect() as db:
            rows = db.execute(sql + where, args).fetchall()
            versions = {row[0] for row in db.execute("SELECT DISTINCT embedding_version FROM documents")}
            if versions and versions != {self.embedder.version}:
                raise RuntimeError("The library uses a different embedding model; reindex its documents before searching.")
            lexical = []
            if match:
                lexical_where = " AND " + " AND ".join(conditions) if conditions else ""
                lexical = [row[0] for row in db.execute(
                    "SELECT passage_fts.passage_id FROM passage_fts JOIN passages p ON p.id=passage_fts.passage_id "
                    "JOIN sections s ON s.id=p.section_id WHERE passage_fts MATCH ?" + lexical_where +
                    " ORDER BY bm25(passage_fts) LIMIT ?", [match, *args, max(20, limit * 3)])]
        by_id = {row["id"]: row for row in rows}
        lexical = [pid for pid in lexical if pid in by_id]
        similarities = {row["id"]: _cosine(vector, row["embedding"]) for row in rows}
        semantic = sorted(rows, key=lambda row: similarities[row["id"]], reverse=True)[:max(20, limit * 3)]
        scores: dict[str, float] = {}
        for ids in (lexical, [row["id"] for row in semantic]):
            for rank, pid in enumerate(ids, 1):
                scores[pid] = scores.get(pid, 0.0) + 1.0 / (RRF_K + rank)
        selected = sorted(scores, key=lambda pid: (-scores[pid], by_id[pid]["ordinal"], pid))[:limit]
        return [self._passage(by_id[pid], scores[pid], similarities[pid]) for pid in selected]

    def open_section(self, section_id: str, *, limit: int = 30, offset: int = 0) -> list[Passage]:
        if not 1 <= limit <= 100 or offset < 0:
            raise ValueError("Invalid section pagination.")
        with self._connect() as db:
            rows = db.execute("""SELECT p.*,d.title AS document_title,d.author,s.title AS section_title,s.page,
                 c.id AS chapter_id,c.title AS chapter_title FROM passages p
                 JOIN documents d ON p.document_id=d.id JOIN sections s ON p.section_id=s.id
                 JOIN chapters c ON s.chapter_id=c.id WHERE p.section_id=? ORDER BY p.ordinal LIMIT ? OFFSET ?""",
                              (section_id, limit, offset)).fetchall()
            return [self._passage(row, 0.0) for row in rows]

    def get_passages(self, passage_ids: list[str]) -> list[Passage]:
        ids = list(dict.fromkeys(passage_ids))
        if len(ids) > 50:
            raise ValueError("Too many passage IDs requested.")
        if not ids:
            return []
        with self._connect() as db:
            rows = db.execute(f"""SELECT p.*,d.title AS document_title,d.author,s.title AS section_title,s.page,
                 c.id AS chapter_id,c.title AS chapter_title FROM passages p
                 JOIN documents d ON p.document_id=d.id JOIN sections s ON p.section_id=s.id
                 JOIN chapters c ON s.chapter_id=c.id WHERE p.id IN ({','.join('?' for _ in ids)})""", ids).fetchall()
        by_id = {row["id"]: row for row in rows}
        return [self._passage(by_id[pid], 0.0) for pid in ids if pid in by_id]

    @staticmethod
    def _passage(row: sqlite3.Row, score: float, similarity: float = 0.0) -> Passage:
        return Passage(id=row["id"], document_id=row["document_id"], title=row["document_title"], author=row["author"],
                       chapter_id=row["chapter_id"], chapter=row["chapter_title"], section_id=row["section_id"],
                       section=row["section_title"], page=row["page"], text=row["text"], context=row["context"],
                       ordinal=row["ordinal"], score=score, similarity=similarity)
