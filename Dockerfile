# syntax=docker/dockerfile:1
#
# Self-contained build: clones the repo at build time rather than using the
# local build context, so `docker build` only needs this file.
#
#   docker build --build-arg GIT_REF=<tag or commit> -t mrx .
#
# GIT_REF: Docker caches the clone by the ARG text, not the remote, so a
# branch name reuses a stale clone. Pass a tag or commit SHA for a real deploy
# (update-container.sh does). The clone is anonymous, so the repo must be public.
#
# BASE_PATH: e.g. --build-arg BASE_PATH=/mrx serves the app at https://host/mrx/.
# It's baked into the frontend build, so changing it means rebuilding.
#
# The result is one process: Spring Boot on 8999 serves the API, the /ws
# live connection, and the built frontend (copied into its static/ folder).

# ---- Stage 1: fetch source --------------------------------------------------
FROM alpine/git:latest AS clone
ARG REPO_URL=https://github.com/Ming-Bao/HuntingMrX.git
ARG GIT_REF=main
WORKDIR /src
# init+fetch instead of `clone --branch`, which can't take a commit SHA.
RUN git init -q && \
    git remote add origin "${REPO_URL}" && \
    git fetch --depth 1 origin "${GIT_REF}" && \
    git checkout -q FETCH_HEAD

# ---- Stage 2: build the frontend (Vue 3 / Vite) ----------------------------
FROM node:22-alpine AS frontend-build
ARG BASE_PATH=""
ENV BASE_PATH=${BASE_PATH}
WORKDIR /build
COPY --from=clone /src/frontend/package.json /src/frontend/package-lock.json ./
RUN npm ci
COPY --from=clone /src/frontend/. .
RUN npm run build

# ---- Stage 3: build the backend, with the frontend inside the jar ----------
FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /build
COPY --from=clone /src/backend/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY --from=clone /src/backend/src ./src
COPY --from=frontend-build /build/dist ./src/main/resources/static/
RUN mvn -B -q package -DskipTests

# ---- Stage 4: runtime -------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
# The backend's context-path reads BASE_PATH at startup, so it matches the
# frontend build above.
ARG BASE_PATH=""
ENV BASE_PATH=${BASE_PATH}
COPY --from=backend-build /build/target/*.jar /app.jar
EXPOSE 8999
ENTRYPOINT ["java", "-jar", "/app.jar"]
