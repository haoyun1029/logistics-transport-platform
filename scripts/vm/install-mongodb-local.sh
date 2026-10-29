#!/usr/bin/env bash
# Offline installer for the official MongoDB 6.0.26 RHEL 7 x86_64 archive.
# Usage: bash install-mongodb-local.sh ARCHIVE
set -euo pipefail
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || fail 'Run as root in the Linux VM.'
[[ $# -eq 1 ]] || fail 'Provide the official .tgz archive.'
archive=$(readlink -f "$1")
[[ -f "$archive" ]] || fail 'Archive not found.'
[[ $(uname -m) == x86_64 ]] || fail 'This archive requires x86_64.'
grep -qw avx /proc/cpuinfo || fail 'AVX is required.'
for cmd in sha256sum tar systemctl ss useradd; do command -v "$cmd" >/dev/null || fail "Missing command: $cmd"; done
if command -v mongod >/dev/null || rpm -qa | grep -i mongodb >/dev/null || pgrep -x mongod >/dev/null; then
    fail 'MongoDB already exists or is running. Inspect it instead of installing again.'
fi
if ss -lnt | awk '{print $4}' | grep -Eq ':27017$'; then fail 'Port 27017 is already in use.'; fi
prefix=mongodb-linux-x86_64-rhel70-6.0.26
install_dir=/opt/logistics-mongodb-6.0.26
config=/etc/logistics-mongod.conf
unit=/etc/systemd/system/logistics-mongod.service
for path in "$install_dir" "$config" "$unit" /var/lib/logistics-mongodb /var/log/logistics-mongodb; do
    [[ ! -e "$path" ]] || fail "Existing path, left unchanged: $path"
done
# Official .tgz.sha256 response supplied from the VM on 2026-10-23.
expected=8a8701c1b0d49ac5ad7ac196676250e99cf7f8dd385e3763a1814dd0afbb2dda
actual=$(sha256sum "$archive" | awk '{print $1}')
[[ ${actual,,} == ${expected,,} ]] || fail 'SHA-256 mismatch; nothing installed.'
# Only accept the expected vendor archive, with no parent path traversal.
tar -tzf "$archive" | awk -v prefix="$prefix/" '
    index($0,prefix)!=1 || $0 ~ /(^|\/)\.\.(\/|$)/ {bad=1} END {exit bad}' || fail 'Unexpected archive paths.'
tar -tzf "$archive" | grep -Fx "$prefix/bin/mongod" >/dev/null || fail 'mongod missing from archive.'
printf 'SHA256_OK %s\n' "$actual"
if ! id logistics_mongo >/dev/null 2>&1; then useradd --system --no-create-home --shell /sbin/nologin logistics_mongo; fi
install -d -m 755 "$install_dir"
tar -xzf "$archive" -C "$install_dir" --strip-components=1 --no-same-owner
"$install_dir/bin/mongod" --version
install -d -o logistics_mongo -g "$(id -gn logistics_mongo)" -m 750 /var/lib/logistics-mongodb /var/log/logistics-mongodb
cat > "$config" <<'CONFIG'
storage:
  dbPath: /var/lib/logistics-mongodb
  wiredTiger:
    engineConfig:
      cacheSizeGB: 0.25
systemLog:
  destination: file
  path: /var/log/logistics-mongodb/mongod.log
  logAppend: true
net:
  bindIp: 127.0.0.1
  port: 27017
  maxIncomingConnections: 100
CONFIG
cat > "$unit" <<'UNIT'
[Unit]
Description=Logistics demo MongoDB (VM loopback only)
After=network.target

[Service]
Type=simple
User=logistics_mongo
ExecStart=/opt/logistics-mongodb-6.0.26/bin/mongod --config /etc/logistics-mongod.conf
Restart=on-failure
RestartSec=5
LimitNOFILE=64000
MemoryLimit=768M
TimeoutStopSec=120

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl start logistics-mongod
systemctl is-active logistics-mongod
printf 'Service started; verify database readiness with MongoGpsProbe before submitting the sink job.\n'
printf 'Logs: journalctl -u logistics-mongod --no-pager -n 30\n'
printf 'MongoDB listens only on VM 127.0.0.1:27017. No firewall or existing database settings changed.\n'
