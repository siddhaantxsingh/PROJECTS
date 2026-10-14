# Resource budget

* **LLM usage.** The platform does not show the remaining weekly allowance to the agent, so no balance is reported here. Work is ordered by value: the core differentiators and the highest-severity gaps come first.
* **Container.** 4 vCPU, 15 GiB RAM, ~20 GB of free disk at the start of this round. Several agents run at once, so benchmark logs record load averages, and numbers measured under contention are labelled as such.
* **Large downloads.** The ORFS Docker image (several GB) and GGUF models (~1.5 GB in total) are kept outside git in `/home/user/models` and the Docker cache.
* **Money spent:** none. No cloud resources, domains or paid APIs were used.
