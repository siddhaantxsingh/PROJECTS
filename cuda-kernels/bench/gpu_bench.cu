// GPU benchmark: times every kernel with CUDA events and checks a sample of
// outputs against the CPU. Needs an NVIDIA GPU; on a machine without one it
// reports the CUDA error and exits with status 2.
//
// Build (clang): see `make gpu-bench`. Run: build/gpu_bench [out.json]
#include <cuda_runtime.h>

#include <cmath>
#include <cstdio>
#include <functional>
#include <random>
#include <string>
#include <vector>

#include "kc/attention.cuh"
#include "kc/gemm.cuh"
#include "kc/reduce.cuh"
#include "kc/softmax.cuh"
#include "kc/transpose.cuh"

#define CK(x)                                                                                  \
    do {                                                                                       \
        cudaError_t e = (x);                                                                   \
        if (e != cudaSuccess) {                                                                \
            std::fprintf(stderr, "%s:%d: %s: %s\n", __FILE__, __LINE__, #x, cudaGetErrorString(e)); \
            std::exit(2);                                                                      \
        }                                                                                      \
    } while (0)

struct Buf {
    float* d = nullptr;
    size_t n = 0;
    explicit Buf(size_t n_, std::mt19937* rng = nullptr) : n(n_) {
        CK(cudaMalloc(&d, n * sizeof(float)));
        std::vector<float> h(n, 0.0f);
        if (rng) {
            std::normal_distribution<float> nd;
            for (auto& v : h) v = nd(*rng);
        }
        CK(cudaMemcpy(d, h.data(), n * sizeof(float), cudaMemcpyHostToDevice));
    }
    std::vector<float> host() const {
        std::vector<float> h(n);
        CK(cudaMemcpy(h.data(), d, n * sizeof(float), cudaMemcpyDeviceToHost));
        return h;
    }
    ~Buf() { cudaFree(d); }
};

static double time_ms(const std::function<void()>& f, int iters = 20) {
    f();
    CK(cudaGetLastError());
    CK(cudaDeviceSynchronize());
    cudaEvent_t a, b;
    CK(cudaEventCreate(&a));
    CK(cudaEventCreate(&b));
    CK(cudaEventRecord(a));
    for (int i = 0; i < iters; i++) f();
    CK(cudaEventRecord(b));
    CK(cudaEventSynchronize(b));
    float ms = 0;
    CK(cudaEventElapsedTime(&ms, a, b));
    return ms / iters;
}

static std::vector<std::string> rows;
static void emit(const char* group, const char* kernel, const char* shape, double ms, double flops, double bytes, double max_err) {
    char buf[512];
    std::snprintf(buf, sizeof buf,
                  "{\"group\":\"%s\",\"kernel\":\"%s\",\"shape\":\"%s\",\"ms\":%.5f,\"gflops\":%.1f,\"gbytes_per_s\":%.1f,\"max_err\":%.3g}", group,
                  kernel, shape, ms, flops / ms / 1e6, bytes / ms / 1e6, max_err);
    rows.push_back(buf);
    std::printf("%-10s %-32s %-18s %9.4f ms %9.1f GFLOP/s %8.1f GB/s err %.2g\n", group, kernel, shape, ms, flops / ms / 1e6, bytes / ms / 1e6,
                max_err);
}

int main(int argc, char** argv) {
    int ndev = 0;
    CK(cudaGetDeviceCount(&ndev));
    cudaDeviceProp prop{};
    CK(cudaGetDeviceProperties(&prop, 0));
    std::printf("device: %s (sm_%d%d, %d SMs)\n", prop.name, prop.major, prop.minor, prop.multiProcessorCount);
    std::mt19937 rng(1);

    {  // sgemm 4096^3
        int n = 4096;
        Buf A(size_t(n) * n, &rng), B(size_t(n) * n, &rng), C(size_t(n) * n);
        auto hA = A.host(), hB = B.host();
        auto check = [&] {
            auto hC = C.host();
            double worst = 0;
            for (int s = 0; s < 64; s++) {
                int i = (s * 977) % n, j = (s * 131) % n;
                double ref = 0;
                for (int k = 0; k < n; k++) ref += double(hA[size_t(i) * n + k]) * hB[size_t(k) * n + j];
                worst = std::max(worst, std::fabs(hC[size_t(i) * n + j] - ref));
            }
            return worst;
        };
        double f = 2.0 * n * n * n, b = 12.0 * n * n;
        double ms = time_ms([&] { kc::sgemm_naive(n, n, n, 1, A.d, B.d, 0, C.d); }, 3);
        emit("sgemm", "naive", "4096^3", ms, f, b, check());
        ms = time_ms([&] { kc::sgemm_smem(n, n, n, 1, A.d, B.d, 0, C.d); }, 5);
        emit("sgemm", "smem 32x32", "4096^3", ms, f, b, check());
        ms = time_ms([&] { kc::sgemm_reg<false>(n, n, n, 1, A.d, B.d, 0, C.d); });
        emit("sgemm", "register 8x8", "4096^3", ms, f, b, check());
        ms = time_ms([&] { kc::sgemm_reg<true>(n, n, n, 1, A.d, B.d, 0, C.d); });
        emit("sgemm", "register 8x8, split", "4096^3", ms, f, b, check());
    }
    {  // transpose 8192^2
        int n = 8192;
        Buf in(size_t(n) * n, &rng), out(size_t(n) * n);
        double b = 8.0 * n * n;
        emit("transpose", "naive", "8192^2", time_ms([&] { kc::transpose_naive(in.d, out.d, n, n); }), 0, b, 0);
        emit("transpose", "smem tile", "8192^2", time_ms([&] { kc::transpose_tiled(in.d, out.d, n, n); }), 0, b, 0);
        emit("transpose", "smem tile, padded", "8192^2", time_ms([&] { kc::transpose_padded(in.d, out.d, n, n); }), 0, b, 0);
    }
    {  // reduce 2^26
        int n = 1 << 26;
        Buf x(n, &rng), out(1), part(kc::REDUCE_BLOCKS);
        double b = 4.0 * n;
        emit("reduce", "atomic", "2^26", time_ms([&] { kc::reduce_atomic(x.d, n, out.d); }, 3), n, b, 0);
        emit("reduce", "smem tree", "2^26", time_ms([&] { kc::reduce_smem(x.d, n, out.d); }), n, b, 0);
        emit("reduce", "float4 + shuffles, two-pass", "2^26", time_ms([&] { kc::reduce_two_pass(x.d, n, part.d, out.d); }), n, b, 0);
    }
    {  // row kernels 16384 x 4096
        int r = 16384, c = 4096;
        Buf x(size_t(r) * c, &rng), y(size_t(r) * c), g(c, &rng), bb(c, &rng);
        double b = 8.0 * r * c;
        emit("rows", "softmax (online)", "16384x4096", time_ms([&] { kc::softmax(x.d, y.d, r, c); }), 0, b, 0);
        emit("rows", "layernorm (Welford)", "16384x4096", time_ms([&] { kc::layernorm<false>(x.d, g.d, bb.d, y.d, r, c); }), 0, b, 0);
    }
    {  // attention BH=32, N=2048, D=64
        int bh = 32, n = 2048;
        size_t sz = size_t(bh) * n * 64;
        Buf Q(sz, &rng), K(sz, &rng), V(sz, &rng), O(sz), S(size_t(bh) * n * n);
        double f = 4.0 * bh * n * double(n) * 64, b = 16.0 * sz;
        emit("attention", "flash (tiled, online softmax)", "32x2048x64", time_ms([&] { kc::flash_attention<64>(Q.d, K.d, V.d, O.d, bh, n, false); }, 5), f,
             b, 0);
        emit("attention", "naive (scores in global memory)", "32x2048x64",
             time_ms([&] { kc::attention_naive<64>(Q.d, K.d, V.d, O.d, S.d, bh, n, false); }, 2), f, b, 0);
    }
    if (argc > 1) {
        FILE* fp = std::fopen(argv[1], "w");
        std::fprintf(fp, "{\"device\":\"%s\",\"results\":[", prop.name);
        for (size_t i = 0; i < rows.size(); i++) std::fprintf(fp, "%s%s", i ? "," : "", rows[i].c_str());
        std::fprintf(fp, "]}\n");
        std::fclose(fp);
    }
}
