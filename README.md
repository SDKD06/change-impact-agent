# Change-Impact Agent

> **"See what breaks before you break it."** — an AI agent that traces the full downstream impact of changing a method in a Java codebase, backed by a real call graph.

When you change a method in a large codebase, the scary question is _"what else breaks?"_ In a project with thousands of methods you can't answer that by eye. **Change-Impact Agent** answers it: name a method, and it reports everything that depends on it, how badly each would be affected, and how far the impact spreads — grounded in a real dependency graph, not a language model's guess.

![Impact analysis](docs/cards.png)
![Dependency graph](docs/graph.png)

---

## The core idea

The project rests on one design decision: **split the work between something precise and something smart.**

- A **call graph** (built with JavaParser's symbol solver) knows _for a fact_ which method calls which. It supplies the precise "what is affected" set — no guessing.
- An **LLM agent** (Spring AI) reads the affected code and explains _how_ and _how badly_ it's affected — the reasoning the graph can't provide.

Neither could do the job alone: the graph can't explain impact, and an LLM can't reliably find every caller across a large codebase. Together they're both precise and insightful. The structured numbers are authoritative; the AI narrative is a fallible reasoning layer surfaced alongside them.

---

## What it does

Given a method name, it returns:

- **Risk level** (Low / Medium / High) and a one-line verdict
- **Blast-radius stats** — methods affected, files touched, max hop depth, direct callers
- **Direct breakage** — callers that break on a signature change (hop 1)
- **Behavioral risk** — callers that depend on current behavior (hop 2+)
- **A force-directed dependency graph** — the changed method at the center, callers radiating out, color-coded by hop distance, arrows showing call direction
- **An AI written report** (on demand) — narrative analysis and recommended checks

---

## Architecture

```
   POST /api/ingest                          POST /api/impact?method=swap
        |                                              |
        v                                              v
 +--------------+   builds    +------------------+  reads  +------------------+
 | RepoIngest   |------------>|  CallGraphService |<-------| ImpactAnalysis   |
 |  - chunks by |             |  (JavaParser +    |  facts |  Service         |
 |    method    |             |   symbol solver)  |        |  - stats, risk   |
 |  - embeds ---+--> pgvector |  - nodes + edges  |        |  - graph data    |
 +--------------+             |  - transitive     |        |  - (opt.) agent  |
                              |    impact (BFS)   |        +------------------+
                              +------------------+                  |
                                                          Angular frontend (UI)
```

- **CallGraphService** — parses every `.java` file, resolves each call to its target, stores reverse edges. `transitiveImpact()` is a reverse BFS over those edges = the "what breaks" set.
- **RepoIngestService** — code-aware chunking (one chunk per method, never split mid-function), embedded into pgvector for semantic search.
- **ImpactAnalysisService** — assembles the structured report from the graph; the deterministic data is instant, the AI narrative is optional and best-effort (a model hiccup never breaks the structured response).
- **Angular frontend** — renders the risk card, stat cards, breakdown columns, and a force-directed SVG dependency graph from the real API data.

---

## Tech stack

| Layer            | Technology                                   |
| ---------------- | -------------------------------------------- |
| Backend          | Java 21, Spring Boot 3, Spring AI            |
| Code analysis    | JavaParser (symbol solver)                   |
| Vector store     | PostgreSQL + pgvector                        |
| LLM / embeddings | Ollama (local) — llama3.1 / nomic-embed-text |
| Frontend         | Angular (standalone components)              |

---

## Results & evaluation

Measured on **[TheAlgorithms/Java](https://github.com/TheAlgorithms/Java)** as the test corpus:

- **Scale:** built a call graph of **2,784 methods and 8,464 call edges**, and embedded **7,433 method chunks** into the vector store.
- **Reference resolution: ~96.5%** — 8,464 of 8,770 call sites resolved; the remainder (library calls, complex generics) are skipped rather than guessed.
- **Accuracy:** on a hand-verified ground-truth set of methods in `MaxHeap.java`, the call graph scored **100% precision and 100% recall** (7/7 caller relationships) via `POST /api/eval` — it never fabricates an edge and missed none of the resolvable ones.

Together these give a complete picture: large-scale coverage (96.5% across thousands of references) plus verified correctness on a labeled sample. The eval harness (`EvalRunner` + `/api/eval`) is reproducible — ingest the test repo and hit the endpoint to regenerate the numbers; expand `EvalRunner.defaultSet()` to broaden the verified set.

> **Design note worth highlighting:** the LLM never produces the numbers — the deterministic call graph does. The model is _given_ the verified facts (risk level, counts, affected methods) and only explains them in plain language, so the written narrative is always consistent with the structured cards. This is how the project keeps LLM reasoning useful without letting it hallucinate facts.

---

## Project structure

```
change-impact-agent/
├── src/                 # Spring Boot backend (call graph, RAG, agent, API)
├── frontend/            # Angular frontend (UI)
├── docs/                # screenshots
├── docker-compose.yml   # Postgres + pgvector
└── README.md
```

---

## Running it locally

**Prerequisites:** Java 21, Docker, [Ollama](https://ollama.com), Node.js + Angular CLI.

**1. Start the database**

```bash
docker compose up -d
```

**2. Pull the local models**

```bash
ollama pull llama3.1
ollama pull nomic-embed-text
```

**3. Run the backend** (point it at any Java repo via the REPO_PATH env var)

```bash
mvn spring-boot:run
```

**4. Ingest, then query**

```bash
curl -X POST http://localhost:8080/api/ingest
curl -X POST "http://localhost:8080/api/impact?method=swap"
```

**5. Run the frontend**

```bash
cd frontend
npm install
ng serve
# open http://localhost:4200
```

### API

| Endpoint                                       | Purpose                                                    |
| ---------------------------------------------- | ---------------------------------------------------------- |
| `POST /api/ingest`                             | Build the call graph + embed the repo (run once per start) |
| `POST /api/impact?method=NAME`                 | Structured impact analysis (instant)                       |
| `POST /api/impact?method=NAME&withReport=true` | Also generate the AI written narrative (slower)            |
| `POST /api/eval`                               | Run the evaluation harness and return precision/recall     |

---

## Known limitations (honest)

- **Symbol resolution is best-effort.** Calls into external libraries and some complex generics don't resolve and are skipped (~3.5% of call sites in the test corpus). The graph favors in-project calls, which is what impact analysis needs.
- **Small / framework-heavy projects show flat results** — most methods are entry points or leaves. The tool shines on larger codebases with deep internal call chains.
- **A single local model is a bottleneck** under load (ingest + query compete). A production version would embed in the background or use a hosted embedding service.

## Roadmap

- Background / async ingestion so queries never block on embedding
- Dynamic "top-impact methods" endpoint to seed the UI examples per repo
- Broader evaluation set with automated ground-truth extraction
- Cross-file and cross-module impact for multi-repo systems
- Live deployment

---

Built as a study in grounded, agentic RAG: a deterministic retrieval source (the call graph) combined with an LLM reasoning layer, on a Spring Boot + Angular stack.
