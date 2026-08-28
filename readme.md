# SmartExtract

SmartExtract is a document-processing application that extracts structured Purchase Order (PO) information from uploaded documents using Google Gemini.

## What it does

- Upload PO documents such as PDF/DOCX.
- Extract text from PDFs using **Apache PDFBox** and from DOC/DOCX files using **Apache POI**.
- Use Gemini for AI-based PO field extraction.
- Validate the extracted data on the server.
- Persist PO information and processing status in PostgreSQL.
- Track processing outcomes such as **Completed**, **Needs Review**, and **Failed**.
- Retry transient AI/external-service failures with bounded retries, exponential backoff, and jitter.
- Provide a UI to view processed documents and retry eligible failures.

## Architecture

```text
Frontend
   │
   ▼
Spring Boot REST API
   │
   ▼
Document Service
   │
   ▼
Document Text Extraction
   │
   ├── PDF → Apache PDFBox
   └── DOC/DOCX → Apache POI
   │
   ▼
LLM Extraction Service
   │
   ▼
Gemini Call Executor
   │
   ▼
Google Gemini API
   │
   ▼
Validation
   │
   ▼
PostgreSQL
```

`GeminiCallExecutor` isolates Gemini-specific integration and handles timeout, retry, backoff, jitter, and error classification.

## Tech Stack

- **Backend:** Java, Spring Boot
- **Document processing:** Apache PDFBox, Apache POI
- **AI:** Google Gemini, Google GenAI Java SDK
- **Database:** PostgreSQL
- **Frontend:** Web UI
- **Testing:** JUnit / integration tests
- **Build:** Maven

## Processing Flow

```text
Upload document
      ↓
Identify document type
      ↓
Extract text
   ┌──┴──────────────┐
   │                 │
 PDF              DOC/DOCX
   │                 │
PDFBox             POI
   └───────┬─────────┘
           ↓
     Extracted text
           ↓
   Gemini / LLM extraction
           ↓
   Parse structured PO data
           ↓
      Validate data
           ↓
     Persist in PostgreSQL
           ↓
      Display status
```

## Document Text Extraction

The application separates **document parsing** from **AI extraction**.

- **Apache PDFBox** is used to extract text from PDF documents.
- **Apache POI** is used to extract text/content from Microsoft Word documents.
- The extracted text is then passed to the LLM extraction layer.
- Gemini is responsible for interpreting the extracted content and mapping it to the required PO fields.

This separation allows document-format handling to remain deterministic while Gemini is used only for the semantic extraction step.

## Error & Retry Strategy

Only transient failures are retried.

| Error | Retry |
|---|---|
| Timeout | Yes |
| 429 / rate limit | Yes |
| Appropriate 5xx errors | Yes |
| Temporary network failure | Yes |
| 400 / invalid request | No |
| 401 / invalid API key | No |
| 403 / permission error | No |

Retries use exponential backoff with jitter and are bounded to avoid repeatedly calling an unavailable dependency.

## Configuration

Configure the Gemini API key through environment/configuration management. Do not commit credentials to source control.

Example:

```properties
GEMINI_API_KEY=<your-api-key>
```

Use the project's existing configuration mechanism to map the environment variable to the application.

## Running Locally

### Prerequisites

- Java
- Maven
- PostgreSQL
- Gemini API key

### Start the application

```bash
mvn spring-boot:run
```

The application can then be accessed through the configured frontend/API endpoint.

## Testing

Run the test suite with:

```bash
mvn test
```

Important scenarios include successful extraction, validation failures, Gemini timeouts, transient 429/5xx failures, retry exhaustion, and non-retryable 4xx failures.

## Design Decisions

The major technology and architecture decisions, including alternatives considered and rejected, are documented in [`decision.md`](decision.md).
