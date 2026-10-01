#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
services/gateway/mvnw -q -f services/gateway/pom.xml test
(cd services/inspection && .venv/bin/python -m pytest -q tests)
(cd services/mcp-adapter && .venv/bin/python -m pytest -q tests)
services/inspection/.venv/bin/python -m pytest -q tests/unit
(cd services/console && npm run format:check && npm run build && npm test)
printf '%s\n' 'Gateway, inspection, MCP, setup, and console checks passed. No hosted model calls.'
