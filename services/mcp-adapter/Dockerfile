FROM python:3.14-slim
WORKDIR /app
COPY services/mcp-adapter/requirements.lock .
RUN pip install --no-cache-dir -r requirements.lock && useradd --uid 10001 --create-home sentinel
COPY services/mcp-adapter/app app
COPY policies/examples/tool-registry-v1.json /app/registry.json
ENV SENTINEL_REGISTRY_PATH=/app/registry.json PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
USER 10001
CMD ["uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8100", "--no-access-log", "--log-level", "critical"]
