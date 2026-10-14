# EdgeFleet: fleet management and safe OTA updates

EdgeFleet is a control plane, a device agent and a CLI for updating fleets of edge devices safely, written in Go with only the standard library. It provides:

- **signed releases**, verified on the device;
- **Ed25519 device identity** with replay-safe signed requests;
- **A/B installs** with trial boots, health checks and **automatic rollback**;
- **staged rollouts**, whose health gates halt a bad release at the canary and roll it back.

The state on both sides survives a crash at any step.

**Status:**
- **Done:** verified locally, including the real binaries running real executable artifacts, and a 2,000-device simulation.
- **Not done:**
  - single control-plane instance;
  - A/B boot emulated in user space (no bootloader integration);
  - not deployed.

See [limitations](docs/limitations.md).

## Results ([docs/benchmarks.md](docs/benchmarks.md))

| | |
|---|---|
| 2,000 devices, good release, waves 1/10/50/100 % | fleet fully updated in 10.4 s |
| Bad release (every trial fails) | halted at the canary after **15 of 2,000** devices tried it; all rolled back |
| Flaky release (3 % failures, 10 % limit) | completes; the 46 failed devices stay on the old version |
| Crash at every update step | device always bootable, then converges (tests) |
| Tampered artifact / unsigned upload / replayed request | rejected (tests) |
| Plain heartbeat load | ~1,800/s, p99 17 ms (4 vCPUs shared with 2,000 simulated devices) |

## Quick start

```bash
go build ./cmd/...
PUB=$(./fleetctl keygen -out release.key)
export FLEET_ADMIN_TOKEN=$(head -c 24 /dev/urandom | base64)
./fleetd -data fleet-data -release-pubkey "$PUB" -heartbeat-sec 5 &
./fleetctl release -key release.key -version 1.0 -file app-v1     # the version devices ship with
TOKEN=$(./fleetctl token | sed -n 's/.*"token": "\(.*\)".*/\1/p')
./fleet-agent -id dev-1 -state dev-1 -token "$TOKEN" -release-pubkey "$PUB" -exec &
./fleetctl release -key release.key -version 2.0 -file app-v2
./fleetctl rollout -version 2.0 -waves 10,50,100 -max-failure 0.1 -bake 10m
./fleetctl status <rollout-id>
```

An application is an executable that supports `app run` (the long-running service) and `app health` (exit 0 when healthy). Run `go test ./...` for the tests and `go run ./cmd/fleetsim -n 2000` for the fleet simulation.

## Documentation

- [Architecture, trust model, update state machine](docs/architecture.md)
- [Testing](docs/testing.md)
- [Benchmarks](docs/benchmarks.md)
- [Limitations](docs/limitations.md)
- [Requirements matrix](docs/requirements.md)
