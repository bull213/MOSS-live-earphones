"""Local, explicit document administration. No document administration HTTP API."""

from __future__ import annotations

import argparse
import asyncio
from dataclasses import asdict
import json
import os
from pathlib import Path
import sys
from pydantic import ValidationError

from app.config import BACKEND_DIR
from app.knowledge.embeddings import LocalMiniLM, fetch_model
from app.knowledge.extraction import ExtractionError
from app.knowledge.index import KnowledgeIndex
from app.knowledge.methods import MethodCandidate, MethodRegistry


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Manage a private local document index.")
    parser.add_argument("--data-dir", type=Path, default=Path(os.environ.get("KNOWLEDGE_DATA_DIR", BACKEND_DIR / ".data" / "knowledge")))
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("fetch-model", help="Explicitly download the pinned local embedding model.")
    add = commands.add_parser("add", help="Index one local PDF, EPUB, TXT, or page-image document.")
    add.add_argument("file", type=Path)
    add.add_argument("--contextual", action="store_true", help="Include source-derived chapter context in embeddings.")
    commands.add_parser("list", help="List indexed documents.")
    remove = commands.add_parser("remove", help="Remove a document and derived index data, not the original file.")
    remove.add_argument("document_id")
    remove.add_argument("--yes", action="store_true", required=True)
    search = commands.add_parser("search", help="Search indexed documents locally.")
    search.add_argument("query")
    search.add_argument("--document-id")
    search.add_argument("--chapter-id")
    search.add_argument("--limit", type=int, default=8)
    section = commands.add_parser("open-section", help="Show passages in a section.")
    section.add_argument("section_id")
    section.add_argument("--limit", type=int, default=30)
    section.add_argument("--offset", type=int, default=0)
    methods = commands.add_parser("methods", help="Extract and review source-linked methods.")
    method_commands = methods.add_subparsers(dest="method_command", required=True)
    method_list = method_commands.add_parser("list")
    method_list.add_argument("--document-id")
    method_show = method_commands.add_parser("show")
    method_show.add_argument("method_id")
    method_show.add_argument("--version", type=int)
    for action in ("approve", "reject"):
        review = method_commands.add_parser(action)
        review.add_argument("method_id")
        review.add_argument("--version", type=int, required=True)
    edit = method_commands.add_parser("edit", help="Create an unreviewed version from a JSON file.")
    edit.add_argument("method_id")
    edit.add_argument("--file", type=Path, required=True)
    extract = method_commands.add_parser("extract", help="Send selected source passages to StepFun for candidate extraction.")
    extract.add_argument("section_id")
    extract.add_argument("--offset", type=int, default=0)
    extract.add_argument("--allow-remote", action="store_true", required=True)
    args = parser.parse_args(argv)
    model_dir = args.data_dir / "models" / "all-MiniLM-L6-v2"
    try:
        if args.command == "fetch-model":
            fetch_model(model_dir)
            result = {"status": "model_ready"}
        else:
            index = KnowledgeIndex(args.data_dir / "library.sqlite3", LocalMiniLM(model_dir))
            if args.command == "add":
                result = index.add(args.file, contextual=args.contextual)
            elif args.command == "list":
                result = index.list_documents()
            elif args.command == "remove":
                result = {"removed": index.remove(args.document_id)}
            elif args.command == "search":
                result = [asdict(item) for item in index.search(args.query, limit=args.limit,
                          document_id=args.document_id, chapter_id=args.chapter_id)]
            elif args.command == "open-section":
                result = [asdict(item) for item in index.open_section(args.section_id, limit=args.limit, offset=args.offset)]
            else:
                registry = MethodRegistry(index)
                if args.method_command == "list":
                    result = registry.list(document_id=args.document_id)
                elif args.method_command == "show":
                    result = registry.get(args.method_id, args.version)
                elif args.method_command in {"approve", "reject"}:
                    status = "approved" if args.method_command == "approve" else "rejected"
                    result = registry.review(args.method_id, args.version, status)
                elif args.method_command == "edit":
                    if args.file.stat().st_size > 256 * 1024:
                        raise ValueError("The method JSON file exceeds 256 KiB.")
                    candidate = MethodCandidate.model_validate_json(args.file.read_text(encoding="utf-8"))
                    result = registry.save_candidate(candidate, method_id=args.method_id)
                else:
                    from openai import AsyncOpenAI
                    from app.config import MODEL_TIMEOUT_SECONDS, Settings
                    from app.knowledge.method_extraction import extract_candidates
                    settings = Settings()
                    if settings.assistant_provider != "stepfun" or settings.stepfun_api_key is None:
                        raise ValueError("Configure the StepFun provider and its API key before extraction.")
                    async def run_extraction():
                        async with AsyncOpenAI(api_key=settings.stepfun_api_key.get_secret_value(),
                                base_url=settings.effective_stepfun_base_url, timeout=MODEL_TIMEOUT_SECONDS,
                                max_retries=0) as client:
                            return await extract_candidates(client, settings, index, args.section_id, offset=args.offset)
                    result = asyncio.run(run_extraction())
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except ValidationError:
        print("Knowledge operation failed: invalid method data.", file=sys.stderr)
        return 1
    except (ExtractionError, ValueError, RuntimeError, OSError, ImportError) as exc:
        print(f"Knowledge operation failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
