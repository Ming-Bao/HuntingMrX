#!/usr/bin/env bash
set -euo pipefail

# Rebuilds and (re)starts the Hunting Mr. X: Wellington Edition container from
# a given branch/tag/commit (default: main). Safe to re-run: it only stops the
# currently running container once the new image has built successfully, so a
# broken build never takes down a working deployment.
#
# Expects: ~/Scotland-Yard to be a clone of the repo. Only its Dockerfile is
# read locally; the Dockerfile clones the app source from GitHub at GIT_REF.

REPO_DIR="$HOME/Scotland-Yard"
IMAGE_NAME="mrx"
CONTAINER_NAME="mrx"
GIT_REF="${1:-main}"
# 8080 matches the Cloudflare Tunnel route (Service URL: http://localhost:8080).
PORT="${PORT:-8080}"
# e.g. BASE_PATH=/mrx ./update-container.sh to serve at https://host/mrx/.
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

# Built from the SHA, not the branch name, so the Dockerfile's cached clone is
# only reused when nothing has changed.
echo "==> Building image..."
docker build \
  --build-arg GIT_REF="${RESOLVED_SHA}" \
  --build-arg BASE_PATH="${BASE_PATH}" \
  -t "${IMAGE_NAME}:${RESOLVED_SHA}" \
  -t "${IMAGE_NAME}:latest" \
  "${REPO_DIR}"

echo "==> Build succeeded. Swapping container..."
docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
# Bound to 127.0.0.1 only: cloudflared runs on this box and reaches it over localhost.
docker run -d \
  --name "${CONTAINER_NAME}" \
  --restart unless-stopped \
  -p "127.0.0.1:${PORT}:8999" \
  "${IMAGE_NAME}:latest"

echo "==> Removing old images..."
docker images "${IMAGE_NAME}" --format '{{.Repository}}:{{.Tag}}' \
  | grep -vx -e "${IMAGE_NAME}:latest" -e "${IMAGE_NAME}:${RESOLVED_SHA}" \
  | xargs -r docker rmi >/dev/null || true
docker image prune -f >/dev/null

echo "==> Done. Running ${IMAGE_NAME}:${RESOLVED_SHA} on port ${PORT}${BASE_PATH:+ (base path: ${BASE_PATH})}"
docker ps --filter "name=${CONTAINER_NAME}"
