#!/bin/bash
# Builds and runs idqg-api entirely inside WSL2 (not cross-VM from Windows), because the
# WSL2 localhost-forwarding loopback that a Windows-side JVM would otherwise use to reach
# Redis/RabbitMQ was observed to drop intermittently during this build; see README Findings.
# Run from Windows: wsl.exe -d Ubuntu-22.04 -- bash "<path to this script>"
set -e
SRC="/mnt/c/Users/Manas/Downloads/Auto Job Applier/Total Job Application process flow/projects/investment-data-quality-lineage-gate"

sudo service redis-server start >/dev/null 2>&1 || true
sudo service rabbitmq-server start >/dev/null 2>&1 || true
sleep 2
redis-cli ping
sudo rabbitmqctl status >/dev/null 2>&1 && echo rabbit-up

rm -rf ~/build/idqg/api ~/build/idqg/generator
mkdir -p ~/build/idqg/api
rsync -a --exclude target "$SRC/api/" ~/build/idqg/api/
rsync -a "$SRC/generator/" ~/build/idqg/generator/
cd ~/build/idqg/api

export IDQG_PYTHON=python3
mvn -q test > /tmp/idqg_api_test.log 2>&1
echo "=== test exit: $? ==="
tail -40 /tmp/idqg_api_test.log

mvn -q package -DskipTests > /tmp/idqg_api_package.log 2>&1
echo "=== package exit: $? ==="
tail -20 /tmp/idqg_api_package.log

mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/idqg_api_cp.txt > /tmp/idqg_api_cp.log 2>&1

mkdir -p /tmp/idqg_api_docs
java -cp "target/classes:$(cat /tmp/idqg_api_cp.txt)" com.mfs.idqg.api.bench.CacheLatencyBenchmark \
  target/idqg-api.jar "$HOME/build/idqg/generator/generate.py" /tmp/idqg_api_docs 2>&1 | tee /tmp/idqg_api_cache_bench.log

java -cp "target/classes:$(cat /tmp/idqg_api_cp.txt)" com.mfs.idqg.api.bench.ChangeFeedLatencyBenchmark \
  target/idqg-api.jar /tmp/idqg_api_docs 2>&1 | tee /tmp/idqg_api_changefeed_bench.log

mkdir -p "$SRC/api/docs"
cp /tmp/idqg_api_docs/*.txt "$SRC/api/docs/" 2>/dev/null || true
cp /tmp/idqg_api_test.log "$SRC/api/docs/test_output.txt"

echo "=== ALL DONE ==="
