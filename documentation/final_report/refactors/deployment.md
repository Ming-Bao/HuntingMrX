# Deployment refactor

Date: 2026-09-23. Scope: the Docker image and deploy script (`Dockerfile`, `docker/`, `update-container.sh`) and the backend and frontend settings they rely on.

## Starting point

- **Two processes in one container:** supervisord ran Spring Boot on :8999 and nginx on :80. nginx served the built frontend and passed `/api` and `/ws` on to Spring.
- **Generated nginx config:** `docker/render-nginx-conf.sh` (121 lines) wrote the nginx config at build time from `BASE_PATH`. It needed:
  - a rewrite so `/mrx/assets/...` found files that weren't in a `/mrx` folder
  - `X-Forwarded-Proto` handling for the `/` → `/mrx/` redirect
  - WebSocket upgrade headers
  - `proxy_buffering off` so SockJS's streaming fallback wasn't held back
- **Duplicated work:** Spring Boot already serves anything in `classpath:/static/`, and its `context-path` (bound to `BASE_PATH`) already applies to everything it serves. nginx was doing a job Spring could do itself.
- **Deploy script:** `update-container.sh` used four lines (a lookup, then stop, then remove) to replace the old container. It never deleted old images, so each deploy left another `mrx:<sha>` image on the server's disk.
- **Dead dev proxy:** `vite.config.ts` still forwarded `/test-map.json` to the backend, though the frontend had moved to `GET /api/map`.

## What changed

### nginx and supervisord removed
- **Frontend inside the jar:** the Dockerfile now builds the frontend first, then copies `dist/` into the backend's `src/main/resources/static/` before `mvn package`. It sits beside `map.json` and `test-map.json`, and no file names clash.
- **One-process image:** the final image is `eclipse-temurin:21-jre-alpine` with the jar and `ENTRYPOINT java -jar /app.jar`, listening on 8999. It no longer installs nginx and supervisor with apt-get.
- **Deleted:** `docker/render-nginx-conf.sh`, `docker/supervisord.conf` and the `docker/` folder.

### Page addresses (`config/PageRoutes`)
The router runs in history mode, so a refresh or a shared link sends a real request for a page address. nginx's `try_files … /index.html` used to handle that. A new `@Configuration`, `PageRoutes`, forwards `/create`, `/join`, `/lobby/*`, `/game/*`, `/game/*/end` and the 6-character join link `/{code}` to `index.html`. The list mirrors `frontend/src/app/router.ts`. Unknown `/api/...` addresses still get their JSON errors.

### BASE_PATH
Spring's context-path now covers the frontend files as well, so the nginx rewrite, the generated config and the forwarded-protocol handling all went. The runtime image sets `ENV BASE_PATH` from the same build argument the frontend build uses, so the two always match.

### Clone stage kept
The Dockerfile still clones the repo from GitHub at `GIT_REF`, so it can build from the Dockerfile alone. We weighed building from the server's local checkout instead and kept cloning. Only the comments were trimmed.

### Deploy script
- **Port:** maps `127.0.0.1:8080` to the container's 8999. The Cloudflare Tunnel still targets `localhost:8080`, so nothing changed outside the server.
- **Container swap:** one `docker rm -f` replaces the four-line stop/remove block.
- **Image cleanup:** after the new container starts, the script deletes every `mrx` image except the current SHA and `latest`, then prunes dangling images.
- **Executable bit:** git had always stored the script as a plain file (`100644`). The server copy only ran because of a local `chmod +x`, which the next pull wiped. The executable bit is now set in git (`100755`).

### Frontend
Removed the dead `test-map.json` proxy entry from `vite.config.ts`.

### Docs
- `spec.md`: the Containerisation row and the deployment note.
- `backend/doc.md` and `frontend/doc.md`.
- The comment in `application.properties`.
- `PageRoutes` added to `class-graph.md`, `class-graph.drawio` and `classdiagram.md`.

`openapi.yaml` is unchanged: no REST endpoint or STOMP topic changed.

## Trade-off
With `BASE_PATH=/mrx`, a request to the bare domain `/` now returns 404 instead of redirecting to `/mrx/`, since nothing in the container serves outside the context path. `/mrx` still redirects to `/mrx/`. A server that only hands out `/mrx` never forwards `/` to the container anyway, and the live site at vuw-mrx.xyz uses the root path.

## Size

| | Before | After |
|---|---|---|
| Processes in the container | 2 (nginx, Spring Boot) under supervisord | 1 (Spring Boot) |
| `Dockerfile` | 91 lines, 4 stages + apt-get layer | 56 lines, 4 stages |
| `docker/` | 2 files, 141 lines | removed |
| `update-container.sh` | 71 lines | 62 lines, now also cleans up old images |
| New Java | none | `PageRoutes`, 27 lines |
| Runtime image | not measured | 231 MB |

## Verification

1. **Tests:** `mvn clean test` gave 135 passed, 0 failed (previously 134). The new test, `ApiIntegrationTest.pageAddressesLoadTheFrontendButApiAddressesDoNot`, checks that every page address forwards to `index.html`, and that `/api/games/nope` and a 5-character path don't.
2. **Frontend build:** `npm run build` passed.
3. **Image build:** the real Dockerfile clones from GitHub, so it couldn't see the uncommitted changes. I built a copy whose clone stage copied the local files instead, with every other line the same. I built it with Podman twice: once at the root path and once with `BASE_PATH=/mrx`.
4. **HTTP checks (curl):**
   - At the root path, `/`, `/create`, `/lobby/ABC123` and `/WXYZ12` returned the HTML page, `/api/map` returned JSON, and the hashed JS asset and `/ws/info` (SockJS) both answered.
   - Under `/mrx`, `/mrx` redirected to `/mrx/`, and `/mrx/lobby/...`, `/mrx/api/map` and `/mrx/ws/info` all worked.
5. **Browser test (Playwright, two players), on both images:**
   - create a game, then refresh the lobby page
   - open the join link and see the code filled in
   - the host sees the guest join over the live connection
   - start the game, the map renders, then refresh the board page

   Both passed. The only console error was Chrome's own `/favicon.ico` 404, since the app has no favicon.
