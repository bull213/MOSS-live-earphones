# Knowledge evaluation

Build a private, human-reviewed set of tasks and reference passage, section and
approved method IDs. Include indirect activation, inappropriate activation,
missing inputs, combinations, conflicts, and requests unrelated to the library.
Use [backend setup](../../backend/README.md#retrieval-evaluation) to run local
Recall@K, evidence completeness, reference resolution and latency measurements.
Record the index, embedding and prompt versions with each result. A retrievable
reference is not proof that a generated claim is supported by that reference.

For answer-quality assessment, independently review each task's expected steps
and evidence before seeing model answers. With separate authorization for paid
requests, compare the same Step 5 model and task set using no retrieval,
ordinary hybrid retrieval, and hybrid retrieval plus approved methods. Grade
method selection, inappropriate activation, procedural adherence, required
inputs, conflicts, source fidelity and usefulness by hand. Record unsupported
claims, source accuracy, timings, token counts and estimated costs for every
attempt, including failures. An LLM judge may assist but cannot replace review.
The current application does not yet provide a dedicated automated three-mode
comparison runner, and no live comparison results are claimed.

Compare contextual and plain embeddings by reindexing the same sources and
running identical reviewed cases. Consider reranking or other infrastructure
only when a measured result justifies it. Keep private source material, cases,
and outputs under the repository's ignored private fixture/results directories.
