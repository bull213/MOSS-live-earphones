"""Evaluate local retrieval against independently reviewed reference IDs."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from statistics import mean
from time import perf_counter

from pydantic import BaseModel, ConfigDict, Field

from app.config import BACKEND_DIR
from app.knowledge.embeddings import LocalMiniLM
from app.knowledge.index import INDEX_VERSION, KnowledgeIndex
from app.knowledge.methods import MethodRegistry


class RetrievalCase(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: str = Field(min_length=1, max_length=100)
    query: str = Field(min_length=1, max_length=1000)
    passage_ids: list[str] = Field(default_factory=list)
    section_ids: list[str] = Field(default_factory=list)
    method_ids: list[str] = Field(default_factory=list)


def evaluate(index: KnowledgeIndex, cases: list[RetrievalCase], k: int) -> dict:
    if not 1 <= k <= 20 or not cases:
        raise ValueError("Use 1–20 results and at least one evaluation case.")
    registry = MethodRegistry(index)
    rows = []
    for case in cases:
        start = perf_counter()
        passages = index.search(case.query, limit=k)
        methods = registry.find(case.query, limit=k)
        elapsed_ms = round((perf_counter() - start) * 1000, 2)
        passage_ids = {item.id for item in passages}
        resolvable_ids = {item.id for item in index.get_passages(list(passage_ids))}
        section_ids = {item.section_id for item in passages}
        method_ids = {item["method_id"] for item in methods}
        expected_passages, expected_sections, expected_methods = map(set, (case.passage_ids, case.section_ids, case.method_ids))
        rows.append({
            "id": case.id,
            "passage_recall": len(expected_passages & passage_ids) / len(expected_passages) if expected_passages else None,
            "section_recall": len(expected_sections & section_ids) / len(expected_sections) if expected_sections else None,
            "method_recall": len(expected_methods & method_ids) / len(expected_methods) if expected_methods else None,
            "evidence_complete": expected_passages <= passage_ids if expected_passages else None,
            "source_reference_accuracy": len(resolvable_ids) / len(passage_ids) if passage_ids else None,
            "latency_ms": elapsed_ms,
        })
    def average(key: str):
        values = [row[key] for row in rows if row[key] is not None]
        return round(mean(values), 4) if values else None
    return {"index_version": INDEX_VERSION, "embedding_version": index.embedder.version, "k": k,
            "case_count": len(rows), "passage_recall": average("passage_recall"),
            "section_recall": average("section_recall"), "method_recall": average("method_recall"),
            "evidence_completeness": average("evidence_complete"),
            "source_reference_accuracy": average("source_reference_accuracy"), "mean_latency_ms": average("latency_ms"),
            "cases": rows}


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate an existing private knowledge index without calling a provider.")
    parser.add_argument("cases", type=Path, help="JSON array of reviewed retrieval cases")
    parser.add_argument("--data-dir", type=Path, default=Path(os.environ.get("KNOWLEDGE_DATA_DIR", BACKEND_DIR / ".data" / "knowledge")))
    parser.add_argument("--k", type=int, default=5)
    args = parser.parse_args()
    if not (args.data_dir / "library.sqlite3").is_file():
        parser.error("Knowledge index not found; index documents first.")
    if args.cases.stat().st_size > 1_000_000:
        parser.error("Case file exceeds 1 MB.")
    raw = json.loads(args.cases.read_text(encoding="utf-8"))
    if not isinstance(raw, list) or len(raw) > 1000:
        parser.error("Cases must be an array with at most 1,000 entries.")
    cases = [RetrievalCase.model_validate(item) for item in raw]
    index = KnowledgeIndex(args.data_dir / "library.sqlite3", LocalMiniLM(args.data_dir / "models" / "all-MiniLM-L6-v2"))
    print(json.dumps(evaluate(index, cases, args.k), indent=2))


if __name__ == "__main__":
    main()
