# Requirements matrix — Project 16 (Industrial Edge)

| Requirement | Status | Evidence |
|---|---|---|
| Industrial protocol ingestion (Modbus TCP) | VERIFIED | from-scratch client/server; tests; e2e |
| PLC simulator | VERIFIED | `internal/plcsim`, `cmd/plcsim` |
| Store-and-forward with durability | VERIFIED | crash-at-every-byte test; e2e SIGKILL with no gaps |
| Uplink to cloud (MQTT) | VERIFIED | from-scratch MQTT 3.1.1 against mosquitto; QoS 1 |
| No data loss across broker outages | VERIFIED | e2e: every seq delivered |
| Edge analytics: compression with error bound | VERIFIED | property test; benchmark max error ≤ deviation |
| Anomaly detection | VERIFIED | EWMA tests; spikes detected in e2e |
| Alarm management (ISA-18.2 states, hysteresis, delays) | VERIFIED | state machine tests; ack API |
| Observability (metrics, health) | VERIFIED | `/metrics`, `/healthz`; asserted in e2e |
| Throughput / latency measurement | VERIFIED | 1,000 tags at 10 Hz, 10k records/s, p99 127 ms |
| Configuration validation | VERIFIED | tests |
| Security (TLS, authentication) | NOT IMPLEMENTED | documented |
| OPC UA, Modbus RTU, other protocols | NOT IMPLEMENTED | — |
| Docker / deployment | NOT IMPLEMENTED | — |
| CI | VERIFIED | `.github/workflows/industrial-edge.yml` green on GitHub Actions (run 38516430120), including e2e with mosquitto |
