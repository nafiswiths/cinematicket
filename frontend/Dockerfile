# --- Build stage ---
FROM node:20-alpine AS build
WORKDIR /app

# Build-time API base URL. Setting this to "/api" makes the SPA rely on the
# nginx reverse proxy in this same image, so the browser never needs to know
# the backend's DNS name.
ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

COPY package.json package-lock.json* ./
RUN npm install --no-audit --no-fund

COPY . .
RUN npm run build

# --- Runtime stage: nginx serving the SPA + reverse-proxying /api to the backend ---
FROM nginx:1.27-alpine AS runtime

# Default upstream. Compose sets this to "api:8080" via docker-compose env;
# Render sets it to "cinemaseat-api.onrender.com:80" via the blueprint.
ENV API_UPSTREAM=api:8080

# Render supports envsubst at startup through a custom entrypoint so the
# ${API_UPSTREAM} placeholder in nginx.conf resolves to the runtime value.
# We use a small entrypoint.sh that envsubs the config and then execs nginx.
RUN apk add --no-cache bash
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/templates/default.conf.template

EXPOSE 80
ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]
