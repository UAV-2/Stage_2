#!/usr/bin/env python3
"""Descarga una vez los libros de BOOK_IDS a cache/<id>.txt (1 petición/s).

Así los benchmarks de integración se pueden repetir con SOURCE=local:cache sin volver a
descargar ni depender de la red. cache/ no se sube a Git.

    python3 scripts/fetch_books.py --ids shared/book_ids.txt
    python3 scripts/fetch_books.py --from-id 1 --count 200   # los primeros 200 ids válidos
"""
import argparse
import time
import urllib.error
import urllib.request
from pathlib import Path

URL = "https://www.gutenberg.org/cache/epub/{id}/pg{id}.txt"


def fetch(book_id):
    req = urllib.request.Request(URL.format(id=book_id), headers={"User-Agent": "ulpgc-bigdata-stage2/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ids")
    ap.add_argument("--from-id", type=int)
    ap.add_argument("--count", type=int, default=100)
    ap.add_argument("--out", default="cache")
    ap.add_argument("--delay", type=float, default=1.0)
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    if a.ids:
        ids = [int(l) for l in Path(a.ids).read_text().split("\n") if l.strip() and not l.startswith("#")]
    else:
        ids = range(a.from_id or 1, 10 ** 6)
    saved = []
    for book_id in ids:
        if not a.ids and len(saved) >= a.count:
            break
        target = out / f"{book_id}.txt"
        if target.exists():
            saved.append(book_id)
            continue
        time.sleep(a.delay)
        try:
            data = fetch(book_id)
        except Exception as e:  # red: se salta y se sigue
            print(f"  {book_id}: {e}")
            continue
        if data and b"*** START OF" in data.upper():
            target.write_bytes(data)
            saved.append(book_id)
            if len(saved) % 25 == 0:
                print(f"  {len(saved)} libros", flush=True)
    (out / "book_ids.txt").write_text("".join(f"{i}\n" for i in saved))
    print(f"{len(saved)} libros en {out}/ (lista en {out}/book_ids.txt)")


if __name__ == "__main__":
    main()
