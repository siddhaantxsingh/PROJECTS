# Resource budget

* **LLM usage.** The platform does not show the remaining weekly allowance to the agent, so no balance is reported here. Work is ordered by value: the core differentiators and the highest-severity gaps come first.
* **Container.** 4 vCPU, 15 GiB RAM, ~20 GB of free disk at the start of this round. Several agents run at once, so benchmark logs record load averages, and numbers measured under contention are labelled as such.
* **Large downloads.** The ORFS Docker image (several GB) and GGUF models (~1.5 GB in total) are kept outside git in `/home/user/models` and the Docker cache.
* **Money spent:** none. No cloud resources, domains or paid APIs were used.

## What actually happened

* Up to 10 agents ran in parallel. Each usage window lasted about 3–4 hours before the session limit stopped all agents. The work spanned 6 such windows, and each time the agents were resumed from where they stopped.
* Disk filled to 100 % once, while models and toolchains were being downloaded. Caches and build outputs were pruned, and the ORFS image was deleted after use; this is why the RISC-V core got only a reduced flow.
* No money was spent.
