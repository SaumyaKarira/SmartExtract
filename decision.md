# SmartExtract --- Technology & Architecture Decisions

## 1. Project Context

SmartExtract is a web application that uploads Purchase Order (PO)
documents, extracts structured PO data, validates the extracted values,
stores usable results, and allows users to search and export purchase
orders.

The repository uses a single repo with two applications:

``` text
smart-extract/
├── frontend/
└── backend/
```

------------------------------------------------------------------------

# Part A --- Technology Decisions

## 2. Frontend: React + TypeScript

### Chosen

-   React
-   TypeScript

### Why React

React was chosen because: - It is well suited to a component-based
application such as SmartExtract. - The application contains multiple
reusable UI elements: upload modal, dashboard cards, PO tables, status
badges, search/filter controls, detail views, etc. - It has a mature
ecosystem and strong industry adoption. - It is sufficient for the MVP
without introducing a larger framework than required.

### Alternatives considered

**Angular** - Full-featured and capable, but more opinionated and
heavier for this relatively small MVP. - More framework
structure/boilerplate than required.

**Vue** - A valid lightweight alternative, but React has broader
ecosystem and industry adoption for the intended project.

**Vanilla JavaScript** - Avoided because UI state, reusable components,
forms, filtering and multiple pages would require more manual DOM/state
management.

### Why TypeScript

TypeScript was chosen over plain JavaScript because the application
exchanges structured PO objects between frontend and backend.

Typed models make fields such as PO number, vendor, dates, totals, line
items and processing status safer to handle and reduce runtime mistakes.

------------------------------------------------------------------------

## 3. Backend: Java 21 + Spring Boot

### Chosen

-   Java 21
-   Spring Boot
-   Spring Data JPA / Hibernate
-   Maven

### Why Java

Java was selected because: - It is a mature backend ecosystem. - It
provides strong typing and a large ecosystem for REST APIs, persistence
and document processing. - It is a good fit for a structured business
application such as purchase-order processing. - The project can
implement the extraction, validation and persistence layers cleanly in
Java.

Spring Data JPA/Hibernate is used for persistence mapping,
and Flyway owns schema creation and evolution.

### Alternatives considered

**Python + FastAPI** - FastAPI is excellent for ML-heavy applications. -
SmartExtract primarily consumes an external LLM API rather than running
an ML model locally. - The extra Python/ML advantage therefore was not
necessary for this MVP.

**Node.js + Express/NestJS** - Both are capable backend choices. - They
would introduce a different backend ecosystem without providing a
specific benefit for this workload.

### Why Spring Boot

Spring Boot was chosen because it provides: - Straightforward REST API
development - Dependency injection - Configuration management - Database
integration - Validation and application structure - A mature
production-oriented Java ecosystem

It gives the project a clean layered architecture without requiring
custom framework infrastructure.

------------------------------------------------------------------------

## 4. Build Tool: Maven

### Chosen

Maven is used for the Java backend.

### Why

-   Mature and widely used in the Java ecosystem.
-   Simple dependency management.
-   Straightforward Spring Boot integration.
-   Familiar project structure and predictable builds.

Gradle was not selected because Maven was sufficient for the project's
size and did not require the additional flexibility of Gradle.

------------------------------------------------------------------------

## 5. Database: PostgreSQL

### Chosen

PostgreSQL is the primary relational database.

### Why PostgreSQL

PostgreSQL provides: - Strong relational modeling - Transactions -
Constraints - Reliable querying - JSONB support where structured
metadata benefits from it

The application also needs reliable filtering and querying by: - PO
number - Vendor - Date - Amount - Status

### Alternatives considered

**MongoDB** - Flexible document storage is attractive for AI-generated
JSON. - However, the application's core data is relational and has clear
relationships between documents, purchase orders and line items. -
PostgreSQL provides better fit for relational constraints, filtering and
joins.

**MySQL** - Also a valid relational choice. - PostgreSQL was preferred
for its strong SQL feature set and JSONB support, while remaining
straightforward for this project.

------------------------------------------------------------------------

## 6. Database Schema Management: Flyway

### Chosen

Flyway manages all database schema changes.

Migrations are stored under:

``` text
backend/src/main/resources/db/migration/
```

### Why Flyway

Flyway provides: - Versioned migrations - Reproducible schema creation -
Ordered migration execution - Migration history tracking - Consistent
local and deployment environments

The database schema therefore changes through explicit migration files
rather than being generated implicitly by the ORM.

### Rejected approach: Hibernate automatic schema creation/update

Hibernate/JPA schema auto-generation was rejected because: - Schema
changes become less explicit. - Production changes are harder to review
and reproduce. - Migration history is not represented as deliberate
versioned SQL.

**Decision:** JPA/Hibernate is used for persistence mapping, but Flyway
owns schema creation and evolution.

------------------------------------------------------------------------

## 7. Persistence: Spring Data JPA / Hibernate

### Chosen

Spring Data JPA with Hibernate.

### Why

It reduces boilerplate for: - Entity mapping - CRUD operations -
Repository access - Relationships - Transactional persistence

It fits naturally with the relational PostgreSQL model.

### Alternative considered: JDBC

Plain JDBC provides more direct SQL control, but would require more
repetitive database-access code for the CRUD/entity-heavy parts of this
application.

JPA was therefore preferred for maintainability and development speed.

------------------------------------------------------------------------

## 8. PDF Text Extraction: Apache PDFBox

### Chosen

Apache PDFBox is used to extract text from PDFs.

### Why

-   Mature Java PDF library.
-   Directly addresses the application's PDF text-extraction
    requirement.
-   Keeps basic text extraction local rather than requiring another
    external service.
-   Provides source text that can be passed to the AI extraction layer
    and displayed for diagnostics/review.

### Alternative considered: Apache Tika

Tika is broader and can detect/extract content from many file types. For
the focused PDF requirement, PDFBox provides more direct control.

------------------------------------------------------------------------

## 9. DOCX Extraction: Apache POI

### Chosen

Apache POI is used for DOCX text extraction.

### Why

-   Mature Java library for Microsoft Office formats.
-   Provides a local deterministic extraction step.
-   Fits directly into the existing Java/Spring Boot backend.

The parser layer therefore supports:

``` text
PDF  → PDFBox
DOCX → Apache POI
```

The extracted text is then supplied to Gemini for semantic structuring.

------------------------------------------------------------------------

## 10. AI Provider: Gemini API

### Chosen

Gemini is used for semantic PO extraction.

### Why

Purchase Orders can have different layouts, labels, table structures and
wording. A fixed parser alone cannot reliably map every layout into the
application's common PO model.

Gemini handles the semantic extraction while deterministic application
code performs validation afterward.

### Important design principle

Gemini is an **extraction component, not the final authority on
correctness**.

``` text
Document
   ↓
PDFBox / POI
   ↓
Gemini extraction
   ↓
Rule-based validation
   ↓
Final application decision
```

------------------------------------------------------------------------

## 11. Why the Architecture Uses Both Parsing and Gemini

PDFBox/POI and Gemini solve different problems.

### PDFBox / POI

Responsible for:

``` text
Binary document
      ↓
machine-readable source text
```

### Gemini

Responsible for:

``` text
unstructured source text
      ↓
semantic structured PO
```

### Validation code

Responsible for:

``` text
structured PO
      ↓
correctness checks
```

This separation prevents the AI model from being the only source of
truth and also makes failures easier to diagnose.

------------------------------------------------------------------------

## 12. Overall Technology Stack

  -----------------------------------------------------------------------
  Layer                   Technology              Primary reason
  ----------------------- ----------------------- -----------------------
  Frontend                React                   Component-based UI and
                                                  mature ecosystem

  Frontend language       TypeScript              Type safety for
                                                  structured application
                                                  data

  Backend                 Java 21                 Mature, strongly typed
                                                  backend platform

  Backend framework       Spring Boot             REST APIs, DI,
                                                  configuration and
                                                  ecosystem

  Build                   Maven                   Simple, mature Java
                                                  dependency/build
                                                  management

  ORM                     Spring Data JPA /       Reduces persistence
                          Hibernate               boilerplate

  Database                PostgreSQL              Strong relational model
                                                  and querying

  Schema migrations       Flyway                  Versioned, reproducible
                                                  database changes

  PDF parsing             Apache PDFBox           Direct PDF text
                                                  extraction

  DOCX parsing            Apache POI              Local DOCX text
                                                  extraction

  AI extraction           Gemini API              Semantic extraction
                                                  from variable PO
                                                  layouts
  -----------------------------------------------------------------------

------------------------------------------------------------------------

# Part B --- Functional & Architecture Decisions

## 13. Extraction and Validation Pipeline

The processing flow is:

``` text
Frontend upload
      ↓
Create document record
      ↓
PDFBox / Apache POI text extraction
      ↓
Gemini structured extraction
      ↓
Rule-based validation
      ↓
Correction / Needs Review / Completed
      ↓
PostgreSQL
      ↓
Frontend
```

------------------------------------------------------------------------

## 14. Rule-Based Validation

Validation is deterministic application logic performed after Gemini
extraction.

Checks include: - Required fields - PO number/vendor availability - Date
parsing - Quantity validity - Negative/invalid numeric values -
Line-item calculations - PO total consistency

For each line:

``` text
line total = quantity × unit price
```

The PO total is also checked against the sum of line-item totals.

------------------------------------------------------------------------

## 15. Validation Outcomes

### Completed

Used when extracted data passes validation.

### Completed with Corrections

Used when an incorrect value can be deterministically corrected with
high confidence.

Example:

``` text
Quantity = 5
Unit price = ₹50,000
Extracted line total = ₹200,000

Correct line total = ₹250,000
```

The correction is recorded in validation metadata.

### Needs Review

Used when the system cannot safely determine the correct value.

Examples: - Missing PO number - Missing vendor - Unparseable/ambiguous
date - Missing required value - Conflicting information

The structured extraction is still displayed so the user can review it.

### Principle

**Never invent a value merely to make validation pass.**

------------------------------------------------------------------------

## 16. Date Validation

The validator accepts reasonable common date formats rather than relying
on one exact format.

For example:

``` text
26 Aug 2026
```

should be recognized as a valid date.

A date that is genuinely ambiguous or cannot be safely parsed results in
`NEEDS_REVIEW`.

------------------------------------------------------------------------

## 17. Gemini Retry / Resilience

Gemini calls are synchronous in the current MVP.

The processing request is:

``` text
upload → extraction → Gemini → validation → DB
```

### Retry behavior

Transient failures are retried automatically.

Current design: - Per-attempt HTTP timeout - Up to 3 attempts -
Exponential backoff with jitter - Respect `Retry-After` where
available - Retry only transient failures - Permanent failures are not
retried

The HTTP client's own timeout/cancellation mechanism is used so a
timed-out HTTP request is actually cancelled before another attempt
starts.

------------------------------------------------------------------------

## 18. Failed Document Handling

Failed documents are retained in the database instead of being deleted.

The document has a `retryable` flag.

``` text
FAILED + retryable=true
        ↓
Show in Purchase Orders
        ↓
User clicks Retry
        ↓
Process existing document again
```

Retry reuses the existing document/file reference and must not create a
duplicate document record.

Permanent failures remain:

``` text
FAILED + retryable=false
```

and do not show a Retry action.

------------------------------------------------------------------------

## 19. Duplicate Upload Handling

File hash is used to detect duplicate uploads.

Duplicate handling must distinguish between:

-   Successfully processed existing document → avoid creating a
    duplicate.
-   Retryable failed document → allow recovery/reprocessing of the
    existing document.
-   Permanent failure → do not repeatedly process automatically.

A previous retryable failure must therefore not permanently block a user
from retrying after a temporary Gemini/API issue is resolved.

------------------------------------------------------------------------

## 20. Uploaded File and Extracted Text Storage

The original uploaded document is retained (not stored in memory) because 
it is required for retry.

The complete extracted text is not stored as the primary database
record.

Reasons: - Extracted text can be large. - It duplicates information
obtainable from the original document. - It unnecessarily increases
database storage. - Retry should use the original document rather than a
potentially stale extracted representation.

Source text can be exposed temporarily or for diagnostics/review.

------------------------------------------------------------------------

## 21. Validation Metadata

`validation_corrections` can retain correction details such as:

``` text
field
original value
corrected value
reason
```

`validation_review_reasons` can retain reasons for manual review.


Will use `JSONB` when the metadata needs structured querying; simple
display-only information can remain `TEXT`.

------------------------------------------------------------------------

## 22. PostgreSQL and Redis Decision

Redis is intentionally **not** part of the current architecture.

It could be used for caching repeated requests or reducing repeated
database/API work, but the current MVP has no demonstrated workload
requiring a separate cache.

Adding Redis would add another service and operational complexity
without a clear current benefit.

The same principle applies to custom connection-pool tuning and
extensive database indexing: they can be introduced later based on
measured workload and query patterns rather than prematurely.

------------------------------------------------------------------------

## 23. Search

Search supports useful PO attributes such as: - PO number - Vendor -
Amount - Date - Status

Conventional filters are provided for predictable queries, with
natural-language search where supported.

------------------------------------------------------------------------

## 24. Export

Structured PO data can be exported in supported formats such as: - CSV, 
Excel and PDF

Exports operate on stored structured PO data rather than raw Gemini
output (failed PO are considered for this generated report).