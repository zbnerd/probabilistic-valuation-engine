#!/usr/bin/env bash
# scripts/install-systemd-units.sh
# Install the 4 MinIO-using module systemd units.
# Idempotent. Does NOT start the services.

set -euo pipefail

MAPLE_HOME="${1:-/opt/maple}"
UNIT_SRC="$(cd "$(dirname "$0")" && pwd)/systemd"

MODULES=(external-api calculator synchronizer cleanup)
UNITS=()
for m in "${MODULES[@]}"; do
  UNITS+=("maple-${m}.service")
done

log()  { printf '[install-systemd] %s\n' "$*"; }
fail() { printf '[install-systemd] ERROR: %s\n' "$*" >&2; exit 1; }

# 1. Prerequisites
[ "$(id -u)" -eq 0 ] || fail "must run as root (or with sudo)"

[ -d "${MAPLE_HOME}" ] || fail "MAPLE_HOME=${MAPLE_HOME} does not exist"

for m in "${MODULES[@]}"; do
  jar="${MAPLE_HOME}/build/libs/module-${m}-0.0.1-SNAPSHOT.jar"
  [ -f "${jar}" ] || fail "missing jar: ${jar}"
done

[ -f "${MAPLE_HOME}/.env" ] || fail "missing ${MAPLE_HOME}/.env"

for m in "${MODULES[@]}"; do
  env_file="${MAPLE_HOME}/.env.${m}"
  [ -f "${env_file}" ] || fail "missing ${env_file}"
done

for u in "${UNITS[@]}"; do
  [ -f "${UNIT_SRC}/${u}" ] || fail "missing source unit: ${UNIT_SRC}/${u}"
done

log "preflight OK (MAPLE_HOME=${MAPLE_HOME})"

# 2. Create maple user/group (system account, no shell, no home)
if ! id maple >/dev/null 2>&1; then
  log "creating maple system user"
  useradd --system --shell /bin/false --home "${MAPLE_HOME}" maple
else
  log "maple user already exists"
fi

# 3. Create /var/log/maple owned by maple:maple
install -d -o maple -g maple -m 0755 /var/log/maple
log "/var/log/maple ready (maple:maple 0755)"

# 4. Copy unit files to /etc/systemd/system/
for u in "${UNITS[@]}"; do
  install -m 0644 "${UNIT_SRC}/${u}" "/etc/systemd/system/${u}"
  log "installed /etc/systemd/system/${u}"
done

# 5. Reload systemd daemon
systemctl daemon-reload
log "systemctl daemon-reload done"

# 6. Enable (but do NOT start)
for u in "${UNITS[@]}"; do
  systemctl enable "${u}" >/dev/null
  log "enabled ${u}"
done

# 7. Summary
cat <<EOF

============================================================
Install complete (services NOT started).
============================================================
MAPLE_HOME:           ${MAPLE_HOME}
Installed units:      ${UNITS[*]}
Unit config:          /etc/systemd/system/maple-*.service
Logs:                 /var/log/maple/<module>.log
                          /var/log/maple/<module>-error.log
Service user:         maple (uid from 'id maple')
Service group:        maple

Next steps (operator decision):
  sudo systemctl start maple-external-api maple-calculator maple-synchronizer maple-cleanup
  sudo systemctl status maple-external-api
  journalctl -u maple-external-api -f   # if logs/var-log-maple is not enough

To uninstall:
  sudo systemctl disable --now maple-external-api maple-calculator maple-synchronizer maple-cleanup
  sudo rm /etc/systemd/system/maple-*.service
  sudo systemctl daemon-reload
============================================================
EOF
