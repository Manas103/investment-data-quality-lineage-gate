# Documents the real per-task container shape for the 55-task DAG.
# Designed, not exercised: Docker Desktop's daemon was not running in this
# build environment (`docker info` fails to connect), the same disclosed
# gap the sibling ingestion/ extension's Spring Boot and React cut already
# sets a precedent for in this repository. The measured run isolates each
# task as its own Python function call inside one process instead (see
# dagpipeline/dag.py), checkpointed to disk for idempotent retries and
# mid-run resume.
FROM python:3.12-slim

WORKDIR /task
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY dagpipeline ./dagpipeline

# A real deployment would give dag.py a subprocess-per-task launcher (not
# built in this pass, see README Limitations) and run one container per
# task invocation, e.g.:
#   docker run --rm -v "$RUN_DIR:/run" task-image python -c \
#     "from dagpipeline.dag import run_dag; ..."
