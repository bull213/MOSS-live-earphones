"""Source-linked, versioned methods. Approval never asserts automatic source fidelity."""

from __future__ import annotations

from datetime import datetime, timezone
import json
from typing import Annotated, Literal
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

from app.knowledge.index import KnowledgeIndex, _cosine, _vector_blob


ShortText = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=1000)]
Identifier = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=100)]


class GroundedRule(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: ShortText
    passage_ids: list[Identifier] = Field(min_length=1, max_length=20)


class MethodCandidate(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: ShortText
    description: ShortText
    source_document_id: Identifier
    source_section_ids: list[Identifier] = Field(min_length=1, max_length=20)
    supporting_passage_ids: list[Identifier] = Field(min_length=1, max_length=40)
    activation_conditions: list[GroundedRule] = Field(default_factory=list, max_length=20)
    exclusion_conditions: list[GroundedRule] = Field(default_factory=list, max_length=20)
    required_inputs: list[GroundedRule] = Field(default_factory=list, max_length=20)
    procedure_steps: list[GroundedRule] = Field(min_length=1, max_length=30)
    constraints: list[GroundedRule] = Field(default_factory=list, max_length=20)
    warnings: list[GroundedRule] = Field(default_factory=list, max_length=20)
    author_claims: list[GroundedRule] = Field(default_factory=list, max_length=20)
    ai_interpretations: list[ShortText] = Field(default_factory=list, max_length=20)
    related_method_ids: list[Identifier] = Field(default_factory=list, max_length=20)
    conflicting_method_ids: list[Identifier] = Field(default_factory=list, max_length=20)
    confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    uncertainty: list[ShortText] = Field(default_factory=list, max_length=20)

    @model_validator(mode="after")
    def rules_use_declared_evidence(self):
        declared = set(self.supporting_passage_ids)
        for group in (self.activation_conditions, self.exclusion_conditions, self.required_inputs,
                      self.procedure_steps, self.constraints, self.warnings, self.author_claims):
            for rule in group:
                if not set(rule.passage_ids).issubset(declared):
                    raise ValueError("Every rule must reference a declared supporting passage.")
        return self


class MethodExtraction(BaseModel):
    model_config = ConfigDict(extra="forbid")
    candidates: list[MethodCandidate] = Field(max_length=10)


class MethodRegistry:
    def __init__(self, index: KnowledgeIndex) -> None:
        self.index = index

    @staticmethod
    def _validate(candidate: MethodCandidate, db) -> None:
        section_ids = set(candidate.source_section_ids)
        passage_ids = set(candidate.supporting_passage_ids)
        if not db.execute("SELECT 1 FROM documents WHERE id=?", (candidate.source_document_id,)).fetchone():
            raise ValueError("The source document does not exist.")
        sections = {row[0] for row in db.execute(
            f"SELECT id FROM sections WHERE document_id=? AND id IN ({','.join('?' for _ in section_ids)})",
            [candidate.source_document_id, *section_ids])}
        if sections != section_ids:
            raise ValueError("A source section is missing or belongs to another document.")
        passages = db.execute(
            f"SELECT id,section_id FROM passages WHERE document_id=? AND id IN ({','.join('?' for _ in passage_ids)})",
            [candidate.source_document_id, *passage_ids]).fetchall()
        if {row[0] for row in passages} != passage_ids or any(row[1] not in section_ids for row in passages):
            raise ValueError("A supporting passage is missing or outside the declared source sections.")
        for related in set(candidate.related_method_ids + candidate.conflicting_method_ids):
            if not db.execute("SELECT 1 FROM method_versions WHERE method_id=? LIMIT 1", (related,)).fetchone():
                raise ValueError("A related or conflicting method does not exist.")

    def save_candidate(self, candidate: MethodCandidate, *, method_id: str | None = None) -> dict:
        candidate = MethodCandidate.model_validate(candidate)
        with self.index._connect() as db:
            self._validate(candidate, db)
            if method_id is None:
                method_id, version = str(uuid4()), 1
            else:
                current = db.execute("SELECT document_id,MAX(version) AS version FROM method_versions WHERE method_id=?", (method_id,)).fetchone()
                if current is None or current["version"] is None:
                    raise ValueError("The method does not exist.")
                if current["document_id"] != candidate.source_document_id:
                    raise ValueError("A method version cannot change its source document.")
                version = current["version"] + 1
        description = " ".join([candidate.name, candidate.description] +
                               [item.text for item in candidate.activation_conditions + candidate.required_inputs])
        vectors = self.index.embedder.encode([description])
        if len(vectors) != 1:
            raise ValueError("The embedding model returned an unexpected number of vectors.")
        embedding = _vector_blob(vectors[0])
        with self.index._connect() as db:
            # Verify again after embedding in case the document changed concurrently.
            self._validate(candidate, db)
            db.execute("INSERT INTO method_versions VALUES (?,?,?,?,?,?,?,?,?)", (
                method_id, version, candidate.source_document_id, "candidate", 0, candidate.model_dump_json(),
                embedding, self.index.embedder.version, datetime.now(timezone.utc).isoformat()))
            db.executemany("INSERT INTO method_evidence VALUES (?,?,?)",
                           [(method_id, version, pid) for pid in sorted(set(candidate.supporting_passage_ids))])
        return {"method_id": method_id, "version": version, "review_status": "candidate", "stale": False}

    def save_candidates(self, candidates: list[MethodCandidate]) -> list[dict]:
        if len(candidates) > 10:
            raise ValueError("Too many candidates in one extraction.")
        candidates = [MethodCandidate.model_validate(candidate) for candidate in candidates]
        with self.index._connect() as db:
            for candidate in candidates:
                self._validate(candidate, db)
        descriptions = [" ".join([candidate.name, candidate.description] +
                        [item.text for item in candidate.activation_conditions + candidate.required_inputs])
                        for candidate in candidates]
        vectors = self.index.embedder.encode(descriptions)
        if len(vectors) != len(candidates):
            raise ValueError("The embedding model returned an unexpected number of vectors.")
        records = [(str(uuid4()), candidate, _vector_blob(vector)) for candidate, vector in zip(candidates, vectors)]
        created = datetime.now(timezone.utc).isoformat()
        with self.index._connect() as db:
            for method_id, candidate, embedding in records:
                self._validate(candidate, db)
                db.execute("INSERT INTO method_versions VALUES (?,?,?,?,?,?,?,?,?)", (
                    method_id, 1, candidate.source_document_id, "candidate", 0, candidate.model_dump_json(),
                    embedding, self.index.embedder.version, created))
                db.executemany("INSERT INTO method_evidence VALUES (?,?,?)",
                               [(method_id, 1, pid) for pid in sorted(set(candidate.supporting_passage_ids))])
        return [{"method_id": method_id, "version": 1, "review_status": "candidate", "stale": False}
                for method_id, _, _ in records]

    def get(self, method_id: str, version: int | None = None) -> dict:
        sql = "SELECT * FROM method_versions WHERE method_id=?"
        args: list = [method_id]
        if version is not None:
            sql += " AND version=?"
            args.append(version)
        sql += " ORDER BY version DESC LIMIT 1"
        with self.index._connect() as db:
            row = db.execute(sql, args).fetchone()
            if row is None:
                raise ValueError("The method version does not exist.")
            return self._record(row)

    def get_active(self, method_id: str) -> dict | None:
        with self.index._connect() as db:
            row = db.execute("SELECT * FROM method_versions WHERE method_id=? AND review_status='approved' "
                             "AND stale=0 ORDER BY version DESC LIMIT 1", (method_id,)).fetchone()
            return self._record(row) if row is not None else None

    @staticmethod
    def _record(row) -> dict:
        return {"method_id": row["method_id"], "version": row["version"],
                "review_status": row["review_status"], "stale": bool(row["stale"]),
                "created_at": row["created_at"], "method": json.loads(row["payload"])}

    def review(self, method_id: str, version: int, status: Literal["approved", "rejected"]) -> dict:
        if status not in {"approved", "rejected"}:
            raise ValueError("Invalid review status.")
        with self.index._connect() as db:
            row = db.execute("SELECT * FROM method_versions WHERE method_id=? AND version=?", (method_id, version)).fetchone()
            if row is None or row["review_status"] != "candidate":
                raise ValueError("Only an existing candidate can be reviewed.")
            if row["stale"]:
                raise ValueError("The method's source changed; create a new candidate before review.")
            self._validate(MethodCandidate.model_validate_json(row["payload"]), db)
            db.execute("UPDATE method_versions SET review_status=? WHERE method_id=? AND version=?", (status, method_id, version))
        return self.get(method_id, version)

    def list(self, *, document_id: str | None = None) -> list[dict]:
        with self.index._connect() as db:
            if document_id:
                rows = db.execute("SELECT * FROM method_versions WHERE document_id=? ORDER BY method_id,version", (document_id,)).fetchall()
            else:
                rows = db.execute("SELECT * FROM method_versions ORDER BY method_id,version").fetchall()
            return [self._record(row) for row in rows]

    def find(self, query: str, *, limit: int = 5, include_unreviewed: bool = False,
             min_similarity: float = 0.30) -> list[dict]:
        if not query.strip() or len(query) > 4000 or not 1 <= limit <= 20 or not -1 <= min_similarity <= 1:
            raise ValueError("Invalid method query or limit.")
        vectors = self.index.embedder.encode([query])
        if len(vectors) != 1:
            raise ValueError("The embedding model returned an unexpected number of vectors.")
        vector = vectors[0]
        _vector_blob(vector)
        with self.index._connect() as db:
            statuses = ("approved", "candidate") if include_unreviewed else ("approved",)
            placeholders = ",".join("?" for _ in statuses)
            rows = db.execute(f"""SELECT m.* FROM method_versions m WHERE m.stale=0
                AND m.review_status IN ({placeholders}) AND m.version=(SELECT MAX(n.version) FROM method_versions n
                    WHERE n.method_id=m.method_id AND n.stale=0 AND n.review_status IN ({placeholders}))""",
                              [*statuses, *statuses]).fetchall()
            if any(row["embedding_version"] != self.index.embedder.version for row in rows):
                raise RuntimeError("A method uses a different embedding model and must be reindexed.")
            scored = [(row, _cosine(vector, row["embedding"])) for row in rows]
            ranked = sorted((item for item in scored if item[1] >= min_similarity),
                            key=lambda item: item[1], reverse=True)[:limit]
            return [self._record(row) | {"score": score} for row, score in ranked]
