# Build: docker build -t vectordb .   (from this directory)
FROM rust:1.89-slim AS build
WORKDIR /src
COPY Cargo.toml Cargo.lock build.rs ./
COPY proto ./proto
COPY src ./src
RUN cargo build --locked --release --bin vectordb

FROM debian:bookworm-slim
RUN useradd --create-home --uid 10001 vdb && mkdir /data && chown vdb /data
COPY --from=build /src/target/release/vectordb /usr/local/bin/vectordb
USER vdb
VOLUME /data
EXPOSE 6333 6334
# no curl in the image; the gRPC/REST ports are probed by the orchestrator
CMD ["vectordb", "serve", "--data", "/data", "--http", "0.0.0.0:6333", "--grpc", "0.0.0.0:6334", "--sync", "always"]
