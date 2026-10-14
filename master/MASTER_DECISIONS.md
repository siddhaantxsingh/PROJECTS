# Decisions

| # | Decision | Why |
|---|---|---|
| D1 | Keep each project's existing language even where the directive names another: LLM engine in Rust (directive: C++/CUDA/Python), InferX in Go (directive: Python/CUDA), Nebula in Rust (directive: Go/Kubernetes, which explicitly allows keeping a working Rust scheduler), Vector DB in Rust | Rewriting working, tested code only to change language adds risk and no evidence. Each deviation is documented in that project's docs |
| D2 | No GPU exists in the cloud container. CUDA code is verified on a CPU SIMT emulator and compiled to real SASS with ptxas; GPU timing stays BLOCKED, with scripts for the owner's RTX 4060 | Directive section 4: never fake hardware results |
| D3 | Pretrained models come from Docker Hub `ai/*` GGUF artifacts (SmolLM2-135M, Qwen2.5-0.5B, nomic-embed-text-v1.5) | huggingface.co and download.pytorch.org are blocked from the container; Docker Hub is reachable. Models are not committed; fetch scripts pin sha256 |
| D4 | RTL-to-GDSII uses OpenROAD-flow-scripts (Docker image `openroad/orfs`) on the open sky130 and nangate45 PDKs | The only free full flow that runs here. Results are academic-PDK results, not tape-out |
| D5 | TinyML runs in QEMU (Cortex-M) built with arm-none-eabi-gcc. Cycle figures are labelled QEMU estimates | No physical MCU. Real-board timing goes to the owner action plan |
| D6 | The standalone repos get work on a branch, not `main` | The owner's instruction: no PRs and no pushes to default branches |
| D7 | The Tata Steel internship is never described from portfolio evidence | Directive section 5. Personal projects are labelled as separate work |
