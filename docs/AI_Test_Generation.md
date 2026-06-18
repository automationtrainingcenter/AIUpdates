# AI Test Generation — INGenious Playwright Studio

This document covers all AI-powered test generation features added to INGenious.
Access the dialog via **Tools → AI Test Generator**.

---

## Table of Contents

1. [Setup — AI Settings](#1-setup--ai-settings)
2. [Web Test Generation](#2-web-test-generation)
   - [Mode: From Prompt / Requirement](#mode-from-prompt--requirement)
   - [Mode: From Azure Test Plan](#mode-from-azure-test-plan)
3. [API Test Generation](#3-api-test-generation)
   - [Mode 1: From Workbench Collection](#mode-1-from-workbench-collection)
   - [Mode 2: From OpenAPI / Swagger Spec](#mode-2-from-openapi--swagger-spec)
   - [Mode 3: From AI Prompt + Live API](#mode-3-from-ai-prompt--live-api)
4. [Authentication — Reusable Pattern](#4-authentication--reusable-pattern)
5. [How Generated Tests Are Structured](#5-how-generated-tests-are-structured)
6. [Supported LLM Models](#6-supported-llm-models)
7. [Architecture Reference](#7-architecture-reference)

---

## 1. Setup — AI Settings

Before using any AI feature, configure your credentials via **Tools → AI Settings**.

| Field | Description |
|---|---|
| **OpenRouter API Key** | API key from [openrouter.ai](https://openrouter.ai). Used for all LLM calls. |
| **Model** | Any OpenRouter model ID (e.g. `anthropic/claude-3.5-sonnet`). Default: `anthropic/claude-3.5-sonnet`. |
| **App URL** | Base URL of the application under test (used for Web generation). |
| **npx Path** | Path to `npx` binary — required for Web generation (Playwright MCP). |

Settings are saved per-project under `Settings/ai.properties`.

> **Model recommendation:** Use a standard (non-thinking) model such as
> `anthropic/claude-3.5-haiku`, `openai/gpt-4o-mini`, or `google/gemini-flash-1.5`
> for structured output generation. Reasoning/thinking models (e.g. `stepfun/step-3.7-flash`)
> may produce empty `content` fields and are not recommended.

---

## 2. Web Test Generation

The **Web Tests** tab generates INGenious Playwright test cases by driving a real browser
through an AI agent connected to the **Playwright MCP server**.

### How it works

1. An `LlmAgent` (Google ADK) is started with the Playwright MCP toolset connected.
2. The agent receives your requirement and navigates the live application using browser tools
   (`browser_navigate`, `browser_click`, `browser_fill`, `browser_screenshot`, etc.).
3. After interacting with the browser, it outputs Playwright Java code grouped into scenario blocks.
4. The code is parsed by `PlaywrightRecordingParser` and converted into INGenious `TestCase` CSV files.

### Mode: From Prompt / Requirement

**When to use:** You have a plain-language description of what to test.

**Input:**
- **Requirement text** — e.g. *"Test the login page with valid credentials, invalid password, and empty fields."*
- App URL is taken from AI Settings.

**What the agent does:**
1. Opens the browser and navigates to the App URL.
2. Explores the UI based on your requirement.
3. Executes each scenario group (valid flows, invalid flows, edge cases).
4. Outputs Playwright Java code; each scenario becomes one INGenious scenario folder.

**Output:** One or more scenario folders in TestPlan, each containing multiple CSV test cases.

---

### Mode: From Azure Test Plan

**When to use:** You track test requirements in Azure DevOps Test Plans.

**Input:**
- **Organization URL** — your Azure DevOps org URL
- **Personal Access Token (PAT)** — Azure DevOps PAT with Test Plan read access
- **Project** — Azure DevOps project name
- **Test Plan ID** — numeric ID of the test plan

The agent reads test cases from the Azure Test Plan via the Azure DevOps MCP server,
then drives the browser to execute and observe each test requirement.

> Azure DevOps settings are pre-filled from `Settings/TestMgmt.properties` if already configured.

---

## 3. API Test Generation

The **API Tests** tab provides three modes for generating INGenious API test cases (Webservice steps).

All three modes produce the same output format: `Scenario → TestCase → TestStep CSV files`
with `ObjectName = Webservice` (or `Execute` for reusable calls).

---

### Mode 1: From Workbench Collection

**When to use:** You have already saved API requests in the INGenious API Workbench (equivalent to Postman collections).

**No LLM involved — fully deterministic conversion.**

**Input:**
- Select a saved collection from the dropdown.
- Optionally rename the target scenario.

**Conversion logic (`ApiTestCaseGenerator`):**

Each request in the collection becomes one test case with steps in this order:

| Step | Action | Source |
|---|---|---|
| 1 | `setEndPoint` | Request URL |
| 2 | `addHeader` / `addURLParam` | Auth config (Bearer → `Authorization` header, API Key → header or param, Basic → Base64 header, OAuth2 → Bearer header) |
| 3 | `addHeader` | Each enabled header in the request |
| 4 | `addURLParam` | Each enabled query parameter |
| 5 | HTTP verb | `getRestRequest` / `postRestRequest` / `putRestRequest` / `patchRestRequest` / `deleteRestRequest` + request body |
| 6 | `assertResponseCode` | `200` (added automatically if no status assertion exists in the collection) |
| 7 | Assertion steps | Each enabled workbench assertion → `assertJSONelementEquals`, `assertXMLelementEquals`, `assertHeaderValueEquals`, `assertResponsebodycontains`, etc. |
| 8 | `closeConnection` | Always last |

---

### Mode 2: From OpenAPI / Swagger Spec

**When to use:** You have an OpenAPI 3.x or Swagger 2.0 spec and want the LLM to generate comprehensive test cases (happy path + error paths).

**Input:**

| Field | Required | Description |
|---|---|---|
| **Spec URL (JSON)** | Yes | Direct URL to the spec JSON (e.g. `https://yourapp.com/api/docs`) |
| **Base URL (optional)** | No | Overrides the server URL from the spec. Use this when the spec contains a cloud/internal URL that differs from your test environment. |
| **Filter by** | Yes | **Controller / Tag** — keeps only operations whose `tags` list contains the filter value. **Endpoint Path** — keeps paths whose URL contains the filter value. |
| **Value** | Yes | The tag name or path substring to filter by (case-insensitive). |
| **What to test** | No | Additional free-text guidance for the LLM (e.g. auth details, edge cases to focus on). |

**How spec filtering works (`OpenApiSpecExtractor`):**

Sending the entire spec wastes tokens. The extractor:
1. Fetches the spec JSON from the URL.
2. Keeps only paths matching the filter (by tag or path substring).
3. Walks all `$ref` references in the kept paths **recursively** — collects only the schemas actually used.
4. Copies those schemas plus metadata (`info`, `servers`, `securityDefinitions`, etc.) to the LLM.

This means for an API with 50 endpoints and 80 schemas, filtering to `/clients` sends only the 3–4 relevant paths and 4–6 schemas instead of the full document.

**What the LLM generates:**

- If authentication is detected: an `Auth [REUSABLE]` scenario is generated first (see [Section 4](#4-authentication--reusable-pattern)).
- For each resource/controller: one scenario with happy-path and error-path test cases (400, 401, 403, 404, 409, etc.).
- Protected test cases start with `Execute|Auth:Login||` to reuse the login flow.

---

### Mode 3: From AI Prompt + Live API

**When to use:** You want the AI to explore a live API, observe real responses, and generate tests based on actual behavior.

**Input:**

| Field | Description |
|---|---|
| **Base URL** | API base URL (e.g. `https://api.yourapp.com`) |
| **What to test** | Natural language description — e.g. *"Test the clients CRUD operations including auth, validation errors, and not-found cases."* |

**How it works:**

An `LlmAgent` (Google ADK) is started with five HTTP function tools:

| Tool | Maps to |
|---|---|
| `http_get(path, headers)` | GET request |
| `http_post(path, body, headers)` | POST request |
| `http_put(path, body, headers)` | PUT request |
| `http_patch(path, body, headers)` | PATCH request |
| `http_delete(path, headers)` | DELETE request |

The agent:
1. Calls the login endpoint first (if auth is needed) to get a real token.
2. Explores the API by calling `http_get` on resource endpoints.
3. Tests CREATE, READ, UPDATE, DELETE flows with real payloads.
4. Tests error cases using observed field names and status codes.
5. After all tool calls are done, outputs the INGenious delimiter-format test steps.

Responses are truncated to 4000 characters to stay within model context limits.

---

## 4. Authentication — Reusable Pattern

This is the INGenious-native way to handle APIs that require a login token before every request.

### How it works

The LLM generates a special **reusable scenario** for authentication, marked with `[REUSABLE]` in the scenario name:

```
=====SCENARIO_START: Auth [REUSABLE]=====
=====TESTCASE_START: Login=====
setEndPoint|https://api.yourapp.com/auth/login||Set login endpoint
addHeader|Content-Type=application/json||Set content type
postRestRequest|{"identifier":"test@example.com","password":"Test@1234"}||Send login request
assertResponseCode|200||Assert login successful
storeJSONelement|$.data.accessToken|%authToken%|Store bearer token
closeConnection|||End login request
=====TESTCASE_END=====
=====SCENARIO_END=====
```

The `[REUSABLE]` marker tells the importer to save this scenario under **ReusableComponents** (not TestPlan).

Every protected test case then starts with an `Execute` call:

```
=====TESTCASE_START: List_Clients_Happy_Path=====
Execute|Auth:Login||Execute reusable login to get auth token
setEndPoint|https://api.yourapp.com/clients||Set endpoint
addHeader|Authorization=Bearer %authToken%||Add bearer token from login
getRestRequest|||Send GET request
assertResponseCode|200||Assert success
closeConnection|||End request
=====TESTCASE_END=====
```

### Variable storage syntax

| Action | Input (field 2) | Condition (field 3) | Purpose |
|---|---|---|---|
| `storeJSONelement` | JSONPath e.g. `$.data.accessToken` | `%authToken%` | Extract value from response into runtime variable |
| `addHeader` | `Authorization=Bearer %authToken%` | — | Use stored variable in a header |

Variables set inside a reusable test case are available to the calling test case because the `Execute` action runs the reusable in the same execution context.

### Multiple auth steps (e.g. Bearer + CSRF)

If the API requires both a Bearer token and a CSRF token, the LLM generates two test cases inside `Auth [REUSABLE]`:

```
Auth:Login       — POSTs to /auth/login, stores %authToken%
Auth:FetchCsrf   — GETs /auth/csrf with %authToken%, stores %csrfToken%
```

Protected test cases then include both `Execute` steps:
```
Execute|Auth:Login||
Execute|Auth:FetchCsrf||
addHeader|Authorization=Bearer %authToken%||
addHeader|X-CSRF-Token=%csrfToken%||
```

---

## 5. How Generated Tests Are Structured

### Output locations

| Scenario type | Location in project |
|---|---|
| Regular API/Web scenarios | `TestPlan/<ScenarioName>/<TestCaseName>.csv` |
| Auth reusable scenario | `ReusableComponents/Auth/<TestCaseName>.csv` |

### Step object types

| ObjectName | When used |
|---|---|
| `Webservice` | All API actions (`setEndPoint`, `addHeader`, HTTP verbs, assertions, `storeJSONelement`) |
| `Execute` | Calling a reusable test case — Action column contains `ScenarioName:TestCaseName` |

### Parsing pipeline (Modes 2 & 3)

```
LLM output text
    → ApiOutputParser          strips [REUSABLE] tag, splits into ApiTestScenario / ApiTestCase / ApiTestRow
    → ApiTestStepImporter      routes to TestPlan or ReusableComponents, maps Execute steps correctly
    → TestCase.save()          writes CSV to disk
    → Project reload           refreshes the IDE tree
```

---

## 6. Supported LLM Models

All AI features route through **OpenRouter** (`https://openrouter.ai/api/v1/chat/completions`), which gives access to models from multiple providers using a single API key.

**Recommended models for structured output generation (Modes 2 & 3):**

| Model ID | Speed | Notes |
|---|---|---|
| `anthropic/claude-3.5-haiku` | Fast | Best instruction-following for delimiter format |
| `anthropic/claude-3.5-sonnet` | Medium | Highest quality, good for complex specs |
| `openai/gpt-4o-mini` | Fast | Very reliable structured output |
| `google/gemini-flash-1.5` | Fast | Large context window — good for big specs |

**For Web generation (Mode: From Prompt / Azure):**

| Model ID | Notes |
|---|---|
| `anthropic/claude-3.5-sonnet` | Default, best Playwright code quality |
| `openai/gpt-4o` | Strong alternative |

**Avoid for structured output:** Thinking/reasoning models (`stepfun/*`, `deepseek-r1`, etc.) spend their token budget on internal reasoning and may not produce the required delimiter format in the `content` field.

---

## 7. Architecture Reference

### Key source files

| File | Purpose |
|---|---|
| `mainui/AITestGeneratorDialog.java` | Main dialog — UI, mode dispatch, progress log |
| `mainui/AISettings.java` | Tools → AI Settings dialog, saves `ai.properties` |
| `playwrightrecording/AITestGenerationAgent.java` | Web generation — ADK agent + Playwright MCP + Azure MCP |
| `playwrightrecording/OpenRouterLlm.java` | ADK `BaseLlm` implementation routing to OpenRouter |
| `playwrightrecording/PlaywrightRecordingParser.java` | Parses Playwright Java code blocks into INGenious test steps |
| `apigeneration/ApiTestCaseGenerator.java` | Mode 1 — deterministic collection → test steps converter |
| `apigeneration/OpenApiSpecExtractor.java` | Mode 2 — fetches spec, filters paths, prunes schemas to used refs only |
| `apigeneration/ApiLlmClient.java` | Mode 2 — one-shot OpenRouter HTTP call, handles `reasoning_content` fallback |
| `apigeneration/ApiHttpToolAgent.java` | Mode 3 — ADK agent with HTTP function tools |
| `apigeneration/ApiHttpTools.java` | HTTP tool methods (`http_get/post/put/patch/delete`) called by Mode 3 agent |
| `apigeneration/ApiOutputParser.java` | Parses LLM delimiter output → `ApiTestScenario` list, detects `[REUSABLE]` |
| `apigeneration/ApiTestStepImporter.java` | Writes scenarios to TestPlan or ReusableComponents, handles `Execute` steps |

### Dependencies

| Library | Used for |
|---|---|
| `google-adk 1.4.0` | Agent framework — `LlmAgent`, `InMemoryRunner`, `FunctionTool`, `McpToolset` |
| `RxJava3` | Streaming agent events as `Flowable<Event>` |
| `Jackson ObjectMapper` | Parsing OpenAPI spec JSON, loading API collections |
| `org.json` | Parsing OpenRouter HTTP responses |
| `java.net.http.HttpClient` | HTTP calls for spec fetch (Mode 2) and live API calls (Mode 3) |
