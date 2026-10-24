# AgentLitmus · Memory Bench

> **AgentLitmus** — a black-box, explainable and reproducible benchmark for **long-term memory in AI agents**.
> Telling apart *"it runs"* from *"it actually works in production"*.

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-17%2B-orange.svg)](https://www.openjdk.org/)
[![Platform](https://img.shields.io/badge/openKylin-3.0-brightgreen.svg)](https://www.openkylin.top/)
[![GitHub](https://img.shields.io/badge/GitHub-agent--litmus%2Fmemory--bench-181717?logo=github)](https://github.com/agent-litmus/memory-bench)
[![Gitee](https://img.shields.io/badge/Gitee-agent--litmus%2Fmemory--bench-C71D23?logo=gitee)](https://gitee.com/agent-litmus/memory-bench)
[![CI](https://github.com/agent-litmus/memory-bench/actions/workflows/ci.yml/badge.svg)](https://github.com/agent-litmus/memory-bench/actions/workflows/ci.yml)

**English** | [简体中文](./README.md)

---

## What it does

AgentLitmus turns *"my agent remembers things"* — a claim nobody can argue with — into six separate, measurable numbers.

It collects **dialogue, memory records, action traces and run artifacts** into a single evidence stream, judges each case against that evidence with **deterministic rules**, classifies every failure into one of five categories, and emits multi-dimensional metrics plus a radar chart.

- **Black-box**: it never touches the agent's internals. Plug in an agent by implementing one method.
- **Explainable**: every judgment carries a structured reason — not a 0.42 score.
- **Reproducible**: runs offline, needs no API key, and the two built-in agents are bit-for-bit deterministic (σ=0).

Core library has **zero framework dependencies** (Jackson + slf4j only) and ships as a CLI and a `.deb` package.

---

## Six dimensions

| Dimension | What it measures |
|---|---|
| **Retention** | Is information that should persist actually remembered and reused later? |
| **Recall** | Can the agent retrieve relevant information in later interactions? |
| **Update** | When new information arrives, is the old value correctly overwritten? |
| **Discrimination** | Can the agent tell apart near-identical pieces of information? |
| **Boundary** | Is information that must **not** be retained (OTPs, ID numbers, "delete all logs") correctly excluded? |
| **Reuse** | Does the agent actually apply historical information while doing real tasks? |

The built-in dataset has **150 cases — 25 per dimension** in the `basic` suite, including **20 cross-session cases**: the fact is injected, the session context is then *reset*, and only long-term memory remains. A correct answer can therefore come only from real long-term memory, never from the context window. Additional `security` / `pressure` / `crosscut` suites bring `--suite full` to **283 cases**.

### Retrieval-term isolation

Discrimination cases are worded so that **the question's retrieval terms hit only the target memory, never the distractors**:

- distractor: `user went to Shanghai on Tuesday`
- target: `user went to Hangzhou on Wednesday`
- question: `Which day did I go to Hangzhou?` — *Hangzhou* / *Wednesday* appear nowhere in the distractor

Without this, you measure **retrieval coincidence**, not discrimination.

---

## Results (measured, not estimated)

Full 150-case run on openKylin 3.0 / OpenJDK 21. Two of the four agents are **real systems** from the openKylin ecosystem.

| Agent | Retention | Recall | Update | Discrimin. | Boundary | Reuse | **Overall** |
|---|---|---|---|---|---|---|---|
| `reference` (baseline, built-in) | 100% | 100% | 100% | 100% | 100% | 100% | **100%** |
| **KylinBot** (real, CLI adapter) | 92% | 56% | 44% | 72% | 60% | 76% | **67%** |
| `degraded` (built-in defective) | 100% | 88% | 0% | 60% | 0% | 76% | **54%** |
| **Hermes** (real, ACP adapter) | 96% | 56% | 4% | 36% | 12% | 76% | **47%** |

The point is not the numbers — it is that **the four profiles differ**. KylinBot discriminates far better (72% vs 36%); Hermes is near-perfect at retention (96%) but almost never updates (4%) and barely enforces boundaries (12%). A single total score would erase all of that.

`reference` and `degraded` are deterministic and give **identical results across repeated runs (σ=0)**, which is the evidence that *the ruler itself is stable*. Two independent full re-runs of the real agents gave 68%/67% (KylinBot) and 45%/47% (Hermes) — the variance comes from the agents, not the tool.

### Three numbers, not one

Verdicts are **three-valued**: `PASS / FAIL / UNKNOWN`. A no-response (empty answer, timeout, rate limit) or a capability-gated skip means **no judgment happened** — it is recorded as `UNKNOWN` and removed from the scoring denominator. A broken harness must not masquerade as a memory defect. Every score therefore comes as three numbers to read together:

| Number | Formula | What it answers |
|---|---|---|
| **Effective rate** | passed / judged | how strong |
| **Coverage** | judged / planned | how much evidence the score rests on |
| **Pessimistic lower bound** | passed / planned | worst case |

### No-response cases, disclosed both ways

Real agents have long-tail latency: in this run **9/150** Hermes cases and **3/150** KylinBot cases returned nothing within the timeout. They are counted as failures (the conservative reading) but **attributed separately** as `NO_RESPONSE` — *service failure, not a memory defect*.

Excluding them from the denominator: Hermes **46.7% → 49.6%**, KylinBot **66.7% → 68.0%**. Critically, Hermes' Update (4% → 4.3%) and Boundary (12% → 12%) barely move — **its low scores are not caused by timeouts.**

---

## Quick start

Three install paths, identical functionality (single-source judge):

```bash
pip install agentlitmus && agentlitmus run --out result/   # Python users: no Java needed
docker run --rm -v "$PWD/result":/out ghcr.io/agent-litmus/memory-bench run --out /out   # Docker (amd64/arm64)
./mvnw -q clean package -DskipTests && java -jar target/memory-bench-1.0.0.jar run --out result/   # JDK 17+
```

**No API key needed** — the built-in dataset is judged entirely by rules, offline.
The pip package is a thin shim: it downloads a self-contained binary (bundled runtime,
sha256-checked against the release's `checksums.txt`) on first run and forwards commands verbatim. The judge stays single-sourced — never two rulers for the same evidence.

### CLI

```bash
litmus run                                   # built-in agents, built-in dataset
litmus run --agents reference,degraded       # pick agents
litmus run --agents-config examples/agents-openkylin.json --out out/   # external agent config
litmus run --cases my-cases.json --out out/  # external dataset
litmus run --suite full --out out/           # suite tiers: basic / standard / full (283 cases)
litmus run --parallel 8 --out out/           # parallel evaluation
litmus run --repeat 5 --out stable/          # repeat N times → stability evidence (σ / jitter)
litmus validate-cases                        # validate dataset legality + coverage
litmus export-cases --out out/               # export built-in cases as a template
litmus rerender --from out/ --out out-v2/    # re-render charts without re-running
                                             # (--out required once manifest.json exists)

# The trust chain: every published number can be independently re-checked
litmus selftest                              # calibrate the judge itself on a gold set (κ gate in CI)
litmus verify --from out/                    # re-verify a run contract (case set / caliber / per-file sha256)
litmus compare out/a out/b                   # regression gate: exits non-zero on significant regressions
litmus regrade --from out/old --out out/new  # re-judge stored evidence under the current rules, no re-run
litmus probe --agents-config agents.json     # probe what a platform can actually be tested on
litmus leaderboard out/a out/b --out board/  # aggregate runs into one static leaderboard
```

### Outputs

| File | Contents |
|---|---|
| `report.txt` | Per-agent report + comparison table (plain text) |
| `report.html` | Self-contained report with a six-dimension radar chart |
| `radar.svg` | Radar chart |
| `evidence/<agent>.jsonl` | Per-agent run evidence (JSON Lines, one record per line, seq + sha256 sealed) |
| `artifacts/<agent>/<case>.md` | Per-case Markdown transcript |
| `manifest.json` | **Run contract**: case-set sha256, rules caliber, environment fingerprint, per-artifact hashes |

Every run writes a verifiable contract: `verify` re-hashes everything offline, `regrade` re-judges stored
evidence under new rules without re-running the agent, `compare` is a per-case regression gate you can put
in CI, `selftest` calibrates the judge itself on a gold set (κ gate), and `leaderboard` aggregates runs —
scores are taken from contracts verbatim, never recomputed. Reproducibility here is a checkable property,
not a claim.

---

## Plugging in your own agent

One method:

```java
@FunctionalInterface
public interface Answerer {
    String answer(String sessionId, String question);
}
```

No framework, no model, no memory implementation is assumed. Five adapter types are ready out of the box:

| Type | Use for |
|---|---|
| `builtin` | Deterministic built-ins (`reference`, `degraded`) — offline calibration |
| `command` | CLI agents, e.g. openKylin **KylinBot** via `scripts/kylinbot-adapter.sh` |
| `http` | Any agent exposing an HTTP endpoint |
| `acp` | Any **ACP** agent (stdio JSON-RPC) — whatever your editor can drive, this can benchmark |
| `mcp` | Agents exposing a chat tool as an **MCP server** (stdio transport); one `tools/call` per turn |

Hermes is driven over **ACP** (Agent Communication Protocol, JSON-RPC over stdio) by `HermesAcpAnswerer`.

```json
[{ "id": "hermes", "name": "Hermes Agent (ACP)", "type": "builtin", "role": "primary" }]
```

Binary path and template home are configurable, so the harness never hard-codes an install location:

```bash
java -Dlitmus.hermes.bin=/opt/hermes/venv/bin/hermes-acp \
     -Dlitmus.hermes.home=/opt/hermes \
     -jar target/memory-bench-*.jar run --agents hermes --cases small.json --out probe/
# or: HERMES_ACP_BIN / HERMES_ACP_HOME
```

---

## Scoring: five outcome classes

A pass rate only tells you *how often* it failed. These five classes tell you *what kind* of failure it is — two agents both at 54% may need completely different fixes.

| Class | Meaning |
|---|---|
| **Correct** | Remembered and used correctly |
| **Omission** | Should have remembered, didn't |
| **Confusion** | Near-identical information mixed up |
| **Wrong persistence** | Information that must not be retained was retained |
| **Wrong reuse** | Superseded value still in use |

On top of that, each failure is attributed to a **lifecycle stage** with an actionable suggestion. The same "omission" splits further using the cross-session flag: still missing after a session reset ⇒ *not persisted* (write/retention defect); missing within the same session ⇒ *retrieval/application* defect. All of this is inferred **without ever looking inside the agent**.

---

## Rules first, LLM only as fallback

Anything expressible as keywords is judged by rules — never by a model. If scoring depends on a model, then "the evaluation is unstable" masks the very problems you are trying to measure.

Only criteria that keywords cannot express (tone, appropriateness, open-ended tasks) go to an LLM, and **with no model available the judge abstains and labels the result** rather than guessing — the run never breaks. When a judge is enabled (`--judge`), verdicts are cached by (rules version, judge fingerprint, rubric, answer), so offline re-grading reproduces exactly; multiple models can be combined into a **panel** decided by strict majority.

---

## Packaging

```bash
# Must run on Linux — jpackage cannot cross-compile (no .deb from macOS)
./scripts/build-deb.sh
sudo dpkg -i dist/agent-litmus_*.deb
agent-litmus run --out ~/litmus-result
```

The `.deb` **bundles a trimmed JRE**, so the target machine needs no pre-installed Java.

---

## Project layout

```
io/github/agentlitmus/
├── Cli.java              CLI entry point (dispatch only)
├── cli/                  one class per subcommand (run / verify / selftest / compare / regrade / probe / leaderboard / …)
├── core/                 evaluation engine (dimensions, cases, three-value judgment, outcomes, attribution, oracle recheck)
├── memory/               long-term memory store (credibility × retainable, state snapshots)
├── agent/                agents under test (Answerer contract, http/command/ACP adapters, sandboxing)
├── evidence/             unified evidence model (seq + sha256 sealed), integrity self-check, JSON Lines export
├── report/               metrics, radar chart, HTML report, run contract (manifest), stability
├── dataset/              suite tiers (basic 150 … full 283) + coverage validation + gold set
├── llm/                  single-method LLM client, OpenAI-compatible judge + verdict cache
└── stats/                paired bootstrap, Cohen's κ, consistency
```

---

## Docs

| Document | Contents |
|---|---|
| [README.md](./README.md) | Chinese README (most detailed) |
| [docs/USAGE.md](./docs/USAGE.md) | Practical guide: try without a key / plug in an agent / read the report / re-verify after changes (Chinese) |
| [SOLUTION.md](./SOLUTION.md) | Methodology: goals, data generation, case design, evidence, scoring (Chinese) |
| [docs/SPEC-EVIDENCE.md](./docs/SPEC-EVIDENCE.md) | Evidence protocol spec + JSON schemas — the basis for cross-language re-grading |
| [PITFALLS.md](./PITFALLS.md) | Everything that went wrong while integrating real agents (Chinese) |
| [samples/](./samples) | Real run evidence, per-case artifacts and reports |

---

## Known limitations

- **Rule-based matching is sensitive to synonyms and paraphrasing.** This is the price paid for determinism; when the two rule signals conflict, the judge **abstains** (recorded as UNKNOWN) instead of guessing, and such cases can be routed to an optional LLM judge layer — measured at κ = 0.901 on the gold set, with verdicts cached so re-grading stays reproducible.
- For real agents the default evidence is **dialogue plus judgment artifacts**; memory-state snapshots (what the agent actually stored) and process-level action traces require declaring a `stateDir` or using a command adapter. Unbacked attributions are listed as **inferences**, never presented as observations.
- **Contradictory statements at the same instant** (as opposed to temporal updates) need semantic judgment and are not covered by the current dataset.
- Real-agent results depend on those agents being available, so they are shipped as pre-recorded evidence rather than requiring a re-run.

---

## Beyond scoring: a defect this benchmark found

Two **independent** full runs (parallelism 8 and 4; timeouts 240 s and 300 s) produced timeout sets that were not random:

| Metric | Value |
|---|---|
| Timeouts, run 1 / run 2 | 25 / 22 cases |
| **Timed out in both runs** | **12 cases** |
| Expected overlap if purely random | ≈ 3.7 cases (25×22/150) |

The observed overlap is **3.2× the random baseline** — so part of it is reproducible and input-triggered, not API jitter. Re-running those 12 cases with a 600 s timeout and protocol-level debug logging:

- all 12 produced an answer, **0 empty** ⇒ *extremely slow*, not hung
- per-turn latency 54 s – 600 s (normal median 25–30 s)
- protocol events: **57,420 thought chunks vs 7,875 answer chunks (≈7:1)**, with only **2 tool calls**

So the slowness is not file-tool usage — the agent generates an extremely long internal reasoning chain on this class of request. A smoke test with a handful of cases would never surface it; only a full, multi-turn, sustained run does.

---

## License

Apache License 2.0. Brand **AgentLitmus** · org `agent-litmus` · package `io.github.agentlitmus`.
