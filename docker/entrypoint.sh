#!/bin/sh
# Runtime entrypoint. When SERVER_SOCKET_PATH is set (the UDS submission topology),
# the API binds a Unix domain socket on a volume shared with the load balancer.
#
# Two things must be handled or the LB cannot reach the API (the v1.6.0 "No status"):
#   1. Stale socket: a leftover file from a previous run makes the bind fail with
#      EADDRINUSE — remove it before the server starts.
#   2. Permissions: HAProxy runs as a different user, and a default ~0755 socket
#      refuses its connect (connecting needs write). Once Ktor has bound the socket,
#      relax it to 0666 so the LB can connect.
# With no SERVER_SOCKET_PATH (local dev / native-image tracing agent) the server
# binds plain TCP and this is a no-op.

set -e

if [ -n "$SERVER_SOCKET_PATH" ]; then
    rm -f "$SERVER_SOCKET_PATH"
    (
        while [ ! -S "$SERVER_SOCKET_PATH" ]; do sleep 0.05; done
        chmod 0666 "$SERVER_SOCKET_PATH"
    ) &
fi

exec /app/rinha-server
