"""Tests for NotebookLM NLP, fuzzy speech matching, and voice adaptation."""

from app.notebooklm.nlp import (
    build_moss_live_notebook_instructions,
    explicit_notebook_target,
    is_meta_notebook_inquiry,
    match_notebook_from_speech,
    parse_explicit_notebook_target,
    sanitize_grounded_voice_summary,
)


def test_parse_explicit_notebook_target():
    assert explicit_notebook_target("What is the fun criterion? @Reason is fun") == (
        "Reason is fun",
        "What is the fun criterion?",
    )
    assert parse_explicit_notebook_target('@"Reason is fun": What is it?') == (
        "Reason is fun",
        "What is it?",
    )
    assert parse_explicit_notebook_target("@washer: how to clean filter") == (
        "washer",
        "how to clean filter",
    )
    assert parse_explicit_notebook_target("notebook: Sugarman: tell me about chapter 1") == (
        "Sugarman",
        "tell me about chapter 1",
    )
    assert parse_explicit_notebook_target("in the notebook Hopfield: what is energy function?") == (
        "Hopfield",
        "what is energy function?",
    )
    assert parse_explicit_notebook_target("Just a normal question without tags") == (
        None,
        "Just a normal question without tags",
    )


def test_is_meta_notebook_inquiry():
    # True positives: user asking about capability/listing
    assert is_meta_notebook_inquiry("can you access my notebooks?") is True
    assert is_meta_notebook_inquiry("Do you have access to my notebooks?") is True
    assert is_meta_notebook_inquiry("What notebooks do I have?") is True
    assert is_meta_notebook_inquiry("List my notebooks please") is True
    assert is_meta_notebook_inquiry("Tell me what notebooks are available") is True
    assert is_meta_notebook_inquiry("What are the keywords to open a specific notebook?") is True
    assert is_meta_notebook_inquiry("what are the keywords to open notebook") is True
    assert is_meta_notebook_inquiry("how do I switch notebooks?") is True
    assert is_meta_notebook_inquiry("האם אתה יכול לגשת למחברות שלי?") is True
    assert is_meta_notebook_inquiry("מה מילות המפתח לפתוח מחברת?") is True

    # False positives: user asking about content
    assert is_meta_notebook_inquiry("Check Reason is fun. What is the fun criterion?") is False
    assert is_meta_notebook_inquiry("What does the Sugarman notebook say about advertising?") is False
    assert is_meta_notebook_inquiry("Can you check Hopfield Networks?") is False


def test_match_notebook_from_speech():
    notebooks = [
        {"id": "nb_1", "title": "Reason is fun"},
        {"id": "nb_2", "title": "HOPFIELD NETWORKS IS ALL YOU NEED"},
        {"id": "nb_3", "title": "Success Forces - sugarman"},
        {"id": "nb_4", "title": "Advertising Secrets of the Written Word - sugarman"},
    ]

    # Meta inquiries return None and do not trigger false positive matching
    nb, q = match_notebook_from_speech("Can you access my notebooks?", notebooks)
    assert nb is None
    assert q == "Can you access my notebooks?"

    nb, q = match_notebook_from_speech("What are the keywords to open a specific notebook?", notebooks)
    assert nb is None
    assert q == "What are the keywords to open a specific notebook?"

    # Substring / title match
    nb, q = match_notebook_from_speech("Check Reason is fun. What is the fun criterion?", notebooks)
    assert nb is not None
    assert nb["id"] == "nb_1"
    assert "fun criterion" in q

    # Inexact / partial title match without complete wording
    nb, q = match_notebook_from_speech("Can you check Advertising Secrets for me?", notebooks)
    assert nb is not None
    assert nb["id"] == "nb_4"
    assert not nb.get("_needs_confirmation", False)

    # Inexact with stem / typo tolerance ("Hopfield network" instead of "HOPFIELD NETWORKS IS ALL YOU NEED")
    nb, q = match_notebook_from_speech("What does Hopfield network say about associative memory?", notebooks)
    assert nb is not None
    assert nb["id"] == "nb_2"

    # Ambiguous match (both nb_3 and nb_4 share "sugarman" with single-token match)
    nb, q = match_notebook_from_speech("Can you check Sugarman for me?", notebooks)
    assert nb is not None
    assert nb.get("_needs_confirmation") is True
    assert "Did you mean" in nb.get("_confirmation_prompt", "")

    # Contextual follow-up when active notebook is provided
    nb, q = match_notebook_from_speech("What else does it say about that?", notebooks, active_notebook=notebooks[0])
    assert nb is not None
    assert nb["id"] == "nb_1"


def test_sanitize_grounded_voice_summary():
    raw_markdown = (
        "## Summary\n\n"
        "The **fun criterion** is a principle formulated by David Deutsch [1, 2]. "
        "It states that problem solving is fun when progress is made [3-5]! 💡🚀 "
        "Further details can be found in chapter 3 of the book."
    )
    sanitized = sanitize_grounded_voice_summary(raw_markdown, max_chars=120)

    assert "[1, 2]" not in sanitized
    assert "[3-5]" not in sanitized
    assert "**" not in sanitized
    assert "##" not in sanitized
    assert "💡" not in sanitized
    assert "🚀" not in sanitized
    assert len(sanitized) <= 120
    # Checks sentence boundary preservation
    assert sanitized.endswith((".", "!", "?"))


def test_build_moss_live_notebook_instructions():
    # Empty notebooks
    empty_prompt = build_moss_live_notebook_instructions([])
    assert "NotebookLM" in empty_prompt

    # With notebooks
    notebooks = [{"id": "1", "title": "Quantum Computing"}]
    prompt = build_moss_live_notebook_instructions(notebooks)
    assert "Quantum Computing" in prompt
    assert "do NOT say you lack access" in prompt
