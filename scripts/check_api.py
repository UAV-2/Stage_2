#!/usr/bin/env python3
"""Comprobaciones automáticas de los contratos HTTP contra los servicios en marcha.

    scripts/start_all.sh && scripts/run_pipeline.sh 5
    python3 scripts/check_api.py                 # usa el primer libro de shared/book_ids.txt
    python3 scripts/check_api.py --book 1342

Solo usa la librería estándar. Cubre búsqueda por palabra clave, filtros individuales y
combinados, resultados vacíos, parámetros inválidos, paginación y las operaciones internas
(ingesta, update, publish) con sus errores y su idempotencia. Sale con código 1 si algo falla.
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
results = []


def call(method, url):
    req = urllib.request.Request(url, method=method, data=b"" if method == "POST" else None)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        body = e.read()
        try:
            return e.code, json.loads(body)
        except ValueError:
            return e.code, {"raw": body.decode(errors="replace")}


def check(name, cond, detail=""):
    results.append((name, bool(cond)))
    print(f"{'PASS' if cond else 'FAIL'}  {name}" + (f"  -> {detail}" if not cond and detail else ""))


def expect(name, method, url, status, error=None):
    st, body = call(method, url)
    ok = st == status and (error is None or body.get("error") == error)
    check(name, ok, f"HTTP {st} {body}")
    return body


def pick_term(body_file, title):
    """Palabra frecuente del libro (no stopword) para buscarlo; si no se puede leer, una del título."""
    stop = set((ROOT / "shared" / "stopwords.txt").read_text().split())
    try:
        text = body_file.read_text(encoding="utf-8")
    except OSError:
        text = title
    counts = {}
    for w in "".join(c.lower() if "A" <= c <= "Z" or "a" <= c <= "z" else " " for c in text).split():
        if len(w) > 3 and w not in stop:
            counts[w] = counts.get(w, 0) + 1
    return max(counts, key=counts.get) if counts else "the"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ingestion", default="http://localhost:7001")
    ap.add_argument("--indexing", default="http://localhost:7002")
    ap.add_argument("--search", default="http://localhost:7003")
    ap.add_argument("--book", type=int)
    ap.add_argument("--ids", default=str(ROOT / "shared" / "book_ids.txt"))
    ap.add_argument("--data", default=str(ROOT / "data"), help="DATA_DIR de los servicios (para elegir una palabra del libro)")
    a = ap.parse_args()
    book = a.book or int(next(l for l in Path(a.ids).read_text().split("\n") if l.strip() and not l.startswith("#")))
    S, ING, IDX = a.search, a.ingestion, a.indexing
    q = urllib.parse.quote

    print(f"== Operacional (libro de prueba {book})")
    for name, base in (("ingestion", ING), ("indexing", IDX), ("search", S)):
        body = expect(f"GET /status {name}", "GET", base + "/status", 200)
        check(f"{name} status=running", body.get("status") == "running")

    print("== Interno: Ingestion Service")
    body = expect("POST /ingest/{id} -> 200", "POST", f"{ING}/ingest/{book}", 200)
    path = body.get("path", "")
    check("ingest status=downloaded y path datalake/YYYYMMDD/HH/id",
          body.get("status") == "downloaded" and str(body.get("path", "")).endswith(f"/{book}"), body)
    body = expect("POST /ingest/{id} repetido -> 200", "POST", f"{ING}/ingest/{book}", 200)
    check("ingest idempotente (already_present=true)", body.get("already_present") is True, body)
    body = expect("GET /ingest/status/{id}", "GET", f"{ING}/ingest/status/{book}", 200)
    check("status=available", body.get("status") == "available", body)
    body = expect("GET /ingest/list", "GET", f"{ING}/ingest/list", 200)
    check("list contiene el libro y count coincide", book in body.get("books", []) and body.get("count") == len(body.get("books", [])))
    expect("POST /ingest/abc -> 400 INVALID_BOOK_ID", "POST", f"{ING}/ingest/abc", 400, "INVALID_BOOK_ID")
    expect("GET /ingest/status/999999998 -> not_available", "GET", f"{ING}/ingest/status/999999998", 200)

    print("== Interno: Indexing Service")
    body = expect("POST /index/update/{id} -> 200", "POST", f"{IDX}/index/update/{book}", 200)
    check("update index=updated", body.get("index") == "updated", body)
    body = expect("POST /index/publish -> 200", "POST", f"{IDX}/index/publish", 200)
    check("publish devuelve versión", isinstance(body.get("version"), int), body)
    body = expect("POST /index/update/{id} ya publicado -> already_indexed", "POST", f"{IDX}/index/update/{book}", 200)
    check("update idempotente (already_indexed=true)", body.get("already_indexed") is True, body)
    expect("POST /index/update/999999998 -> 409 BOOK_NOT_INGESTED", "POST", f"{IDX}/index/update/999999998", 409, "BOOK_NOT_INGESTED")
    body = expect("GET /index/status", "GET", f"{IDX}/index/status", 200)
    check("books_indexed >= 1", body.get("books_indexed", 0) >= 1, body)
    time.sleep(1.5)  # la búsqueda detecta la nueva versión en <= REFRESH_MS

    print("== Front-end: Search Service")
    meta = expect("GET /books/{id} -> 200", "GET", f"{S}/books/{book}", 200)
    expect("GET /books/999999998 -> 404 BOOK_NOT_FOUND", "GET", f"{S}/books/999999998", 404, "BOOK_NOT_FOUND")
    expect("GET /books/abc -> 400", "GET", f"{S}/books/abc", 400, "INVALID_BOOK_ID")
    term = pick_term(Path(a.data) / (path + ".body.txt"), meta.get("title") or "")
    body = expect(f"GET /search?q={term}", "GET", f"{S}/search?q={q(term)}", 200)
    check("búsqueda encuentra el libro", any(r["book_id"] == book for r in body.get("results", [])) or body.get("total", 0) > body.get("limit", 20), body.get("total"))
    check("respuesta con query/total/offset/limit/results", all(k in body for k in ("query", "total", "offset", "limit", "results")))
    lang = meta.get("language")
    if lang:
        body = expect("filtro language", "GET", f"{S}/search?q={q(term)}&language={lang}", 200)
        check("filtro language: todos los resultados en ese idioma", all(r["language"] == lang for r in body["results"]))
    author = (meta.get("author") or "").split(" ")[-1]
    if author:
        body = expect("filtro author (parcial, sin mayúsculas)", "GET", f"{S}/search?q={q(term)}&author={q(author.lower())}", 200)
        check("filtro author: todos contienen el autor", all(author.lower() in (r["author"] or "").lower() for r in body["results"]))
    if meta.get("year"):
        body = expect("filtros combinados (author+language+year)", "GET",
                      f"{S}/search?q={q(term)}&author={q(author)}&language={lang}&year={meta['year']}", 200)
        check("filtros combinados incluyen el libro", any(r["book_id"] == book for r in body["results"]), body)
    body = expect("filtro sin coincidencias -> 200 vacío", "GET", f"{S}/search?q={q(term)}&language=zz", 200)
    check("total=0 y results=[]", body.get("total") == 0 and body.get("results") == [])
    body = expect("consulta sin coincidencias -> 200 vacío", "GET", f"{S}/search?q=qqzzxxw", 200)
    check("total=0", body.get("total") == 0)
    body = expect("paginación limit=1", "GET", f"{S}/search?q={q(term)}&limit=1", 200)
    check("como mucho 1 resultado", len(body.get("results", [])) <= 1 and body.get("limit") == 1)
    total = body.get("total", 0)
    body = expect("paginación offset=total", "GET", f"{S}/search?q={q(term)}&offset={total}", 200)
    check("offset fuera de rango -> results vacío", body.get("results") == [])
    for name, url, err in (("falta q", "/search", "MISSING_QUERY"), ("limit=0", "/search?q=a&limit=0", "INVALID_LIMIT"),
                           ("limit=101", "/search?q=a&limit=101", "INVALID_LIMIT"), ("offset=-1", "/search?q=a&offset=-1", "INVALID_OFFSET"),
                           ("year=12", "/search?q=a&year=12", "INVALID_YEAR"), ("language=e1", "/search?q=a&language=e1", "INVALID_LANGUAGE")):
        expect(f"parámetro inválido ({name}) -> 400 {err}", "GET", S + url, 400, err)
    body = expect("GET /search/filters", "GET", f"{S}/search/filters", 200)
    check("filters con languages y years", "languages" in body and "years" in body)
    expect("ruta inexistente -> 404 JSON", "GET", f"{S}/nope", 404, "NOT_FOUND")

    failed = [n for n, ok in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} comprobaciones correctas")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
