"""Point-in-time vintages written to S3 through the AWS SDK (boto3).

`S3VintageStore` writes every vintage to its own key under
`vintages/<schema>/<as_of_date>/<delivery_id>.json`, keyed by a UUID
delivery id rather than overwriting a fixed name, so a second delivery for
the same (schema, as_of_date), a real restatement, produces a second,
independent object. This is the same non-overwrite discipline the sibling
`ingestion/` extension's `PointInTimeVintageStore` already uses for local
Parquet files, applied here to S3 through boto3 instead.

Real AWS is never contacted: `moto`'s `mock_aws()` intercepts every boto3
call in-process and backs it with an in-memory, S3-API-compatible store,
so every call below is a real `boto3` S3 call (`put_object`,
`get_object`, `list_objects_v2`) exercised against a real implementation
of the S3 API, not a hand-rolled substitute.
"""
from __future__ import annotations

import json
import uuid
from dataclasses import dataclass

import boto3

BUCKET_NAME = "aqr-orchestration-vintages"


@dataclass(frozen=True)
class VintageKey:
    schema: str
    as_of_date: str
    delivery_id: str

    @property
    def key(self) -> str:
        return f"vintages/{self.schema}/{self.as_of_date}/{self.delivery_id}.json"


class S3VintageStore:
    def __init__(self) -> None:
        self.client = boto3.client("s3", region_name="us-east-1")
        existing = self.client.list_buckets().get("Buckets", [])
        if not any(b["Name"] == BUCKET_NAME for b in existing):
            self.client.create_bucket(Bucket=BUCKET_NAME)

    def write_vintage(self, schema: str, as_of_date: str, payload: dict) -> VintageKey:
        delivery_id = str(uuid.uuid4())
        vintage_key = VintageKey(schema=schema, as_of_date=as_of_date, delivery_id=delivery_id)
        self.client.put_object(
            Bucket=BUCKET_NAME,
            Key=vintage_key.key,
            Body=json.dumps(payload).encode("utf-8"),
        )
        return vintage_key

    def read_vintage(self, vintage_key: VintageKey) -> dict:
        response = self.client.get_object(Bucket=BUCKET_NAME, Key=vintage_key.key)
        return json.loads(response["Body"].read().decode("utf-8"))

    def list_vintages(self, schema: str, as_of_date: str) -> list[str]:
        prefix = f"vintages/{schema}/{as_of_date}/"
        response = self.client.list_objects_v2(Bucket=BUCKET_NAME, Prefix=prefix)
        return [obj["Key"] for obj in response.get("Contents", [])]
