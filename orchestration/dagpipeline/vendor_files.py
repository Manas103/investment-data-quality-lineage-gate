"""Generates one simulated day's vendor file drop: 1,500+ real CSV files
across the 35 declared schemas.

Every row is tagged with its own (source_file, source_row) lineage at
write time, the same discipline the sibling `ingestion/` extension uses,
so a later quarantine decision can always point back to the exact file and
row it came from.
"""
from __future__ import annotations

import csv
from pathlib import Path

import numpy as np

from .schemas import COLUMNS, SCHEMAS

FILES_PER_SCHEMA = 44  # 44 * 35 = 1,540, comfortably over the 1,500+ claim
ROWS_PER_FILE = 15


def generate_one_days_vendor_files(out_dir: Path, seed: int = 2026) -> list[Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(seed)
    written: list[Path] = []

    for schema in SCHEMAS:
        for delivery in range(FILES_PER_SCHEMA):
            file_name = f"{schema.name}_{delivery:03d}.csv"
            path = out_dir / file_name
            with open(path, "w", newline="", encoding="utf-8") as fh:
                writer = csv.writer(fh)
                writer.writerow(COLUMNS)
                # sampled without replacement so no file can accidentally seed its own
                # duplicate_instrument_within_file violation by random collision
                instrument_numbers = rng.choice(99999, size=ROWS_PER_FILE, replace=False)
                for row_idx in range(ROWS_PER_FILE):
                    instrument_id = f"{schema.asset_class[:3].upper()}{instrument_numbers[row_idx]:05d}"
                    value1 = float(rng.uniform(schema.value1_min, schema.value1_max))
                    value2 = float(rng.uniform(0, 1))
                    writer.writerow(
                        ["2026-10-05", instrument_id, round(value1, 4), round(value2, 6), "USD", "vendor_a"]
                    )
            written.append(path)

    return written


def read_vendor_file(path: Path, schema_name: str) -> list[dict]:
    records = []
    with open(path, "r", newline="", encoding="utf-8") as fh:
        reader = csv.DictReader(fh)
        for row_idx, row in enumerate(reader):
            row["schema"] = schema_name
            row["source_file"] = path.name
            row["source_row"] = row_idx
            records.append(row)
    return records
