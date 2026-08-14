# syntax=docker/dockerfile:1
#
# Self-contained build: clones the repo at build time rather than using the
# local build context, so `docker build` only needs this file to produce a
# runnable image. Override REPO_URL/GIT_REF to build a different fork/ref.
# Repo was renamed from Scotland-Yard to HuntingMrX on GitHub — if this
# clone starts failing, check whether it's moved again.
#
#   docker build --build-arg GIT_REF=v0.1.0 -t hunting-mrx-wellington:v0.1.0 .
#
# NOTE on path prefixes: pass --build-arg BASE_PATH=/mrx to deploy this
# under https://host/mrx/ instead of the domain root (e.g. a university
# server that only hands out a path, not a subdomain). See the ARG BASE_PATH
# comments in stages 3/4 — it's baked into the image, not runtime-overridable
# via `docker run -e`, so rebuild to change it. Unset reproduces today's
# root-path behavior exactly.
#
# NOTE on caching: Docker caches the `git clone` layer by its command text
# (the ARG values), not by what's actually on the remote — rebuilding with
# the same GIT_REF reuses the old clone even if that branch has new commits.
# Always pass a specific tag/commit as GIT_REF for a real deploy; "main" is
# only a sane default for local experimentation.
#
# NOTE on private repos: this uses an anonymous HTTPS clone, which only
# works if the repo is public. A private repo needs credentials passed in
# some way that doesn't get baked into an image layer — e.g. a BuildKit
# `--secret` mount or `--ssh` agent forwarding — which isn't set up here.

# ---- Stage 1: fetch source --------------------------------------------------
FROM alpine/git:latest AS clone
ARG REPO_URL=https://github.com/Ming-Bao/HuntingMrX.git
ARG GIT_REF=main
WORKDIR /src
# `git clone --branch` only accepts a branch/tag name, not a raw commit SHA —
# using init+fetch+checkout instead supports all three, since `git fetch`
# (unlike `clone --branch`) can fetch an arbitrary reachable commit from
# GitHub directly.
RUN git init -q && \
    git remote add origin "${REPO_URL}" && \
    git fetch --depth 1 origin "${GIT_REF}" && \
    git checkout -q FETCH_HEAD

# ---- Stage 2: build the backend (Java 21 / Spring Boot / Maven) ------------
FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /build
COPY --from=clone /src/backend/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY --from=clone /src/backend/src ./src
RUN mvn -B -q package -DskipTests

# ---- Stage 3: build the frontend (Vue 3 / Vite) ----------------------------
FROM node:22-alpine AS frontend-build
# Set to serve this deploy under a URL path prefix instead of the domain
# root — e.g. --build-arg BASE_PATH=/mrx for a university server that only
# gives out https://host/mrx/. Threaded through to the Vite build (bakes
# asset paths + router base), the backend's context-path (below), and the
# generated nginx config. Empty (the default) reproduces today's root-path
# behavior exactly — must start with "/" and have no trailing slash when set.
ARG BASE_PATH=""
ENV BASE_PATH=${BASE_PATH}
WORKDIR /build
COPY --from=clone /src/frontend/package.json /src/frontend/package-lock.json ./
RUN npm ci
COPY --from=clone /src/frontend/. .
RUN npm run build

# ---- Stage 4: runtime — backend + frontend, one image, two processes ------
FROM eclipse-temurin:21-jre-jammy AS runtime
# ARGs don't cross stages — redeclare so both the nginx-config generation
# below and the backend's context-path (application.properties, read from
# the container environment at boot via supervisord) see the same value.
ARG BASE_PATH=""
ENV BASE_PATH=${BASE_PATH}
RUN apt-get update \
    && apt-get install -y --no-install-recommends nginx supervisor \
    && rm -rf /var/lib/apt/lists/*

COPY --from=backend-build /build/target/*.jar /app/backend.jar
COPY --from=frontend-build /build/dist /usr/share/nginx/html
COPY --from=clone /src/docker/render-nginx-conf.sh /tmp/render-nginx-conf.sh
RUN sh /tmp/render-nginx-conf.sh "$BASE_PATH" > /etc/nginx/sites-enabled/default \
    && rm /tmp/render-nginx-conf.sh
COPY --from=clone /src/docker/supervisord.conf /etc/supervisor/conf.d/supervisord.conf

# 8999: backend (Spring Boot REST + STOMP/WebSocket) — matches
#       backend/src/main/resources/application.properties
# 80:   frontend (nginx) — serves the built Vue app and reverse-proxies
#       /api and /ws to the backend, so the browser only ever talks to :80
EXPOSE 8999 80

CMD ["/usr/bin/supervisord", "-n", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
