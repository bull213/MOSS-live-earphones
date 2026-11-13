"""Natural language processing and speech adaptation utilities for Google NotebookLM."""

from __future__ import annotations

import difflib
import re
from typing import Any

# Common words to exclude when scoring distinctive keyword overlap for notebook matching
NOTEBOOK_STOP_WORDS = {
    "the", "and", "for", "with", "from", "that", "this", "what", "which",
    "tell", "about", "check", "open", "access", "enter", "read", "notes",
    "notebook", "notebooks", "please", "know", "have", "look", "search",
    "into", "your", "mine", "some", "first", "second", "show", "help",
    "can", "could", "would", "will", "shall", "should", "may", "might", "must",
    "my", "you", "they", "them", "all", "any", "are", "was", "were", "been",
    "does", "doing", "done", "give", "want", "like", "find",
    "keyword", "keywords", "command", "commands", "phrase", "phrases",
    "switch", "switching", "how", "words", "word",
    "את", "אתה", "יכול", "לבדוק", "בבקשה", "מחברת", "מחברות", "על", "מה",
    "איך", "למה", "מי", "של", "לי", "לך", "כל", "הכל", "האם",
    "פקודה", "פקודות", "מילות", "מילת", "מפתח",
}

META_INQUIRY_PATTERNS = [
    r"\b(can you|do you|are you able to)\b.{0,30}\b(access|open|read|see|view|use)\b.{0,20}\bnotebooks?\b",
    r"\b(what|which)\b.{0,30}\bnotebooks?\b.{0,30}\b(have|got|exist|available)\b",
    r"\b(list|show|tell me)\b.{0,20}\b(all|my)?\b.{0,20}\bnotebooks?\b",
    r"^\s*(can you access my notebooks|do you have access to my notebooks|what notebooks do i have|list my notebooks)\s*[\?\.]*\s*$",
    r"\b(keyword|keywords|command|commands|phrase|phrases|words?)\b.{0,30}\b(open|access|use|switch|select)\b.{0,30}\bnotebooks?\b",
    r"\b(how|what)\b.{0,30}\b(do i|can i|to)\b.{0,30}\b(open|access|use|switch|switch to)\b.{0,20}\bnotebooks?\b",
    r"\b(what are the keywords|how do i open|how to open|how do i switch)\b.{0,30}\bnotebooks?\b",
    r"(?:האם|אתה|יכול).{0,30}(?:לגשת|לראות|לפתוח|לבדוק).{0,20}(?:ל|ב|מ|כ|ש|ה)?מחבר(?:ת|ות)",
    r"(?:אילו|איזה|רשימת|הצג|תראה).{0,30}(?:ל|ב|מ|כ|ש|ה)?מחבר(?:ת|ות)",
    r"(?:מה|איך|אילו).{0,30}(?:מיל(?:ות|ת)\s+(?:ה)?מפתח|פקוד(?:ה|ות)).{0,30}מחבר(?:ת|ות)",
    r"(?:איך|כיצד)\s+(?:אפשר\s+)?(?:לפתוח|לגשת|לעבור).{0,20}מחבר(?:ת|ות)",
]


def parse_explicit_notebook_target(question: str) -> tuple[str | None, str]:
    """
    Parse an explicit NotebookLM target from question text.
    Returns (target_name, cleaned_query).

    Supported formats:
    - Suffix @tag: "What is the fun criterion? @Reason is fun"
    - Prefix @"Quoted Title": @"Reason is fun" What is the fun criterion?
    - Prefix @tag: @Reason What is the fun criterion?
    - Prefix notebook: notebook: Reason is fun: What is the fun criterion?
    """
    if not question:
        return None, question
    q = question.strip()

    # 1. Suffix @tag: "What is the fun criterion? @Reason is fun"
    m_suffix = re.search(r"\s+@(?:\"([^\"]+)\"|'([^']+)'|([a-zA-Z0-9_\-\s]+))\s*$", q)
    if m_suffix:
        target = (m_suffix.group(1) or m_suffix.group(2) or m_suffix.group(3)).strip()
        cleaned = q[:m_suffix.start()].strip()
        if target and cleaned:
            return target, cleaned

    # 2. Prefix @tag:
    # 2a. Quoted title: @"Reason is fun": Query or @"Reason is fun" Query
    m_quoted = re.match(r"^\s*@(?:\"([^\"]+)\"|'([^']+)')(?:\s*[:,\-]\s*|\s+)(.+)$", q, re.DOTALL)
    if m_quoted:
        target = (m_quoted.group(1) or m_quoted.group(2)).strip()
        cleaned = m_quoted.group(3).strip()
        return target, cleaned

    # 2b. Unquoted title prefix: @Reason: Query or @Reason is fun: Query (colon-terminated)
    m_colon = re.match(r"^\s*@([a-zA-Z0-9_\-\s]+)\s*:\s*(.+)$", q, re.DOTALL)
    if m_colon:
        target = m_colon.group(1).strip()
        cleaned = m_colon.group(2).strip()
        return target, cleaned

    # 2c. Bare prefix tag before question: @Reason What is the fun criterion?
    m_bare = re.match(r"^\s*@([a-zA-Z0-9_\-]+)\s+(.+)$", q, re.DOTALL)
    if m_bare:
        target = m_bare.group(1).strip()
        cleaned = m_bare.group(2).strip()
        return target, cleaned

    # 3. Explicit key-value: "notebook: Reason is fun: What is the fun criterion?"
    m_kv = re.match(
        r"^\s*(?:notebook|manual)\s*[:=]\s*(?:\"([^\"]+)\"|'([^']+)'|([^,:\n]+?))\s*[:,\-]\s*(.+)$",
        q,
        re.IGNORECASE | re.DOTALL,
    )
    if m_kv:
        target = (m_kv.group(1) or m_kv.group(2) or m_kv.group(3)).strip()
        cleaned = m_kv.group(4).strip()
        if target:
            return target, cleaned

    # 4. Natural phrasing: "in notebook Reason is fun: What is the fun criterion?"
    m_nat = re.match(
        r"^\s*(?:in|from|ask|using|according to|check)\s+(?:the\s+)?(?:notebook\s+|manual\s+)?(?:\"([^\"]+)\"|'([^']+)'|([a-zA-Z0-9_\-\s]+?))\s*(?:notebook|manual)?\s*(?:[:,\-]\s*|\s+to\s+|\s+about\s+|\s*,\s*)(.+)$",
        q,
        re.IGNORECASE | re.DOTALL,
    )
    if m_nat:
        target = (m_nat.group(1) or m_nat.group(2) or m_nat.group(3)).strip()
        cleaned = m_nat.group(4).strip()
        if target:
            return target, cleaned

    return None, q


# Backwards compatibility alias
explicit_notebook_target = parse_explicit_notebook_target


def is_meta_notebook_inquiry(text: str) -> bool:
    """Check if the user is asking about notebook capability or listing rather than querying."""
    if not text:
        return False
    text_lower = text.lower()
    return any(re.search(pattern, text_lower, re.IGNORECASE) for pattern in META_INQUIRY_PATTERNS)


def tokens_match_fuzzy(t: str, nt: str) -> bool:
    """Check if token t matches notebook token nt with fuzzy stem or typo tolerance."""
    if t == nt:
        return True
    if len(t) >= 4 and len(nt) >= 4:
        # Prefix/stem match: e.g. "network" / "networks", "advertise" / "advertising"
        if (t.startswith(nt) or nt.startswith(t)) and abs(len(t) - len(nt)) <= 3:
            return True
        # Fuzzy similarity for typos or minor misspellings
        if difflib.SequenceMatcher(None, t, nt).ratio() >= 0.80:
            return True
    return False


def match_notebook_from_speech(
    user_text: str,
    notebooks: list[dict[str, Any]],
    current_notebook: dict[str, Any] | None = None,
    *,
    active_notebook: dict[str, Any] | None = None,
) -> tuple[dict[str, Any] | None, str]:
    """
    Intelligently match a notebook from spoken audio transcripts.
    Supports inexact/partial wording and flags _needs_confirmation when confidence is not certain.
    Returns (matched_notebook, cleaned_query).
    """
    if not user_text:
        return None, user_text

    target_active = active_notebook if active_notebook is not None else current_notebook
    text_lower = user_text.lower()

    # 0. Meta-inquiry check: asking about access capability or asking to list notebooks
    if is_meta_notebook_inquiry(text_lower):
        return None, user_text

    # 1. Explicit target pattern e.g. "@Reason is fun ...", "notebook: Reason is fun: ..."
    target_name, stripped_query = parse_explicit_notebook_target(user_text)
    if target_name:
        for nb in notebooks:
            if nb.get("title", "").strip().lower() == target_name.lower():
                return nb, stripped_query or user_text
        for nb in notebooks:
            if target_name.lower() in nb.get("title", "").strip().lower():
                return nb, stripped_query or user_text

    # 2. Exact or substring title match (for notebook titles with >= 4 characters)
    sorted_notebooks = sorted(notebooks, key=lambda x: len(x.get("title", "")), reverse=True)
    for nb in sorted_notebooks:
        title = nb.get("title", "").strip().lower()
        if len(title) >= 4 and title in text_lower:
            return nb, user_text

    # 3. Distinctive keyword & fuzzy phrase matching
    clean_tokens = [
        w for w in re.findall(r"[\w\u0590-\u05fe]+", text_lower)
        if len(w) >= 3 and w not in NOTEBOOK_STOP_WORDS
    ]

    if clean_tokens:
        candidates: list[tuple[float, int, dict[str, Any]]] = []
        for nb in sorted_notebooks:
            nb_title = nb.get("title", "").strip()
            nb_tokens = [
                w for w in re.findall(r"[\w\u0590-\u05fe]+", nb_title.lower())
                if len(w) >= 3 and w not in NOTEBOOK_STOP_WORDS
            ]
            if not nb_tokens:
                continue

            matched = [t for t in clean_tokens if any(tokens_match_fuzzy(t, nt) for nt in nb_tokens)]
            overlap_count = len(matched)
            if overlap_count == 0:
                # Also check sequence matcher against full title for fuzzy phrasing
                ratio = difflib.SequenceMatcher(None, " ".join(clean_tokens), nb_title.lower()).ratio()
                if ratio >= 0.65:
                    candidates.append((ratio, 1, nb))
                continue

            coverage = overlap_count / len(nb_tokens)
            score = overlap_count * 1.5 + coverage
            candidates.append((score, overlap_count, nb))

        if candidates:
            # Sort by score descending
            candidates.sort(key=lambda c: c[0], reverse=True)
            best_score, best_overlap, best_nb = candidates[0]

            # Check for ambiguity: does another notebook have a close score?
            is_ambiguous = len(candidates) > 1 and (best_score - candidates[1][0] < 0.5)

            # Determine if confidence is high or requires confirmation
            best_nb_tokens = [
                w for w in re.findall(r"[\w\u0590-\u05fe]+", best_nb.get("title", "").lower())
                if len(w) >= 3 and w not in NOTEBOOK_STOP_WORDS
            ]
            total_nb_tokens = len(best_nb_tokens)

            # High confidence: multiple matching tokens or high coverage, with no close tie
            high_confidence = (
                not is_ambiguous
                and (
                    best_overlap >= 2
                    or (total_nb_tokens <= 1 and best_overlap >= 1)
                    or (best_overlap / max(total_nb_tokens, 1) >= 0.5)
                )
            )

            if high_confidence:
                return best_nb, user_text

            # Medium confidence / inexact match / ambiguous -> ask for confirmation
            result_nb = dict(best_nb)
            result_nb["_needs_confirmation"] = True
            if is_ambiguous:
                second_nb = candidates[1][2]
                result_nb["_confirmation_prompt"] = (
                    f"Did you mean '{best_nb.get('title')}' or '{second_nb.get('title')}'?"
                )
            else:
                result_nb["_confirmation_prompt"] = (
                    f"Did you mean the '{best_nb.get('title')}' notebook?"
                )
            return result_nb, user_text

    # 4. Contextual follow-up referencing the previously active notebook
    if target_active is not None:
        pronoun_match = re.search(r"\b(it|that|this|the notebook|the notes|זה|זאת|המחברת)\b", text_lower)
        question_words = ["what", "how", "why", "who", "tell", "explain", "summarize", "read", "check", "מה", "איך", "למה", "מי", "תסביר", "ספר"]
        if pronoun_match or any(q in text_lower for q in question_words):
            return target_active, user_text

    return None, user_text


def sanitize_grounded_voice_summary(text: str, max_chars: int = 800) -> str:
    """
    Sanitize and bound NotebookLM research output for live speech injection.
    - Strips citation footnotes ([1], [1, 2], [3-5]).
    - Strips markdown headers (# Header).
    - Strips markdown formatting (**, __, `, >).
    - Strips emojis.
    - Truncates to a clean sentence boundary within max_chars.
    """
    if not text:
        return ""
    # 1. Remove bracketed citation markers e.g. [1], [1, 2], [3-5]
    cleaned = re.sub(r"\s*\[[\d,\s\-]+\]", "", text)
    # 2. Remove markdown headers (# Header)
    cleaned = re.sub(r"^\s*#{1,6}\s+.*$", "", cleaned, flags=re.MULTILINE)
    # 3. Remove markdown links [text](url) -> text
    cleaned = re.sub(r"\[([^\]]+)\]\([^\)]+\)", r"\1", cleaned)
    # 4. Remove emojis and markdown formatting asterisks/underscores/backticks
    cleaned = re.sub(r"[\u2600-\u27bf\U0001f300-\U0001f9ff]", "", cleaned)
    cleaned = re.sub(r"[*_`>~]", "", cleaned)
    # 5. Collapse multiple whitespace and newlines
    cleaned = re.sub(r"\n+", " ", cleaned)
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    if len(cleaned) <= max_chars:
        return cleaned
    truncated = cleaned[:max_chars]
    # Find last sentence-ending punctuation (. or ! or ?)
    match = re.search(r"[\.\!\?](?=\s|$)[^\.\!\?]*$", truncated)
    if match and match.start() > max_chars // 3:
        return truncated[:match.start() + 1].strip()
    return truncated.strip()


def build_moss_live_notebook_instructions(
    base_instructions: str = "",
    titles: list[str] | list[dict[str, Any]] | None = None,
) -> str:
    """Compose dynamic MOSS Live system instructions with NotebookLM cloud integration."""
    if isinstance(base_instructions, list) and titles is None:
        titles = base_instructions
        base_instructions = ""

    title_strings: list[str] = []
    if titles:
        for item in titles:
            if isinstance(item, dict):
                t = item.get("title")
                if t:
                    title_strings.append(str(t))
            elif isinstance(item, str):
                title_strings.append(item)

    if not title_strings:
        if base_instructions:
            return base_instructions
        return (
            "[DIRECT GOOGLE NOTEBOOKLM CLOUD INTEGRATION]\n"
            "You are connected to the user's Google NotebookLM account. Currently no notebooks were found."
        )

    notebook_list = "\n".join(f"- {title}" for title in title_strings[:40])
    header = f"{base_instructions}\n\n" if base_instructions else ""
    return (
        f"{header}"
        "[DIRECT GOOGLE NOTEBOOKLM CLOUD INTEGRATION]\n"
        "You are directly connected in the cloud to the user's Google NotebookLM account. "
        "The user's account contains these cloud notebooks:\n"
        f"{notebook_list}\n\n"
        "CRITICAL RULES FOR NOTEBOOKS:\n"
        "1. These notebooks are stored entirely in Google's cloud. They are NOT open apps, files, or icons on the user's phone screen. "
        "The user does NOT have them on their screen and CANNOT tap or click them. NEVER ask or tell the user to click, tap, open, or enter a notebook on their screen; do NOT say you lack access.\n"
        "2. You and your backend have direct background cloud access to read and search all of these notebooks automatically.\n"
        "3. When the user asks whether you can access their notebooks or asks you to open or access them (e.g. 'Can you access my notebooks?', 'Open Reason is fun', 'Check my notes'):\n"
        "   - If they have not named a specific notebook yet, reply: 'Yes, I have direct cloud access to all your notebooks, including Reason is fun, AI Wearables for Older Adults, and others. Which one would you like me to check?'\n"
        "   - If they named a notebook or topic, acknowledge immediately: 'I am accessing your [Notebook Title] notebook now in the cloud. One moment while I retrieve that information.'\n"
        "4. When the user asks a question about any notebook or topic, say: 'Checking your [Notebook Title] notebook now. One moment please.' "
        "Then, as soon as the grounded information arrives in this conversation (prefixed with [Grounded Knowledge from Notebook ...]), summarize it aloud to the user right now in 1-2 conversational sentences."
    )
