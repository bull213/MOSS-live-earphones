# Android Knowledge Ingestion Subsystem

## Overview
Coordinates document scanning, on-device OCR using Google ML Kit, and multi-page upload pipelines transmitting physical book and manual captures to the local backend for indexing.

## Interface
- `PhoneBookImporter` (`PhoneBookImporter.kt`): Primary orchestrator handling page capture sequencing, staging, and upload batching.
- `AndroidPdfOcr` (`AndroidPdfOcr.kt`): On-device ML Kit text recognition extracting text from document frames.
- `PhoneBookImportApi` (`PhoneBookImportApi.kt`): Retrofit client communicating with `/v1/knowledge/books/import`.

## Invariants
- **Local Network Only**: Transmits document uploads strictly to the loopback development backend (`127.0.0.1:8000`), never directly to third-party cloud services.
- **Ordered Paging**: Pages are indexed sequentially; multi-page uploads preserve page numbering and document metadata.

## Side Effects
- Reads captured document images from cache.
- Sends multipart HTTP POST requests to the local backend.

## Verification
- Run: `.\android\gradlew.bat -p android testDebugUnitTest`
