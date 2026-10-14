# Limitations

* **Heartbeat path serialisation.** Update results and version changes are fsynced to the journal while holding the store's global lock. At 2,000 devices and 1 s heartbeats this shows up as 0.6–1.4 s p95–p99 call latencies during a rollout. Group commit, or a lock-free soft-state path, is the obvious fix.
* **Single control-plane instance** with local-disk state: no high availability and no replication. The store is sized for tens of thousands of devices, not millions; the state lives in memory.
* **Full-image updates only:** no delta updates or compression. An artifact is one executable; there is no multi-file bundle format.
* **The health check is generic:** the process stays up and `app health` passes. Application-specific health metrics feeding the rollout gate (error rates, crash reports) are not implemented.
* **The A/B boot is emulated in user space.** The agent plays the bootloader's role, with its boot counter in `boot.json`. Real OS/firmware updates need integration with the platform's bootloader (U-Boot/GRUB) or a tool like RAUC/Mender. The state machine here is the same idea.
* **No key rotation or revocation.** Neither the release key nor device keys can be rotated or revoked, and there is no TUF-style metadata (expiry, thresholds). Admin access is a single shared bearer token: no RBAC, no audit log beyond the journal.
* **Small fleets with tiny canary percentages** can have an empty first wave. The gate then advances, and the next wave acts as the canary.
* **Not deployed;** no TLS termination is configured. Run behind a TLS proxy.
