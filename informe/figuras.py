#!/usr/bin/env python3
"""Figures for the Stage 2 report (English labels), built from the benchmark CSV files.

    python figuras.py [carpeta_con_los_csv]     -> figuras/*.png

By default it reads ../benchmarks/results (in the repository) or ../resultados_benchmarks.
Requires pandas and matplotlib.
"""
import re
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import pandas as pd  # noqa: E402

HERE = Path(__file__).resolve().parent
RES = Path(sys.argv[1]) if len(sys.argv) > 1 else next(
    (p for p in (HERE.parent / "benchmarks" / "results", HERE.parent / "resultados_benchmarks") if p.exists()),
    HERE.parent / "benchmarks" / "results")
OUT = HERE / "figuras"
C = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#4a3aa7"]
plt.rcParams.update({"font.size": 9, "font.family": "serif", "axes.spines.top": False,
                     "axes.spines.right": False, "axes.grid": True, "grid.color": "#e6e5e0",
                     "axes.axisbelow": True, "legend.frameon": False, "savefig.bbox": "tight",
                     "figure.dpi": 200})


def save(fig, name):
    OUT.mkdir(exist_ok=True)
    fig.savefig(OUT / f"{name}.png")
    plt.close(fig)
    print("  ", OUT / f"{name}.png")


def e1_label(tag):
    w = re.search(r"WORKERS=(\d+)", tag).group(1)
    r = re.search(r"INDEXING_REPLICAS=(\d+)", tag).group(1)
    return f"{w} worker{'s' if w != '1' else ''}\n{r} indexer{'s' if r != '1' else ''}"


def e2_label(tag):
    r = re.search(r"SEARCH_REPLICAS=(\d+)", tag).group(1)
    t = re.search(r"HTTP_THREADS=(\d+)", tag).group(1)
    return f"{r} search replica{'s' if r != '1' else ''}, {t} HTTP threads"


def pipeline():
    df = pd.read_csv(RES / "pipeline.csv")
    df["config"] = df["tag"].str.replace(r"\s*rep=\d+", "", regex=True)
    g = df.groupby("config", sort=False)
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.3), gridspec_kw={"width_ratios": [1, 1.25]})
    m, s = g["indexing_books_s"].mean(), g["indexing_books_s"].std()
    x = range(len(m))
    axes[0].bar(x, m, yerr=s, color=C[0], capsize=3, width=0.6)
    axes[0].set_xticks(list(x), [e1_label(t) for t in m.index], fontsize=7)
    axes[0].set_ylabel("books published per second")
    axes[0].set_title("(a) End-to-end throughput (100 books)", loc="left")
    parts = [("ingest_ms_mean", "POST /ingest call"), ("update_ms_mean", "POST /index/update call"),
             ("publish_call_ms_mean", "POST /index/publish call"),
             ("publish_wait_ms_mean", "wait until searchable")]
    w = 0.2
    for i, (col, name) in enumerate(parts):
        axes[1].bar([k + (i - 1.5) * w for k in x], g[col].mean() / 1000, w, color=C[i], label=name)
    axes[1].set_yscale("log")
    axes[1].set_xticks(list(x), [e1_label(t) for t in m.index], fontsize=7)
    axes[1].set_ylabel("seconds (log scale)")
    axes[1].set_title("(b) Where the time goes (mean per book or per call)", loc="left")
    axes[1].legend(fontsize=7, loc="upper left", ncol=2, bbox_to_anchor=(0, 1.0))
    axes[1].set_ylim(0.003, 400)
    fig.tight_layout()
    save(fig, "fig_pipeline")


def search():
    df = pd.read_csv(RES / "search_load.csv")
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.2))
    for i, (tag, d) in enumerate(df.groupby("tag", sort=False)):
        g = d.groupby("concurrency")
        kw = dict(marker="osD^"[i], color=C[i], linewidth=1.6, markersize=4, capsize=2, label=e2_label(tag))
        axes[0].errorbar(g.size().index, g["throughput_rps"].mean(), yerr=g["throughput_rps"].std(), **kw)
        axes[1].errorbar(g.size().index, g["p95_ms"].mean(), yerr=g["p95_ms"].std(), **kw)
    for ax in axes:
        ax.set_xscale("log", base=2)
        ax.set_xticks([1, 2, 4, 8, 16, 32], ["1", "2", "4", "8", "16", "32"])
        ax.set_xlabel("concurrent clients (closed loop)")
    axes[0].set_ylabel("queries per second")
    axes[0].set_title("(a) GET /search throughput", loc="left")
    axes[1].set_ylabel("ms")
    axes[1].set_title("(b) GET /search latency, 95th percentile", loc="left")
    axes[0].legend(fontsize=7, loc="lower right")
    fig.tight_layout()
    save(fig, "fig_search")


def resources():
    df = pd.read_csv(RES / "resources.csv")
    df["config"] = df["tag"].str.replace(r"\s*rep=\d+", "", regex=True)
    df["kind"] = df["process"].str.replace(r"-\d+$", "", regex=True)
    # CPU: per sample, sum the replicas of a kind (in cores), then average over the run
    per_t = df.groupby(["config", "tag", "timestamp", "kind"])["cpu_percent"].sum().reset_index()
    cpu = per_t.groupby(["config", "kind"], sort=False)["cpu_percent"].mean().unstack()
    rss = df[df.kind != "HOST"].groupby(["config", "kind"], sort=False)["rss_mb"].max().unstack()
    order = list(dict.fromkeys(df["config"]))
    cpu, rss = cpu.reindex(order), rss.reindex(order)
    labels = [e1_label(t).replace("\n", ", ") if t.startswith("E1") else e2_label(t) for t in order]
    labels = [("E1: " if t.startswith("E1") else "E2: ") + lab for t, lab in zip(order, labels)]
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.9), sharey=True)
    y = range(len(order))
    kinds = ["ingestion", "indexing", "search"]
    h = 0.27
    for i, k in enumerate(kinds):
        axes[0].barh([v + (i - 1) * h for v in y], cpu[k] / 100, h, color=C[i], label=k)
        axes[1].barh([v + (i - 1) * h for v in y], rss[k], h, color=C[i], label=k)
    host_cores = cpu["HOST"] / 100 * 28  # HOST is % of the whole machine (28 logical CPUs)
    axes[0].plot(host_cores, list(y), "k|", markersize=9, markeredgewidth=2, label="whole host")
    axes[0].set_yticks(list(y), labels, fontsize=7)
    axes[0].invert_yaxis()
    axes[0].set_xlabel("mean CPU, in cores (sum of replicas)")
    axes[0].set_title("(a) CPU use per service", loc="left")
    axes[0].legend(fontsize=7, loc="upper right")
    axes[1].set_xlabel("MB")
    axes[1].set_title("(b) Peak resident memory of one process", loc="left")
    fig.tight_layout()
    save(fig, "fig_resources")


def jmh():
    df = pd.read_csv(RES / "jmh_all.csv")
    df["op"] = df["Benchmark"].str.split(".").str[-1]
    to_ms = {"ns/op": 1e-6, "us/op": 1e-3, "ms/op": 1.0, "s/op": 1e3}
    av = df[df["Mode"] == "avgt"].copy()
    av["ms"] = av["Score"] * av["Unit"].map(to_ms)
    av["err"] = av["Score Error (99.9%)"] * av["Unit"].map(to_ms)
    fig, axes = plt.subplots(1, 3, figsize=(10, 3.1))
    for i, op in enumerate(["parseHeader", "splitHeaderBody", "tokenizeAndCount"]):
        d = av[av.op == op].sort_values("Param: words")
        axes[0].errorbar(d["Param: words"], d["ms"], yerr=d["err"], marker="os^"[i], color=C[i],
                         label=op, linewidth=1.6, markersize=4)
    axes[0].set_xscale("log")
    axes[0].set_yscale("log")
    axes[0].set_xlabel("words in the book")
    axes[0].set_ylabel("ms per operation (log)")
    axes[0].set_title("(a) Text processing", loc="left")
    axes[0].legend(fontsize=7)
    p = av[av.op == "publishBatch"].sort_values("Param: batchBooks")
    b = p["Param: batchBooks"].astype(int)
    x = range(len(p))
    axes[1].bar([k - 0.2 for k in x], p["ms"] / 1000, 0.4, color=C[0], label="per batch")
    axes[1].bar([k + 0.2 for k in x], p["ms"] / 1000 / b.values, 0.4, color=C[1], label="per book")
    axes[1].set_xticks(list(x), [f"{n} book{'s' if n > 1 else ''}" for n in b])
    axes[1].set_ylabel("seconds")
    axes[1].set_title("(b) Publishing one index segment", loc="left")
    axes[1].legend(fontsize=7)
    rows = []
    for f in sorted(RES.glob("jmh_threads_*_t*.csv")):
        d = pd.read_csv(f)
        d["threads"] = int(re.search(r"_t(\d+)$", f.stem).group(1))
        rows.append(d[d["Mode"] == "thrpt"] if (d["Mode"] == "thrpt").any() else d)
    t = pd.concat(rows)
    for i, (bench, d) in enumerate(t.groupby("Benchmark")):
        d = d.sort_values("threads")
        speed = d.threads / d["Score"] if d["Mode"].iloc[0] == "avgt" else d["Score"]
        name = "search (thrpt)" if "Search" in bench else "tokenize 50k words (avgt)"
        axes[2].plot(d.threads, speed / speed.iloc[0], marker="os"[i], color=C[i], linewidth=1.6,
                     markersize=4, label=name)
    axes[2].plot([1, 8], [1, 8], "--", color="#8a8984", linewidth=1, label="ideal")
    axes[2].set_xticks([1, 2, 4, 8])
    axes[2].set_xlabel("JMH threads (-t)")
    axes[2].set_ylabel("speedup over 1 thread")
    axes[2].set_title("(c) Threads in one process", loc="left")
    axes[2].legend(fontsize=7)
    fig.tight_layout()
    save(fig, "fig_jmh")


if __name__ == "__main__":
    pipeline()
    search()
    resources()
    jmh()
