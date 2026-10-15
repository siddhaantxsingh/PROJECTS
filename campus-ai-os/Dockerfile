# syntax=docker/dockerfile:1
# Campus AI OS image. Groundwork (sibling directory in this monorepo) is passed
# as a named build context; scripts/docker_build.sh runs:
#   docker build --build-context groundwork=../groundwork -t campus-ai-os .
# Behind a TLS-intercepting proxy, its CA can be passed as the optional build
# secret "ca" (not stored in the image). Built and smoke-tested 2026-10-15
# (docs/evidence/2026-10-15/).
FROM node:22-slim AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=secret,id=ca,required=false \
    if [ -s /run/secrets/ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/ca; fi && npm ci
COPY frontend ./
RUN npm run build

FROM python:3.12-slim
RUN useradd --create-home --uid 10001 app
WORKDIR /app
COPY --from=groundwork pyproject.toml /deps/groundwork/pyproject.toml
COPY --from=groundwork groundwork /deps/groundwork/groundwork
COPY backend ./backend
RUN --mount=type=secret,id=ca,required=false \
    if [ -s /run/secrets/ca ]; then export PIP_CERT=/run/secrets/ca; fi \
    && pip install --no-cache-dir /deps/groundwork ./backend
COPY --from=web /web/dist ./static
ENV CAMPUS_STATIC_DIR=/app/static
USER app
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s CMD python -c "import urllib.request,sys;sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/healthz').status==200 else 1)"
CMD ["uvicorn", "--factory", "campus.app:create_app", "--host", "0.0.0.0", "--port", "8000"]
