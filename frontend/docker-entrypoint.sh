#!/bin/bash
# Render-friendly entrypoint: substitute ${API_UPSTREAM} in the nginx template
# at runtime, then exec nginx in the foreground.
set -e

# Default upstream if not supplied.
: "${API_UPSTREAM:=api:8080}"

mkdir -p /etc/nginx/conf.d
envsubst '${API_UPSTREAM}' < /etc/nginx/templates/default.conf.template \
  > /etc/nginx/conf.d/default.conf

echo "[entrypoint] nginx config resolved with API_UPSTREAM=${API_UPSTREAM}"

exec "$@"
