#!/usr/bin/env bash
set -euo pipefail

# Rebuilds and (re)starts the Hunting Mr. X: Wellington Edition container from
# a given branch/tag/commit (default: main). Safe to re-run: it only stops the
# currently running container once the new image has built successfully, so a
# broken build never takes down a working deployment.
#
# Expects: ~/Scotland-Yard to be a clone of the repo (only its Dockerfile and
# docker/ configs are read locally — the Dockerfile clones the actual app
# source fresh from GitHub at GIT_REF, per its own build design).

REPO_DIR="$HOME/Scotland-Yard"
IMAGE_NAME="mrx"
CONTAINER_NAME="mrx"
GIT_REF="${1:-main}"
PORT="${PORT:-8080}"

# Set to deploy under a URL path prefix instead of the domain root — e.g.
# BASE_PATH=/mrx ./update-container.sh for a server that only hands out
# https://host/mrx/. Baked into the image at build time (see Dockerfile's
# ARG BASE_PATH), not runtime-overridable, so changing it means rebuilding —
# which this script always does anyway. Empty (the default) reproduces
# today's root-path behavior exactly.
BASE_PATH="${BASE_PATH:-}"

cd "$REPO_DIR"

echo "==> Fetching latest refs..."
git fetch origin --tags --quiet

echo "==> Resolving '${GIT_REF}' to a commit..."
if git rev-parse -q --verify "refs/tags/${GIT_REF}" >/dev/null; then
  RESOLVED_SHA="$(git rev-parse "refs/tags/${GIT_REF}")"
elif git rev-parse -q --verify "origin/${GIT_REF}" >/dev/null; then
  RESOLVED_SHA="$(git rev-parse "origin/${GIT_REF}")"
else
  RESOLVED_SHA="$(git rev-parse "${GIT_REF}")"
fi
echo "    ${GIT_REF} -> ${RESOLVED_SHA}"

# GIT_REF is passed as the commit SHA (not a branch name) so the Dockerfile's
# `git clone` layer only cache-hits when nothing has actually changed —
# passing "main" every time would always reuse the first clone ever made.
echo "==> Building image..."
docker build \
  --build-arg GIT_REF="${RESOLVED_SHA}" \
  --build-arg BASE_PATH="${BASE_PATH}" \
  -t "${IMAGE_NAME}:${RESOLVED_SHA}" \
  -t "${IMAGE_NAME}:latest" \
  "${REPO_DIR}"

echo "==> Build succeeded. Swapping container..."
if docker ps -a --format '{{.Names}}' | grep -qx "${CONTAINER_NAME}"; then
  docker stop "${CONTAINER_NAME}" >/dev/null 2>&1 || true
  docker rm "${CONTAINER_NAME}" >/dev/null 2>&1 || true
fi

# Defaults to 8080, matching the Cloudflare Tunnel route already configured
# (Service URL: http://localhost:8080) — override with PORT=... if the tunnel
# route (or whatever's fronting this) points somewhere else. Bound to
# 127.0.0.1 only — cloudflared runs on this same box and reaches it over
# localhost, so there's no need to expose this port beyond that.
docker run -d \
  --name "${CONTAINER_NAME}" \
  --restart unless-stopped \
  -p "127.0.0.1:${PORT}:80" \
  "${IMAGE_NAME}:latest"

echo "==> Done. Running ${IMAGE_NAME}:${RESOLVED_SHA} on port ${PORT}${BASE_PATH:+ (base path: ${BASE_PATH})}"
docker ps --filter "name=${CONTAINER_NAME}"
