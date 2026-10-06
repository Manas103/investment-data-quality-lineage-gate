from dagpipeline.schemas import SCHEMAS
from dagpipeline.sql_rules import ALL_SQL_RULES


def test_exactly_35_schemas():
    assert len(SCHEMAS) == 35
    assert len({s.name for s in SCHEMAS}) == 35


def test_exactly_30_sql_rules():
    assert len(ALL_SQL_RULES) == 30
    assert len({r.name for r in ALL_SQL_RULES}) == 30
