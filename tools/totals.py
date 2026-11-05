#!/usr/bin/env python3
"""Prints calltally's totals per month, for checking that an install changed nothing.

Run it before and after an install and compare the two outputs with diff. Opens the database read-only and counts the
same way as /api/range: connected calls only, deleted entries never, deleted entries counted on their own.

Usage: python3 tools/totals.py [path/to/calltally.db]   (default: data/calltally.db next to the repo)
"""
import sqlite3
import sys
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from calltally import CONNECTED  # noqa: E402

MONTHS = (
    "SELECT substr(day,1,7) AS month,"
    f" COALESCE(SUM(CASE WHEN {CONNECTED} AND deleted_at IS NULL THEN duration_s END),0) AS call_s,"
    f" COALESCE(SUM({CONNECTED} AND deleted_at IS NULL),0) AS calls,"
    " COALESCE(SUM(kind='text' AND deleted_at IS NULL),0) AS texts,"
    " COALESCE(SUM(deleted_at IS NOT NULL),0) AS deleted,"
    " COUNT(*) AS rows"
    " FROM entries GROUP BY month ORDER BY month"
)


def totals(path):
    """Lines of text: one per month, then one for the whole table."""
    path = Path(path).resolve()
    if not path.is_file():
        raise FileNotFoundError(f"no database at {path}")
    conn = sqlite3.connect(f"file:{quote(str(path))}?mode=ro", uri=True)
    try:
        months = conn.execute(MONTHS).fetchall()
        rows, latest = conn.execute("SELECT COUNT(*), MAX(at) FROM entries").fetchone()
    finally:
        conn.close()
    lines = [f"{m} call_s={s} calls={c} texts={t} deleted={d} rows={r}" for m, s, c, t, d, r in months]
    lines.append(f"all rows={rows} latest={latest or 'none'}")
    return lines


def main(argv):
    if len(argv) > 2:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    path = argv[1] if len(argv) == 2 else ROOT / "data" / "calltally.db"
    try:
        lines = totals(path)
    except (OSError, sqlite3.Error) as error:
        print(f"totals: {error}", file=sys.stderr)
        return 1
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
