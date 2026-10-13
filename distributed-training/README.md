# dtrain: distributed training from scratch

`dtrain` is data-parallel training of a small GPT built without a deep-learning framework. It provides:

- a reverse-mode **autograd engine** on NumPy;
- **collective communication over TCP**: ring and direct all-reduce, reduce-scatter, all-gather and broadcast, with failure detection;
- **DDP** with gradient bucketing, comm/compute overlap and gradient accumulation;
- **ZeRO-1** optimizer-state sharding;
- **sharded, checksummed, atomic checkpoints**;
- an **elastic launcher** that restarts the group after a worker crash.

Training resumes bit-identically after a crash.

**Status:**
- **Done:** verified on one 4-vCPU machine; 36 tests pass.
- **Not done:**
  - CPU only (no GPU here);
  - no PyTorch (its CPU wheels were unreachable);
  - multi-host is untested.

See [limitations](docs/limitations.md).

## Results ([docs/benchmarks.md](docs/benchmarks.md))

| | |
|---|---|
| DDP vs one process, same global batch | equal to ≤ 1e-10 (float64 tests); max loss difference 2.4e-7 over 300 float32 steps |
| ZeRO-1 vs DDP | equal to ≤ 1e-10; optimizer state per rank cut 4× at 4 ranks |
| Crash at step 5 → auto-restart → resume | final parameters **bit-identical** to the run without a failure |
| Weak scaling, 1 → 4 ranks (4 vCPUs) | 3.37× (DDP), 3.60× (ZeRO-1) |
| Ring all-reduce, 64 MiB, 4 ranks | 0.76 GB/s bus bandwidth, 71 % of OpenMPI 4.1 on the same machine |
| Small messages | 10–50× OpenMPI's latency (Python overhead) |
| Overlapping all-reduce with backward | no gain (GIL contention), reported as a negative result |

## Quick start

```bash
pip install -e '.[dev]'
python -m pytest                                    # 36 tests, ~30 s
python -m dtrain.train --mode single --steps 200 --warmup 10            # downloads tiny-shakespeare (SHA-256 checked)
python -m dtrain.launch --nproc 4 -- python -m dtrain.train --mode ddp --steps 200 --warmup 10
python -m dtrain.launch --nproc 4 --max-restarts 2 -- python -m dtrain.train --mode zero \
    --steps 200 --ckpt-dir ckpt --ckpt-every 20 --resume --fail-at-step 50   # crash, restart, resume
```

Benchmarks: `benchmarks/allreduce_bench.py` (plus an `mpirun` baseline), `benchmarks/scaling.sh`, and `benchmarks/report.py <date>`. The commands are at the top of each file.

## Documentation

- [Architecture](docs/architecture.md)
- [Testing](docs/testing.md)
- [Benchmarks](docs/benchmarks.md)
- [Limitations](docs/limitations.md)
- [Requirements matrix](docs/requirements.md)
