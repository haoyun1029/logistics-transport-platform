#!/usr/bin/env bash
# Restore this existing Hadoop 3.1.3 VM, without formatting or starting other hosts.
set -euo pipefail
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || fail 'Run in the Linux VM as root.'
[[ $# -eq 1 ]] || fail 'Usage: bash start-local-hdfs.sh CURRENT_VM_IPV4'
vm_ip=$1
ip -o -4 addr show | awk '{split($4,a,"/"); print a[1]}' | grep -Fx "$vm_ip" >/dev/null || fail 'IP is not assigned to this VM.'
hadoop_dir=/opt/remodule/hadoop-3.1.3
hdfs=$hadoop_dir/bin/hdfs
name_dir=$hadoop_dir/data/dfs/name
data_dir=$hadoop_dir/data/dfs/data
[[ -x "$hdfs" && -f "$name_dir/current/VERSION" && -f "$data_dir/current/VERSION" ]] || fail 'Existing Hadoop storage not found. Nothing will be formatted.'
nn_cluster=$(awk -F= '$1=="clusterID" {print $2}' "$name_dir/current/VERSION")
dn_cluster=$(awk -F= '$1=="clusterID" {print $2}' "$data_dir/current/VERSION")
[[ -n "$nn_cluster" && "$nn_cluster" == "$dn_cluster" ]] || fail 'NameNode/DataNode cluster IDs differ. Inspect existing storage.'
if ss -lnt | awk '{print $4}' | grep -Eq ':8020$'; then
    fail 'NameNode port is already listening. Inspect health rather than changing its configuration.'
fi
backup=/var/backups/logistics/hdfs-before-start-$(date +%Y%m%d-%H%M%S)
[[ ! -e "$backup" ]] || fail 'Backup path already exists.'
install -d -m 700 "$backup"
cp -a /etc/hosts "$backup/hosts"
cp -a "$hadoop_dir/etc/hadoop" "$backup/hadoop-config"
cp -a "$name_dir" "$backup/namenode-metadata"
hosts_tmp=$(mktemp)
trap 'rm -f "$hosts_tmp"' EXIT
awk -v ip="$vm_ip" '
    /^[[:space:]]*#/ {print; next}
    {for(i=2;i<=NF;i++) if(tolower($i)=="hadoop101") {$1=ip; found=1}; print}
    END {if(!found) print ip " hadoop101"}' /etc/hosts > "$hosts_tmp"
cat "$hosts_tmp" > /etc/hosts
printf 'BACKUP=%s\n' "$backup"
getent hosts hadoop101
# Only start local daemons; preserve workers, replication and all existing XML.
export HADOOP_HEAPSIZE_MAX=256
export HADOOP_HEAPSIZE_MIN=64
"$hdfs" --daemon start namenode
"$hdfs" --daemon start datanode
printf 'Local daemons requested. Check dfsadmin -report and safemode before writing.\n'
printf 'No format, forced safemode exit, YARN start or remote worker start was performed.\n'
