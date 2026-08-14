#!/bin/sh
# Emits the nginx server block for this image, parameterized by BASE_PATH —
# the same flag that drives the frontend's Vite `base` (vite.config.ts) and
# the backend's server.servlet.context-path (application.properties). Run at
# `docker build` time (this Dockerfile rebuilds the image per deploy target
# anyway, same as REPO_URL/GIT_REF) and its output is copied straight into
# /etc/nginx/sites-enabled/default — no runtime templating needed.
#
# Usage: render-nginx-conf.sh "$BASE_PATH"
#   BASE_PATH=""      -> serves at https://host/            (default, current behavior)
#   BASE_PATH="/mrx"   -> serves at https://host/mrx/         (no trailing slash on input)
set -eu
BASE="${1:-}"

cat <<EOF
server {
    listen 80;
    server_name _;

    root /usr/share/nginx/html;
    index index.html;
EOF

# Bare "/" only matters once the app has moved off the root — send it to
# the prefixed root so bookmarks/links to the domain root still land on the
# app instead of nginx's default 404. Skipped entirely when BASE is empty,
# since "redirect / to /" would just be a loop.
if [ -n "$BASE" ]; then
  cat <<EOF
    location = / {
        return 301 $BASE/;
    }

EOF
fi

cat <<EOF
    # Vue Router uses history mode (createWebHistory) — any path that isn't
    # a real static file falls back to index.html so client-side routing
    # can take over.
    #
    # The rewrite strips BASE before try_files runs: \`root\` resolves \$uri
    # straight against the filesystem, but the built dist/ has no /mrx
    # subdirectory — only the *URLs* Vite emits are prefixed (base), the
    # physical output layout isn't. Without this, /mrx/assets/foo.js 404s
    # against root/mrx/assets/foo.js and silently falls through to
    # index.html, which the browser then rejects for a MIME-type mismatch
    # (expected .js/.css, got text/html) — that's this exact bug.
    location $BASE/ {
        rewrite ^$BASE/(.*)\$ /\$1 break;
        try_files \$uri \$uri/ /index.html;
    }

    # REST API — proxied straight through to the backend process on the
    # same container. No trailing path segment on proxy_pass, so nginx
    # forwards the original URI unchanged; the backend's own context-path
    # (bound to the same BASE_PATH) is what makes it line up.
    location $BASE/api/ {
        proxy_pass http://127.0.0.1:8999;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    }

    # STOMP over SockJS (WebSocketConfig registers the endpoint at /ws,
    # picked up under the backend's context-path same as /api above).
    # Needs the Upgrade/Connection headers for the raw WebSocket transport,
    # and a long read timeout so idle connections aren't dropped mid-game.
    #
    # proxy_buffering off matters just as much as the Upgrade headers above:
    # SockJS falls back to xhr_streaming (a plain long-lived HTTP POST, not
    # a protocol upgrade) whenever the browser/network won't complete a real
    # WebSocket handshake. nginx's default buffering holds the backend's
    # streamed frames until its buffer fills or the upstream closes, instead
    # of forwarding them live — from the browser's side that looks exactly
    # like the stream getting cut short right after it opens (a handful of
    # bytes, then done) even though the backend is still trying to push to
    # it. Only matters for this streaming fallback path — real WebSocket
    # upgrades bypass proxy_buffering entirely once nginx sees 101.
    location $BASE/ws/ {
        proxy_pass http://127.0.0.1:8999;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host \$host;
        proxy_read_timeout 86400;
        proxy_buffering off;
    }
}
EOF
