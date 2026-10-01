FROM python:3.14.2-slim
WORKDIR /app
COPY services/inspection/requirements.lock /app/requirements.lock
RUN pip install --no-cache-dir -r requirements.lock
COPY contracts/schemas /app/contracts/schemas
COPY services/inspection/app /app/app
COPY services/inspection/models /app/models
ENV SENTINEL_SCHEMAS_DIR=/app/contracts/schemas
USER 10001:10001
EXPOSE 8000
CMD ["uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
