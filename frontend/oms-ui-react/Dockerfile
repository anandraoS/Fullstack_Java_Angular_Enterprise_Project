# Multi-stage build: Node builds the React bundle, an unprivileged NGINX serves it and proxies /api to the gateway.
# Same runtime contract as frontend/oms-ui/Dockerfile (port 8080, uid 101, API_GATEWAY_URL / OMS_API_BASE_URL).
# NOT built in the development environment of this repository (Docker is not installed there): treat as untested.

# ---- build stage ---------------------------------------------------------------------------------------------------
FROM node:24-alpine AS build
WORKDIR /app

# Dependencies first so the layer is cached until the package files change.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
# `npm run build` = tsc -b (strict type check) + vite build; fails the image build on any type error.
RUN npm run build

# ---- runtime stage -------------------------------------------------------------------------------------------------
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime

# Runtime configuration (no rebuild needed):
#   API_GATEWAY_URL  upstream that /api/ is proxied to (server side)
#   OMS_API_BASE_URL apiBaseUrl handed to the browser through /config.json (normally the same-origin /api/v1)
ENV API_GATEWAY_URL=http://api-gateway:18080 \
    OMS_API_BASE_URL=/api/v1 \
    NGINX_ENVSUBST_FILTER="API_GATEWAY_URL|OMS_API_BASE_URL"

# The image entrypoint runs envsubst over /etc/nginx/templates/*.template and writes /etc/nginx/conf.d/*.
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

# Runs as the non-root user (uid 101) of the base image and listens on an unprivileged port.
USER 101
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
