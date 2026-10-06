#!/bin/bash
set -e
SRC="/mnt/c/Users/Manas/Downloads/Auto Job Applier/Total Job Application process flow/projects/investment-data-quality-lineage-gate"

sudo service redis-server start >/dev/null 2>&1 || true
sudo service rabbitmq-server start >/dev/null 2>&1 || true
sleep 2

rsync -a --exclude target "$SRC/api/" ~/build/idqg/api/
cd ~/build/idqg/api
export IDQG_PYTHON=python3
mvn -q package -DskipTests -o > /tmp/idqg_api_package2.log 2>&1

mkdir -p /tmp/idqg_api_docs
java -cp "target/classes:$(cat /tmp/idqg_api_cp.txt)" com.mfs.idqg.api.bench.CacheLatencyBenchmark \
  target/idqg-api.jar "$HOME/build/idqg/generator/generate.py" /tmp/idqg_api_docs 2>&1 | tee /tmp/idqg_api_cache_bench2.log

cp /tmp/idqg_api_docs/cache_benchmark_output.txt "$SRC/api/docs/"
echo "=== RERUN DONE ==="
