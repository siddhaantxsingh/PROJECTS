// Functional tests: every kernel runs unchanged on the CPU SIMT emulator and
// is compared with a double-precision reference.
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

static int failures = 0, checks = 0;
#define CHECK(cond, ...)                                       \
    do {                                                       \
        checks++;                                              \
        if (!(cond)) {                                         \
            failures++;                                        \
            std::printf("FAIL %s:%d: ", __FILE__, __LINE__);   \
            std::printf(__VA_ARGS__);                          \
            std::printf("\n");                                 \
        }                                                      \
    } while (0)

static std::mt19937 rng(12345);
static std::vector<float> randn(size_t n, float mean = 0.0f, float sd = 1.0f) {
    std::normal_distribution<float> d(mean, sd);
    std::vector<float> v(n);
    for (auto& x : v) x = d(rng);
    return v;
}

// ------------------------------------------------------------------ reduce

static void test_reduce() {
    for (int n : {1, 3, 31, 1000, 4097, 100003, 1 << 20}) {
        auto x = randn(n);
        double ref = 0, absum = 0;
        for (float v : x) {
            ref += v;
            absum += std::fabs(v);
        }
        // fp32 bound: each element passes through at most
        // (serial terms per thread + tree depth) roundings
        auto bound = [&](double serial) { return (serial + 20) * 6e-8 * absum + 1e-30; };
        float out = 0;
        if (n <= 100003) {  // one block per 256 elements: slow to emulate beyond this
            kc::reduce_atomic(x.data(), n, &out);
            CHECK(std::fabs(out - ref) <= bound(n), "atomic n=%d got %.7g want %.7g", n, out, ref);
            out = 0;
            kc::reduce_smem(x.data(), n, &out);
            CHECK(std::fabs(out - ref) <= bound(n / 256.0 + 1), "smem n=%d got %.7g want %.7g", n, out, ref);
        }
        std::vector<float> partials(kc::REDUCE_BLOCKS);
        float a = 0, b = 0;
        kc::reduce_two_pass(x.data(), n, partials.data(), &a);
        kc::reduce_two_pass(x.data(), n, partials.data(), &b);
        CHECK(std::fabs(a - ref) <= bound(n / (4.0 * 256 * 256) * 4 + 2), "two-pass n=%d got %.7g want %.7g", n, a, ref);
        CHECK(std::memcmp(&a, &b, 4) == 0, "two-pass is deterministic");
    }
}

// ------------------------------------------------------------------ transpose

static void test_transpose() {
    for (auto [r, c] : std::vector<std::pair<int, int>>{{1, 1}, {32, 32}, {33, 65}, {100, 7}, {256, 512}}) {
        auto in = randn(size_t(r) * c);
        std::vector<std::function<void(const float*, float*, int, int)>> fs = {kc::transpose_naive, kc::transpose_tiled, kc::transpose_padded};
        for (size_t f = 0; f < fs.size(); f++) {
            std::vector<float> out(in.size(), -1.0f);
            fs[f](in.data(), out.data(), r, c);
            bool ok = true;
            for (int i = 0; i < r; i++)
                for (int j = 0; j < c; j++) ok &= out[size_t(j) * r + i] == in[size_t(i) * c + j];
            CHECK(ok, "transpose variant %zu %dx%d", f, r, c);
        }
    }
}

// ------------------------------------------------------------------ gemm

static void test_gemm() {
    using F = std::function<void(int, int, int, float, const float*, const float*, float, float*)>;
    std::vector<std::pair<const char*, F>> fs = {
        {"naive", kc::sgemm_naive}, {"smem", kc::sgemm_smem}, {"reg", kc::sgemm_reg<false>}, {"reg-split", kc::sgemm_reg<true>}};
    struct S { int m, n, k; float alpha, beta; };
    for (S s : std::vector<S>{{1, 1, 1, 1, 0}, {128, 128, 8, 1, 0}, {129, 130, 17, 1, 0}, {64, 300, 64, 0.5f, 2.0f}, {256, 256, 256, 1, 1}, {5, 257, 3, -1, 0}}) {
        auto A = randn(size_t(s.m) * s.k), B = randn(size_t(s.k) * s.n), C0 = randn(size_t(s.m) * s.n);
        std::vector<double> ref(size_t(s.m) * s.n), mag(size_t(s.m) * s.n);
        for (int i = 0; i < s.m; i++)
            for (int j = 0; j < s.n; j++) {
                double acc = 0, a = 0;
                for (int k = 0; k < s.k; k++) {
                    acc += double(A[size_t(i) * s.k + k]) * B[size_t(k) * s.n + j];
                    a += std::fabs(double(A[size_t(i) * s.k + k]) * B[size_t(k) * s.n + j]);
                }
                ref[size_t(i) * s.n + j] = s.alpha * acc + s.beta * C0[size_t(i) * s.n + j];
                mag[size_t(i) * s.n + j] = std::fabs(s.alpha) * a + std::fabs(s.beta * C0[size_t(i) * s.n + j]);
            }
        for (auto& [name, f] : fs) {
            auto C = C0;
            f(s.m, s.n, s.k, s.alpha, A.data(), B.data(), s.beta, C.data());
            double worst = 0;
            for (size_t i = 0; i < C.size(); i++) worst = std::max(worst, std::fabs(C[i] - ref[i]) / ((s.k + 2) * 6e-8 * mag[i] + 1e-30));
            CHECK(worst <= 1.0, "%s %dx%dx%d: error %.2f x bound", name, s.m, s.n, s.k, worst);
        }
    }
}

// ------------------------------------------------------------------ softmax / layernorm

static void test_softmax() {
    for (auto [rows, cols] : std::vector<std::pair<int, int>>{{1, 1}, {3, 5}, {7, 32}, {9, 1000}, {64, 4096}}) {
        auto x = randn(size_t(rows) * cols, 0.0f, 10.0f);
        x[0] = 3e38f;  // would overflow exp() in a naive implementation
        if (cols > 2) x[1] = -INFINITY;
        std::vector<float> y(x.size());
        kc::softmax(x.data(), y.data(), rows, cols);
        double worst = 0;
        for (int r = 0; r < rows; r++) {
            double m = -INFINITY, s = 0;
            for (int c = 0; c < cols; c++) m = std::max(m, double(x[size_t(r) * cols + c]));
            for (int c = 0; c < cols; c++) s += std::exp(double(x[size_t(r) * cols + c]) - m);
            for (int c = 0; c < cols; c++) {
                double want = std::exp(double(x[size_t(r) * cols + c]) - m) / s;
                worst = std::max(worst, std::fabs(y[size_t(r) * cols + c] - want) / (want * 1e-5 + 1e-12));
            }
        }
        CHECK(worst <= 1.0, "softmax %dx%d error %.2f x bound", rows, cols, worst);
    }
    std::vector<float> x = {-INFINITY, -INFINITY, -INFINITY}, y(3);
    kc::softmax(x.data(), y.data(), 1, 3);
    CHECK(std::isnan(y[0]), "all -inf row gives NaN");
}

static void test_layernorm() {
    for (auto [rows, cols, mean] : std::vector<std::tuple<int, int, float>>{{1, 1, 0}, {5, 17, 0}, {8, 768, 0}, {4, 4096, 3}, {8, 1024, 1e4f}}) {
        auto x = randn(size_t(rows) * cols, mean, 1.0f);
        auto g = randn(cols, 1.0f, 0.1f), b = randn(cols, 0.0f, 0.1f);
        std::vector<float> y(x.size()), yn(x.size());
        kc::layernorm<false>(x.data(), g.data(), b.data(), y.data(), rows, cols);
        kc::layernorm<true>(x.data(), g.data(), b.data(), yn.data(), rows, cols);
        double worst = 0, worst_naive = 0;
        for (int r = 0; r < rows; r++) {
            double mu = 0, var = 0;
            for (int c = 0; c < cols; c++) mu += x[size_t(r) * cols + c];
            mu /= cols;
            for (int c = 0; c < cols; c++) var += std::pow(x[size_t(r) * cols + c] - mu, 2);
            var /= cols;
            for (int c = 0; c < cols; c++) {
                double want = (x[size_t(r) * cols + c] - mu) / std::sqrt(var + 1e-5) * g[c] + b[c];
                // fp32 input rounding alone moves (x - mean) by |x| * 6e-8
                double tol = 1e-4 + 4 * std::fabs(mean) * 6e-8 / std::sqrt(var + 1e-5) * std::fabs(g[c]);
                worst = std::max(worst, std::fabs(y[size_t(r) * cols + c] - want) / tol);
                worst_naive = std::max(worst_naive, std::fabs(yn[size_t(r) * cols + c] - want) / tol);
            }
        }
        CHECK(worst <= 1.0, "layernorm %dx%d mean %g: Welford error %.2f x bound", rows, cols, mean, worst);
        if (mean >= 1e4f) CHECK(worst_naive > 100, "naive E[x^2]-E[x]^2 should break down at mean %g (error %.1f x)", mean, worst_naive);
        if (mean == 0) CHECK(worst_naive <= 1.0, "naive variant is fine for centred data");
    }
}

// ------------------------------------------------------------------ attention

template <int D>
static void test_attention_d() {
    for (auto [bh, n, causal] : std::vector<std::tuple<int, int, bool>>{{1, 1, false}, {2, 37, false}, {2, 37, true}, {3, 130, true}, {1, 200, false}}) {
        size_t sz = size_t(bh) * n * D;
        auto Q = randn(sz), K = randn(sz), V = randn(sz);
        std::vector<float> O(sz), On(sz), S(size_t(bh) * n * n);
        kc::flash_attention<D>(Q.data(), K.data(), V.data(), O.data(), bh, n, causal);
        kc::attention_naive<D>(Q.data(), K.data(), V.data(), On.data(), S.data(), bh, n, causal);
        double worst = 0, worst_n = 0;
        double scale = 1.0 / std::sqrt(double(D));
        for (int h = 0; h < bh; h++)
            for (int i = 0; i < n; i++) {
                std::vector<double> s(n);
                double m = -INFINITY;
                int end = causal ? i + 1 : n;
                for (int j = 0; j < end; j++) {
                    double acc = 0;
                    for (int d = 0; d < D; d++) acc += double(Q[(size_t(h) * n + i) * D + d]) * K[(size_t(h) * n + j) * D + d];
                    s[j] = acc * scale;
                    m = std::max(m, s[j]);
                }
                double l = 0;
                for (int j = 0; j < end; j++) l += (s[j] = std::exp(s[j] - m));
                for (int d = 0; d < D; d++) {
                    double o = 0;
                    for (int j = 0; j < end; j++) o += s[j] * V[(size_t(h) * n + j) * D + d];
                    o /= l;
                    size_t idx = (size_t(h) * n + i) * D + d;
                    worst = std::max(worst, std::fabs(O[idx] - o));
                    worst_n = std::max(worst_n, std::fabs(On[idx] - o));
                }
            }
        CHECK(worst < 2e-5, "flash D=%d bh=%d n=%d causal=%d max abs error %.3g", D, bh, n, int(causal), worst);
        CHECK(worst_n < 2e-5, "naive attention D=%d n=%d max abs error %.3g", D, n, worst_n);
    }
}

int main(int argc, char** argv) {
    std::vector<std::pair<std::string, void (*)()>> tests = {
        {"reduce", test_reduce},       {"transpose", test_transpose}, {"gemm", test_gemm},
        {"softmax", test_softmax},     {"layernorm", test_layernorm}, {"attention_d32", test_attention_d<32>},
        {"attention_d64", test_attention_d<64>},
    };
    for (auto& [name, f] : tests) {
        if (argc > 1 && name.find(argv[1]) == std::string::npos) continue;
        int before = failures, c0 = checks;
        f();
        std::printf("%-16s %s (%d checks)\n", name.c_str(), failures == before ? "ok" : "FAILED", checks - c0);
    }
    std::printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
