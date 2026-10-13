# Not built in the build environment (Docker Hub unreachable). Expects the
# groundwork repository next to this one in the build context's parent.
FROM node:22-slim AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend ./
RUN npm run build

FROM python:3.12-slim
RUN useradd --create-home --uid 10001 app
WORKDIR /app
COPY groundwork /deps/groundwork
RUN pip install --no-cache-dir /deps/groundwork
COPY backend ./backend
RUN pip install --no-cache-dir ./backend
COPY --from=web /web/dist ./static
ENV CAMPUS_STATIC_DIR=/app/static
USER app
EXPOSE 8000
CMD ["uvicorn", "--factory", "campus.app:create_app", "--host", "0.0.0.0", "--port", "8000"]
