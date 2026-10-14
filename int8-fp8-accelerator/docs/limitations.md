# Limitations

* **No timing or physical design.** Area comes from Yosys (generic gates and ECP5 mapping) for the PE and the post unit only. The top level, with 48 KiB of behavioural scratchpad, was not synthesised. The combinational FP32 post unit (80-bit leading-one detector and shifter) and the 32×32 requantisation multiplier would need pipelining for a real clock target.
* **Exact FP8 accumulation is expensive:** about 21× the PE LUTs of INT8-only. This is a deliberate, documented trade (correctly rounded, order-independent results). A cheaper FP32-accumulating PE was not built for comparison.
* **No overlap of drain and compute:** 3N + 3 cycles of overhead per tile. Double-buffered accumulators would fix this.
* **Fixed maxima:** M, N ≤ 64 and K ≤ 256 (scratchpad parameters), with K ≤ 4,096 for the exactness proof. Larger GEMMs must be tiled by the host. There is no DMA or AXI interface; the host writes the scratchpads word by word through a simple port.
* **FP8 bias / scaling is not supported.** FP8 outputs are raw FP32. INT8 requantisation is per tensor (no per-channel multipliers).
* **Simulation only:** no FPGA bring-up.
