# Limitations

* **No real GPUs.** This was built on a machine without GPUs.
  * The agent reports a configured number of fake devices and sets `CUDA_VISIBLE_DEVICES`.
  * Health checks (NVML/DCGM: ECC errors, Xid events, thermals) are replaced by a file-based failure injector.
  * Nothing here has run a CUDA program.
* **The topology model is two-level** (NVLink island inside a node, then nodes). There is no notion of racks, rail-optimised networks, spine oversubscription or PCIe/NUMA affinity.
* **The simulator's slow-down factors (1.1× across islands, 1.3× across extra nodes) are assumptions**, not measurements. The placement-quality metrics do not depend on them, and one run in [benchmarks.md](benchmarks.md) removes them. The workload is synthetic, not a production trace.
* **Fencing is missing.**
  * When a node is declared down, its jobs are requeued at once.
  * If the node was only partitioned, its agent keeps running the old attempt until a heartbeat gets through and the reply no longer lists the attempt; the agent then kills it.
  * Meanwhile the job can run twice. Jobs must tolerate this, for example by checkpointing per attempt (`NEBULA_ATTEMPT`).
  * A production system would have the agent stop its jobs after its own lease expires.
* **A gang job's completion is rank 0's exit code.** Ranks that exit 0 before rank 0 are not restarted. A non-zero exit on any rank fails the whole job immediately.
* **Preemption kills; it does not checkpoint.** A preempted job restarts from scratch unless its command checkpoints by itself.
* **The control plane is one process with one lock.**
  * Every state change is fsynced while the lock is held.
  * With about 10,000 queued jobs a scheduling pass takes tens of milliseconds, during which API calls wait.
  * Handlers wait for the lock on Tokio worker threads. In the overload benchmark, p99 latency reaches about 1 s ([benchmarks.md](benchmarks.md)).
  * There is no HA or replication. A restart keeps all state, but the API is unavailable while the process is down.
* **The job history grows without bound.** Finished jobs are never pruned from the state or the snapshot.
* **Security is minimal.**
  * There are static bearer tokens from the config file, compared in constant time. One shared node token is used by all agents, with no per-node identity or mTLS.
  * There is no TLS: put it behind a TLS-terminating proxy.
  * Commands run as the agent's user, with no container isolation, cgroups or GPU memory limits.
* **Usage is metered but not billed.** There are GPU-seconds per job and per tenant, but no prices, invoices or budgets.
