FROM python:3.12-slim
RUN useradd --create-home --uid 10001 app
WORKDIR /app
COPY pyproject.toml ./
COPY groundwork ./groundwork
RUN pip install --no-cache-dir .
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD python -c "import urllib.request,sys;sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8080/healthz').status==200 else 1)"
CMD ["python", "-m", "groundwork", "serve", "--host", "0.0.0.0", "--port", "8080"]
