# Industrial Edge Gateway

This is an industrial IoT edge gateway in Go, using only the standard library. It:

1. polls PLCs over **Modbus TCP** (implemented from the spec);
2. runs **edge analytics**:
   - swinging-door compression with a proven error bound;
   - EWMA anomaly detection;
   - ISA-18.2 alarms;
3. buffers everything in a **crash-safe, disk-backed queue**;
4. forwards it over **MQTT 3.1.1 at QoS 1** (implemented from the spec), committing data only once the broker acknowledges it.

It loses nothing across broker outages and gateway crashes. A PLC simulator is included.

**Status:**
- **Done:** verified locally with a real broker (mosquitto) and a simulated plant.
- **Not done:**
  - no TLS or authentication;
  - Modbus TCP only;
  - not deployed.

See [limitations](docs/limitations.md).

## Results ([docs/benchmarks.md](docs/benchmarks.md))

| | |
|---|---|
| Broker outage + `kill -9` of the gateway + restart | every record delivered (no gaps); 3–9 duplicates from in-flight batches |
| 1,000 tags at 10 Hz (20 PLCs) → MQTT | 10,000 records/s sustained; end-to-end latency p50 42 ms, p99 127 ms; 0.59 cores including the simulated plant |
| Swinging-door compression | 38–105× on ramping signals at ±0.2–0.5, with the error bound held exactly (property-tested) |
| Durable queue | 733 k appends/s with 100 ms group commit (5.3 k/s fsync-per-record); 1 M-record recovery scan in 63 ms |
| Read coalescing | 50 tags → 1 Modbus request per poll |

## Run it

```bash
go build ./cmd/edgegw ./cmd/plcsim
./plcsim -listen 127.0.0.1:5020 -spike-every 500 &
mosquitto -p 1883 &
./edgegw -config configs/example.json          # metrics at http://127.0.0.1:9100/metrics
mosquitto_sub -t 'plant1/gw1/data' -q 1         # JSON batches
curl -X POST 'http://127.0.0.1:9100/alarms/ack?dev=tank1&tag=level'
```

Tests: `go test ./...` (MQTT and e2e need `mosquitto` on PATH or at `/usr/sbin/mosquitto`). Benchmarks: `go run ./cmd/edgebench -out bench/results/$(date +%F) -broker 127.0.0.1:1883`, then `python3 bench/report.py <date>`.

## Documentation

- [Architecture and failure behaviour](docs/architecture.md)
- [Testing](docs/testing.md)
- [Benchmarks](docs/benchmarks.md)
- [Limitations](docs/limitations.md)
- [Requirements matrix](docs/requirements.md)
