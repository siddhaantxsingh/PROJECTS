# Resume evidence review (draft, 2026-10-14)

This file checks every claim in the three resumes the owner supplied (SWE, ML and Electronics, files named `Siddhant_*_Final-1_*.pdf`) against evidence that exists in the owner's repositories. It does **not** judge claims that only the owner can prove (grades, ranks, contest results, employment). Those are listed with the document that would prove them.

Status labels:

* **SUPPORTED:** a repository contains code and measured evidence for the claim as written.
* **PARTLY SUPPORTED:** the evidence exists but the wording overstates it, or a detail differs.
* **NOT SUPPORTED BY ANY REPO:** no repository I can access contains evidence. The claim may still be true, but it is unproven and risky in an interview.
* **OWNER MUST VERIFY:** only an external record can prove it (offer letter, transcript, contest profile, etc.).

Sections marked *(updated when the audit finishes)* depend on audits that are still running.

---

## 1. Tata Steel internship (July 2026)

### 1.1 What the three resumes say

| Item | SWE resume | ML resume | Electronics resume |
|---|---|---|---|
| Role title | Software Engineering Intern | Machine Learning Intern | Embedded Systems & ML Intern |
| Dates | July 2026 | July 2026 | July 2026 |
| TinyML bullet | "Built and deployed a TinyML fault-detection **pipeline** on ARM Cortex-M microcontrollers for real-time crane sensor monitoring, achieving sub-100ms inference latency." | "**Trained and deployed an INT8-quantized** TinyML fault-detection model on ARM Cortex-M hardware … sub-100ms inference latency." | "Deployed a TinyML fault-detection model on ARM Cortex-M …" (no latency) |
| RAG bullet | "**Shipped an internal RAG service** over 500+ pages of maintenance SOPs, cutting engineer **query time** to under 30 seconds." | "**Designed a RAG pipeline** over 500+ pages … cutting engineer query time to under 30 seconds." | "**Built a RAG assistant** over 500+ pages … cutting **lookup time** to under 30 seconds." |

### 1.2 Inconsistencies to fix

1. **Three different job titles for one internship.** A background check compares the title against the offer letter or the experience certificate. Use the title on that document in all three resumes. To fit a role, change the bullets, not the title.
2. **Dates.** "July 2026" reads as a single month. Write the exact start and end dates from the certificate, for example "Jun 2026 – Jul 2026".
3. **What was done to the model.** The resumes say "built a pipeline", "trained and deployed an INT8-quantized model" and "deployed a model". These are different claims. Pick the one that is true. If you did not do the INT8 quantization yourself, remove it.
4. **"Deployed".** State whether the model ran on a real crane or on a bench or test board, and whether people used it. "Deployed for real-time crane sensor monitoring" implies production use on plant equipment.
5. **Latency.** Two resumes say sub-100 ms and one says nothing. State what was timed (inference only, or sensor-to-alert), on which MCU and at what clock speed.
6. **RAG wording.** "Shipped an internal service", "designed a pipeline" and "built an assistant" differ in scope. "Query time" and "lookup time" differ too. Under 30 s compared with what? Give the before value, for example "from ~10 minutes of manual search".
7. **Confidentiality.** Do not publish SOP text, crane sensor data, plant names or internal screenshots. Get written permission before describing internal systems in detail.

### 1.3 Evidence check

| Claim | Evidence in the repositories | Status | Evidence the owner needs |
|---|---|---|---|
| Internship at Tata Steel, July 2026 | None. A text search of PROJECTS (15 projects), the 5 standalone repos, MESP and the matrix-multiplier repo found no mention of Tata Steel. | OWNER MUST VERIFY | Offer letter, experience certificate or stipend record; manager's name and email for reference checks |
| Role title | none | OWNER MUST VERIFY | The title on the certificate |
| TinyML fault detection on ARM Cortex-M | None from the internship. A separate personal project now has a Cortex-M INT8 classifier run in QEMU; it is **not** internship work *(updated when the audit finishes)* | OWNER MUST VERIFY | MCU part number; model type and size; how latency was measured; accuracy on held-out data; permission to describe |
| Sub-100 ms inference | none | OWNER MUST VERIFY | A latency log or test report from the internship |
| INT8 quantization (ML resume) | none from the internship | OWNER MUST VERIFY | Training or conversion notebook or report |
| RAG over 500+ pages of SOPs | None from the internship. Groundwork and Campus AI OS are separate personal RAG projects | OWNER MUST VERIFY | Document count and pages; architecture; who used it |
| Query time under 30 s | none | OWNER MUST VERIFY | The before and after measurement method |

### 1.4 Suggested wording (use only after checking each fact)

Fill the brackets from your records and delete anything you cannot prove. **Use one title everywhere.**

> **Tata Steel** — [Title exactly as on certificate] · Jamshedpur · [Mon YYYY – Mon YYYY]
> – [Built / Trained and quantized to INT8] a fault-detection model for crane [hoist motor / gearbox] sensors on an ARM Cortex-[M4] ([MCU part]); measured [N] ms per inference on the board ([what was timed]), [accuracy metric] on [held-out data description].
> – Built a [prototype] RAG assistant over [N] pages of maintenance SOPs for [team]; [N] engineers used it during [pilot]; median lookup time fell from [X] to [Y] s in [how measured].

If you cannot get numbers, use the bullets without them, for example "Prototyped a TinyML fault-detection model on an ARM Cortex-M board for crane sensor monitoring". **Never** put the personal projects' numbers (QEMU estimates, synthetic datasets) into the Tata Steel entry.

When you describe the personal projects, present them as separate work, for example "Industrial Edge — personal open-source project extending ideas from my internship; all data synthetic".

---

## 2. Portfolio claims on the resumes

### 2.1 SWE resume

| Claim | Evidence | Status |
|---|---|---|
| Raft KV: Go, **gRPC, C++** | RAFT-KV repo *(updated when the audit finishes)* | *(pending)* |
| Raft KV: 5-node cluster, snapshotting, recovers within 2× election timeout | RAFT-KV repo *(updated when the audit finishes)* | *(pending)* |
| Raft KV: "documented log compaction and membership-change trade-offs" | Membership changes were NOT IMPLEMENTED at baseline (ADR 0005, static membership) *(updated when the audit finishes)* | *(pending)* |
| **Kubernetes PaaS Platform** (Go, Kubernetes, Docker, gRPC); 47 s → 12 s deploys; "findings discussed on Hacker News" | No such repository among the owner's 12 GitHub repositories. Nebula GPU Cloud is a Rust GPU scheduler, not a Kubernetes PaaS. | **NOT SUPPORTED BY ANY REPO.** High interview risk. Remove it, or publish the code and the benchmark log plus a link to the HN thread |
| Collaborative editor (CRDTs): no central lock | TANDEM repo *(updated when the audit finishes)* | *(pending)* |
| "Implemented operational transformation as an alternate merge strategy across 50+ simulated editors" | TANDEM repo *(updated when the audit finishes)* | *(pending)* |
| Sub-50 ms edit propagation over WebSockets; offline reconciliation | TANDEM repo *(updated when the audit finishes)* | *(pending)* |
| Skills: Kafka, GraphQL Federation, Kubernetes, AWS, Redux | No portfolio repository uses them (one Nebula doc mentions Kubernetes only as the intended stack) | Keep only skills you can discuss in depth |

### 2.2 ML resume

| Claim | Evidence | Status |
|---|---|---|
| LLM fine-tuning with **LoRA** | No repository contains LoRA or any fine-tuning code. Groundwork does retrieval and grounded generation only | **NOT SUPPORTED BY ANY REPO** |
| "90%+ accuracy on a held-out QA set" | Groundwork reports retrieval and citation metrics *(updated when the audit finishes)* | *(pending)* |
| "Indexed 10,000+ documents … sub-second retrieval" | Groundwork corpus size *(updated when the audit finishes)* | *(pending)* |
| Vector DB in **C++, Python** | `PROJECTS/vectordb` is **Rust** (with a Python benchmark harness) | **PARTLY SUPPORTED:** fix the language |
| HNSW over **1M+** embeddings | Benchmarks use n = 100,000 vectors × 128 dims (synthetic) | **PARTLY SUPPORTED:** say 100k, or run a 1M benchmark and save the log |
| PQ compresses storage **8×** with **95%+ recall@10** | PQ m = 32: codes are 15.4× smaller than f32, and recall@10 is 0.95 with rerank ×4 (`vectordb/docs/benchmarks.md`) | **SUPPORTED, with a caveat:** the measured ratio is 15×, and 0.95 needs exact reranking of 40 candidates. Write "15× smaller codes; 0.95 recall@10 with exact re-ranking" |
| p50/p99 latency vs graph parameters | `vectordb/docs/benchmarks.md` (ef sweep, compared with FAISS) | **SUPPORTED** |
| TinyML Edge Inference Engine: INT8 on Cortex-M, sub-100 ms, < 50 KB RAM, **three model architectures**, accuracy vs float | The new Industrial Edge TinyML module has one model, run in QEMU *(updated when the audit finishes)* | *(pending)* |

### 2.3 Electronics resume

| Claim | Evidence | Status |
|---|---|---|
| RISC-V core, multi-stage pipeline, hazards and forwarding | `PROJECTS/riscv-cpu`: 5-stage RV32IM in SystemVerilog; riscv-tests 64/64; 250/250 random programs match Spike | **SUPPORTED.** The resume undersells it: it is RV32IM in SystemVerilog, not RV32I in Verilog |
| "Synthesized and **deployed on FPGA hardware**" | riscv-cpu has never run on an FPGA. The requirements file says "no silicon or FPGA run" | **NOT SUPPORTED BY ANY REPO.** Remove it unless an older FPGA project exists with photos or logs |
| "Verified compiled C programs via a custom toolchain" | riscv-cpu runs CoreMark and C tests with a crt0 and linker script, using the standard GCC toolchain | **SUPPORTED** (say "GCC toolchain with custom crt0/linker script") |
| "Benchmarked clock frequency and IPC against a single-cycle baseline" | Measured CPI is 1.01–1.34 and CoreMark 2.87/MHz (2.43 without the branch predictor). There is no single-cycle baseline and no Fmax measurement *(Fmax may be added; updated when the audit finishes)* | **PARTLY SUPPORTED** |
| ASIC matrix multipliers (serial, pipelined, parallel), Genus and Innovus, met 4 ns timing | Matrix-multiplier repo: Genus timing reports at a 4.0 ns clock with positive slack on all 9 designs (176–1,272 ps); Innovus layout screenshots for the 3×3 designs | **SUPPORTED** for synthesis. Say layouts were done for the 3×3 designs |
| "Co-authored resulting paper, under review" | none | OWNER MUST VERIFY (submission confirmation email) |
| TinyML engine (same claim as the ML resume) | see 2.2 | *(pending)* |
| Skills: Vivado, Quartus, Virtuoso, ModelSim | The Mixed-node CMOS inverter repo may show Virtuoso (not inspected); there is no Vivado or Quartus project | Keep only tools you used for real |

### 2.4 Shared claims (all resumes)

| Claim | Status | Evidence the owner needs |
|---|---|---|
| Branch Rank 1, B.Tech Electronics (VLSI-DT); Achievers Scholarship | OWNER MUST VERIFY | Rank certificate or transcript. Only the Electronics resume says "AY 2026–27"; make the three match. A CGPA of 7.62 next to "Rank 1" will draw questions, so have the document ready |
| Codeforces Expert (1600+), top 14 %, rank 163 in Round 961 (Div. 4), 400+ solved | OWNER MUST VERIFY | Codeforces profile link (already on the resume) |
| LeetCode 600+, Knight | OWNER MUST VERIFY | LeetCode profile |
| Hackathons: 1st at M#, 2nd at BigCode | OWNER MUST VERIFY | Certificates |
| Open source: "3 merged PRs" | **Inconsistent:** SWE says full-stack/infra projects, ML says ML/systems projects, Electronics says one RTL/EDA tooling project | Links to the PRs. Describe the same PRs truthfully in every resume |
| HDLBits 100 % | OWNER MUST VERIFY | HDLBits profile screenshot |

---

## 3. Resume bullets the portfolio supports

These are drafted in `master/SCORECARD.md` after the audits finish, using only numbers with a saved log. Each bullet links to the evidence file.
