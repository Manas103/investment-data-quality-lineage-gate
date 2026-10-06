"""The 35 declared vendor feed schemas for the daily orchestrated ingestion run.

Each schema names an asset class and a region; all 35 share the same 6
column names (the same deliberate simplification the sibling `ingestion/`
extension already discloses: what distinguishes a schema is its asset
class, its value range and whether negative values are legal, not its
column shape). `SCHEMAS` is asserted to have exactly 35 entries.
"""
from __future__ import annotations

from dataclasses import dataclass

ASSET_CLASSES = ["equity", "bond", "etf", "option", "cash", "future", "swap"]
REGIONS = ["us", "eu", "apac", "latam", "emea"]

COLUMNS = ["as_of_date", "instrument_id", "value1", "value2", "currency", "source_vendor"]


@dataclass(frozen=True)
class VendorSchema:
    name: str
    asset_class: str
    region: str
    allow_negative_value1: bool
    value1_min: float
    value1_max: float


def _build_schemas() -> list[VendorSchema]:
    schemas = []
    for asset_class in ASSET_CLASSES:
        for region in REGIONS:
            name = f"{asset_class}_{region}"
            allow_negative = asset_class in ("swap", "future")
            value1_min = -1000.0 if allow_negative else 0.0
            schemas.append(
                VendorSchema(
                    name=name,
                    asset_class=asset_class,
                    region=region,
                    allow_negative_value1=allow_negative,
                    value1_min=value1_min,
                    value1_max=100_000.0,
                )
            )
    return schemas[:35]


SCHEMAS: list[VendorSchema] = _build_schemas()
SCHEMA_BY_NAME: dict[str, VendorSchema] = {s.name: s for s in SCHEMAS}

assert len(SCHEMAS) == 35
