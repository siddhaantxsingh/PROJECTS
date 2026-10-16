# Blockers (things this environment cannot do)

| Blocker | Projects affected | Owner action |
|---|---|---|
| No GPU in the container | CUDA kernels, LLM engine (GPU path), InferX, Nebula (real GPUs), Distributed training (NCCL) | Run the RTX 4060 scripts in OWNER_ACTION_PLAN_20_PROJECTS.pdf |
| Multi-GPU / multi-node hardware | Distributed training scaling, Nebula multi-node | Optional cloud rental (cost controls in the PDF) |
| Commercial SystemVerilog UVM simulator (Questa/VCS/Xcelium) | UVM SoC verification | University licence or EDA Playground account |
| FPGA board and Vivado | RISC-V CPU, accelerator | Owner's board and licence (Vivado is FPGA tooling, not CUDA) |
| Real Cortex-M board | Industrial Edge TinyML, EdgeFleet devices | e.g. STM32 Nucleo-F446RE / -L476RG |
| huggingface.co and download.pytorch.org blocked | Worked around: GGUF models from Docker Hub; CPU PyTorch from conda-forge | None needed |
| Disk (~40 GB per session) | Full ORFS place and route for the RISC-V core and the accelerator top level | Run locally (see the PDF) |
| Real deployment, domains, GitHub App, OAuth apps, SMTP, LLM API keys | TANDEM, VerifyCloud, Campus AI OS, MCP Fabric, EvalOps | Accounts and secrets (see PDF) |
| Real users (student beta, RTL-engineer beta) | Campus AI OS, VerifyCloud | Recruit (see PDF) |
| Internship records | Resume section 5 | See RESUME_EVIDENCE.md |
