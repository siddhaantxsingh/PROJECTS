# Limitations

* **Protocols:** Modbus TCP only (no Modbus RTU/serial, OPC UA, EtherNet/IP or S7). MQTT 3.1.1 at QoS 0/1 only, with no TLS or authentication: the uplink sends credentials and data in clear text. A production deployment needs TLS and broker ACLs (MQTT 5 / Sparkplug B would be the next step).
* **Writes to PLCs** are implemented in the client but not exposed through the gateway API. There is no control path, by design.
* **At-least-once, not exactly-once:** consumers must de-duplicate on `seq`. Gateway-side state is not de-duplicated across restarts: swinging-door state and alarm state are in memory, so after a crash compression restarts from the next sample and alarms re-evaluate from NORMAL.
* **The disk cap drops whole segments.** The budget can be exceeded by up to one segment (`segment_bytes`), so `max_bytes` should be at least 2 × `segment_bytes`.
* **Single gateway process:** no clustering or hot standby.
* **Benchmarks are single-host** with a simulated plant; latency excludes the PLC's own scan time. Real PLCs are usually slower to answer than the simulator.
* **No Docker image or service unit was built or tested.**
