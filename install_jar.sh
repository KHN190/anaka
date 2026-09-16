#!/bin/bash
# Install a rebuilt Anaka jar safely: wait until the game is closed (API refuses connections for 10 s in a
# row), then swap the single jar in dev-mods. Replacing the jar under a running game corrupted class loading once.
# Usage: install_jar.sh 0.1.26
cd "$(dirname "$0")" || exit 1
V=${1:?version, e.g. 0.1.26}
SRC="$(dirname "$0")/build/libs/anaka-$V+mc1.21.11.jar"
# Where the launcher loads development jars from (-Dfabric.addMods). Set MC_DEV_MODS to yours.
DEST="${MC_DEV_MODS:?set MC_DEV_MODS to the dev-mods directory your launcher loads}"
[ -f "$SRC" ] || { echo "no built jar $SRC"; exit 1; }
# Wait until the API has refused connections for 10 seconds straight. A plain TCP probe: the port is up exactly
# while the game is, and this needs no client library (the agent that usually talks to it is a separate project).
PORT="${MC_API_PORT:-27599}"
DOWN=0
until [ "$DOWN" -ge 10 ]; do
  if (exec 3<>/dev/tcp/127.0.0.1/"$PORT") 2>/dev/null; then
    exec 3<&- 3>&-
    DOWN=0
    echo "game still running on port $PORT — close it to install"
  else
    DOWN=$((DOWN + 2))
  fi
  sleep 2
done
rm -f "$DEST"/anaka-*.jar
cp "$SRC" "$DEST/" && echo "installed $(ls "$DEST") — start the game now"
