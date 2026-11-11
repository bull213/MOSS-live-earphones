# Backend and local document index

The backend exposes the OpenAI-compatible photo route, the configured
`POST /v1/assistant/turns` route, the local knowledge library, and the MOSS Live
WebSocket gateway. The simulated demo route has been removed. Android uses its
normal assistant by default; Live AI is an explicit mode.

## MOSS Live realtime gateway

The Android **Start MOSS Live** control uses a separate foreground-only
WebSocket route: Android connects to
`ws://127.0.0.1:8000/v1/realtime/moss-live`, and this FastAPI process connects
to the official `wss://api.mosi.cn/v1/realtime` endpoint. The backend attaches a
new session, applies short-step assistant instructions, waits for the session
configuration confirmation, then relays bounded JSON and binary messages.
Credentials never go to Android. Without `MOSS_API_KEY`, it sends a safe
`moss_not_configured` error and closes the WebSocket; ordinary REST routes are
unaffected.

For USB use on Windows, connect and unlock the phone, then double-click
`Start MOSS Live.cmd` in the project root. It restores `adb reverse` and starts
the local backend if needed, then opens the app. Tap **Start MOSS Live** on the
phone to begin a provider session. USB reconnections can remove the ADB port
forward, so run the launcher again after reconnecting.

The current model default is the stable `MOSS_MODEL=moss-live-1.0`. A live
check authenticated the configured key, attached a session, and applied the
session configuration with this model, then closed without sending media.
This confirms realtime account access but does not validate audio/media behavior
or guarantee billing. Use the stable model ID. To
configure the key locally, copy `backend/.env.example` to `backend/.env` only if `.env`
does not already exist, then edit `MOSS_API_KEY` in that ignored local file.
Never paste the key into source, chat, shell command arguments, screenshots or
logs, and never commit `.env`. Restart the backend after configuration. The
development backend must remain bound to `127.0.0.1`; use the existing
`adb reverse tcp:8000 tcp:8000` setup from the Android README. Do not expose
this unauthenticated development endpoint publicly.

MOSS input is streamed 16 kHz mono PCM S16LE and response audio is 48 kHz mono
PCM S16LE. The backend forwards provider audio/video binary frames without
logging their contents. It bounds message sizes, applies WebSocket queue
limits, and closes both sides when either session ends. The Android build
currently enables voice only; camera streaming is not available yet because the
exact current `media.mode` event payload has not been independently verified.

An upstream handshake-only check accepted the stable model; it did not attach
a session or test media relay. Automated tests cover configuration, protocol
envelopes, safe missing-key behavior, and session lifecycle without simulating
MOSS. Starting MOSS Live attaches a real provider session and may incur
charges. MOSS sends microphone audio to the cloud while active; closing the
Android session stops microphone capture and closes the backend upstream
socket. Do not start a provider session as part of routine build validation.
The offline backend suite passes 148 tests with one opt-in local-embedding test
skipped; no test creates a MOSS session.

## Windows setup

Open the project in PyCharm and use its configured Python 3.13 environment.
From the repository root, install the backend requirements:

```powershell
& .\.venv\Scripts\python.exe -m pip install -r .\backend\requirements-dev.txt
```

For local book indexing, install the optional knowledge requirements too:

```powershell
& .\.venv\Scripts\python.exe -m pip install -r .\backend\requirements-knowledge.txt
```

If `backend/.env` does not already exist, copy `.env.example` to `.env` and
edit it locally. To use StepFun, set `ASSISTANT_PROVIDER=stepfun`, supply
`STEPFUN_API_KEY`, and select `STEPFUN_REGION=global` or `cn` for the key's
region. `STEPFUN_BASE_URL` is an optional HTTPS override. The key remains on
the backend. OpenAI remains the default provider.

From `backend`, start the development server on loopback:

```powershell
cd backend
& ..\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

With the server configured and the operator's authorization for a paid call,
a text request can be sent with:

```powershell
curl.exe -F "question=Explain this task" http://127.0.0.1:8000/v1/assistant/turns
```

Add `-F "image=@C:\path\photo.jpg;type=image/jpeg"` for a photograph.
`/v1/turns` continues to require a photo. The simulated `/v1/demo/turns` route
is no longer part of the application.
The new text-only request currently requires the StepFun provider. Real StepFun
service compatibility and answer quality have not yet been verified with a key.

For knowledge-assisted requests, also set `STEPFUN_INPUT_USD_PER_MILLION` and
`STEPFUN_OUTPUT_USD_PER_MILLION` to the current prices for the key's region.
`AGENT_MAX_ESTIMATED_USD` sets a per-request estimate limit, not a guaranteed
billing ceiling. Without prices, knowledge-assisted requests fail closed; direct
visual assistance remains available. `library_mode` accepts `auto`, `use`, or
`avoid`, and optional `history_json` accepts up to eight user/assistant messages
and 4,000 characters. The response includes resolvable `sources`,
`applied_methods`, and a `retrieval_status` in addition to `reply` and `action`.

The agent sends relevant indexed passages and approved methods to StepFun when
library retrieval is used. Only reviewed, non-stale methods are selected. Its
tools have read-only access to the local index and bounded rounds, calls,
passages, elapsed time, and estimated costs. If a client disconnects, the
provider request is cancelled; a local worker thread already running may finish
its bounded read in the background. Do not expose the development server to
the public without authentication, TLS, rate limiting, and user isolation.

## Local document index

From `backend`, explicitly download the pinned MiniLM embedding model once:

```powershell
& ..\.venv\Scripts\python.exe -m app.knowledge.cli fetch-model
```

This contacts the model host but does not send book content. The model weights
are checked against their pinned SHA-256; loading and indexing use local files.
Afterward, the following commands do not require an AI API key:

```powershell
& ..\.venv\Scripts\python.exe -m app.knowledge.cli add 'C:\Books\example.pdf'
& ..\.venv\Scripts\python.exe -m app.knowledge.cli list
& ..\.venv\Scripts\python.exe -m app.knowledge.cli search 'customer awareness'
& ..\.venv\Scripts\python.exe -m app.knowledge.cli open-section SECTION_ID
& ..\.venv\Scripts\python.exe -m app.knowledge.cli remove DOCUMENT_ID --yes
```

The `add` command supports PDF, EPUB, UTF-8 TXT, and a single page image
(PNG/JPEG/WebP/TIFF). Use `--contextual` to include source-derived chapter
information in embeddings. For PDFs, embedded text is assessed page by page;
pages with absent, sparse, or visibly broken text are rendered for local OCR.
Good native text is retained. A scanned page is not indexed silently if no OCR
provider is available.

OCR is optional and runs during local ingestion, outside the FastAPI request
path. Install the optional Python provider and PDF renderer with:

```powershell
& ..\.venv\Scripts\python.exe -m pip install -r .\requirements-ocr.txt
```

Alternatively, install Tesseract with the English language data and set
`TESSERACT_CMD` if its executable is not on `PATH`. `OCR_PROVIDER=auto` (the
default) prefers Tesseract when available, then PaddleOCR. Set
`OCR_PROVIDER=tesseract` or `OCR_PROVIDER=paddleocr` to select one explicitly.
PaddleOCR loads its English model on first use; model downloads may occur unless
the model has already been cached. For offline operation, prepare and verify
that model before importing private documents.

Unlimited-OCR 2 can be selected with `OCR_PROVIDER=unlimited-ocr-2` when a
separately installed SGLang server is already running on loopback. Set
`UNLIMITED_OCR_ENDPOINT` (default
`http://127.0.0.1:10000/v1/chat/completions`) and
`UNLIMITED_OCR_MODEL_REVISION` to the pinned model revision. The adapter sends
page images only to localhost, groups pages into windows of at most eight, and
requires `UNLIMITED_OCR_NGRAM_PROCESSOR` to be set to the serialized
`DeepseekOCRNoRepeatNGramLogitProcessor.to_str()` value from that pinned SGLang
environment; it also sends the repository's 35-token repetition settings and
requires one `<PAGE>` output per input page. The worker does not download Baidu
weights or start the model server. Baidu's documented serving environment is
separate from the backend environment; review and pin its model code, weights,
serving wheel, and dependencies before use. Its page-window results are
experimental until evaluated against the project's book corpus.

Physical PDF page numbers and per-page native/OCR provenance are stored with
the index. OCR model/provider fingerprints participate in reindex detection.
The worker has bounded input/output, memory and wall-time limits; temporary
rendered images are removed after each job. OCR warnings are retained in the
document record. Re-adding a changed source builds replacement passages before
the SQLite transaction, leaving the prior index active if extraction or
embedding fails. Removing a document removes derived index data, not the
original file.

### Importing a book from Android

The Android app uses the bundled Google ML Kit Latin text recognizer for PDF
pages that the backend finds empty, sparse, or broken. The phone inspects and
recognizes those pages locally. It sends the selected PDF and resulting page
text to this backend over the existing loopback/ADB connection; no Baidu or
other OCR model is downloaded to the phone. Searchable pages keep their native
text. The backend commits the result through the same `KnowledgeIndex` used by
the CLI, stores the uploaded original under `.data/knowledge/sources/`, and
records physical page numbers and phone-OCR provenance.

The phone import endpoints are `/v1/knowledge/inspect` and
`/v1/knowledge/import`. They are intended for a backend bound to
`127.0.0.1:8000` and reachable over ADB reverse, not a public network. Staged
uploads expire after one hour; successful imports are idempotent during that
window. The backend still needs the local MiniLM model ready before it can
index a book. A failed embedding/index operation leaves the existing index
active. Failed uploads stay in private staging until expiration; select the PDF
again to start a fresh import after an error.

The index, source passages, and model files are private local data under ignored
`backend/.data/knowledge/`; protect and back up that directory as needed. The
CLI is single-owner. The loopback development server does not provide production
authentication, TLS, or multi-user isolation.

## Extract and review methods

First use `search` or `open-section` to find the relevant section ID. With the
StepFun provider and key configured, explicitly authorize extraction of up to
12 passages from that section:

```powershell
& ..\.venv\Scripts\python.exe -m app.knowledge.cli methods extract SECTION_ID --allow-remote
& ..\.venv\Scripts\python.exe -m app.knowledge.cli methods list
& ..\.venv\Scripts\python.exe -m app.knowledge.cli methods show METHOD_ID --version 1
```

Extraction sends the selected source passages to StepFun and may incur charges.
Use `--offset` to choose a later passage window in a long section. Candidates
are unreviewed: the backend verifies that cited passages exist, but only a human
review can establish whether a candidate faithfully reflects its source.
Review the candidate against the passages, then explicitly approve or reject it:

```powershell
& ..\.venv\Scripts\python.exe -m app.knowledge.cli methods approve METHOD_ID --version 1
& ..\.venv\Scripts\python.exe -m app.knowledge.cli methods reject METHOD_ID --version 1
```

To edit, save the `method` object from `show` as a UTF-8 JSON file, modify it,
then run `methods edit METHOD_ID --file C:\path\candidate.json`. This creates a
new unreviewed version and does not replace an already approved version. If a
source passage changes on reindexing, dependent versions are marked stale and
are excluded from normal selection. Removing a source removes its method records.

## Tests

From `backend`, run offline tests with:

```powershell
& ..\.venv\Scripts\python.exe -m pytest -q
```

The real local embedding check is opt-in and requires the downloaded model:

```powershell
$env:HF_HUB_OFFLINE = '1'
$env:TRANSFORMERS_OFFLINE = '1'
$env:RUN_LOCAL_EMBEDDING_TESTS = '1'
& ..\.venv\Scripts\python.exe -m pytest -q tests/test_knowledge_index.py
```

Mocked tests do not establish real-model accuracy or prove that the StepFun
service accepts the configured account and region.

## Retrieval evaluation

Create a UTF-8 JSON array with independently reviewed references, for example:

```json
[{"id":"headline-1","query":"Write an advertising headline",
  "passage_ids":["PASSAGE_ID"],"section_ids":["SECTION_ID"],"method_ids":["METHOD_ID"]}]
```

Use the CLI's `search`, `open-section`, and `methods show` to obtain and review
the IDs. Store private cases under ignored `evaluation/fixtures/private/`.
From `backend`, run the evaluation without contacting a model provider:

```powershell
& ..\.venv\Scripts\python.exe -m app.knowledge.evaluation ..\evaluation\fixtures\private\retrieval.json --k 5
```

It reports passage, section, and approved-method recall, evidence completeness,
and retrieval latency. Empty reference lists are excluded from the corresponding
metric. Compare contextual embeddings by reindexing the same documents with
`add FILE --contextual` and repeating the same cases. Synthetic fixtures verify
the mechanics; expert-reviewed real passages and authorized live comparisons
are still needed to evaluate answer quality, source fidelity, and costs.
