#!/usr/bin/env python3
"""Gráficas de los benchmarks del Stage 2 (requiere pandas y matplotlib; anaconda los trae).

    python3 benchmarks/plot_results.py          -> benchmarks/results/plots/*.png

Lee los CSV que encuentre en benchmarks/results/: search_load.csv, pipeline.csv,
resources.csv, jmh_all.csv y jmh_threads_*_t*.csv. Muestra media ± desviación típica
de las repeticiones y etiqueta cada configuración (réplicas, hilos, concurrencia).
"""
import re
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import pandas as pd  # noqa: E402

RES = Path(__file__).resolve().parent / "results"
OUT = RES / "plots"
COLORS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#4a3aa7", "#e34948"]
MARKERS = ["o", "s", "^", "D", "v", "P", "X", "*"]
plt.rcParams.update({"font.size": 9, "axes.spines.top": False, "axes.spines.right": False, "axes.grid": True,
                     "grid.color": "#e6e5e0", "axes.axisbelow": True, "legend.frameon": False,
                     "savefig.bbox": "tight", "figure.dpi": 150})


def save(fig, name):
    OUT.mkdir(parents=True, exist_ok=True)
    fig.savefig(OUT / f"{name}.png")
    plt.close(fig)
    print("  ", OUT / f"{name}.png")


def search_load():
    f = RES / "search_load.csv"
    if not f.exists():
        return
    df = pd.read_csv(f)
    g = df.groupby(["tag", "concurrency"]).agg(rps=("throughput_rps", "mean"), rps_sd=("throughput_rps", "std"),
                                               p95=("p95_ms", "mean"), p95_sd=("p95_ms", "std"),
                                               mean=("mean_ms", "mean")).reset_index().fillna(0)
    fig, axes = plt.subplots(1, 2, figsize=(10, 3.6))
    for i, (tag, d) in enumerate(g.groupby("tag", sort=False)):
        kw = dict(color=COLORS[i % 8], marker=MARKERS[i % 8], linewidth=2, markersize=5, capsize=3, label=tag)
        axes[0].errorbar(d.concurrency, d.rps, yerr=d.rps_sd, **kw)
        axes[1].errorbar(d.concurrency, d.p95, yerr=d.p95_sd, **kw)
    axes[0].set_title("Throughput de GET /search", loc="left")
    axes[0].set_ylabel("consultas / s")
    axes[1].set_title("Latencia p95 de GET /search", loc="left")
    axes[1].set_ylabel("ms")
    for ax in axes:
        ax.set_xlabel("clientes concurrentes")
        ax.set_xscale("log", base=2)
    axes[0].legend(fontsize=8)
    fig.tight_layout()
    save(fig, "search_load")


def pipeline():
    f = RES / "pipeline.csv"
    if not f.exists():
        return
    df = pd.read_csv(f)
    df["config"] = df["tag"].str.replace(r"\s*rep=\d+", "", regex=True)
    g = df.groupby("config", sort=False).agg(idx=("indexing_books_s", "mean"), idx_sd=("indexing_books_s", "std"),
                                             wait=("publish_wait_ms_mean", "mean"), wait_sd=("publish_wait_ms_mean", "std"),
                                             upd=("update_ms_mean", "mean"), ing=("ingest_ms_mean", "mean")).reset_index().fillna(0)
    fig, axes = plt.subplots(1, 2, figsize=(10, 3.6))
    x = range(len(g))
    axes[0].bar(x, g.idx, yerr=g.idx_sd, color=COLORS[0], capsize=3)
    axes[0].set_title("Libros publicados por segundo (ingesta + indexación)", loc="left")
    axes[0].set_ylabel("libros / s")
    w = 0.38
    axes[1].bar([i - w / 2 for i in x], g.ing, w, color=COLORS[0], label="POST /ingest (media)")
    axes[1].bar([i + w / 2 for i in x], g.upd, w, color=COLORS[1], label="POST /index/update (media)")
    axes[1].set_title("Latencia de las llamadas internas", loc="left")
    axes[1].set_ylabel("ms")
    axes[1].legend(fontsize=8)
    for ax in axes:
        ax.set_xticks(list(x), [c.replace("WORKERS=", "workers ").replace(" INDEXING_REPLICAS=", "\nréplicas ")
                               for c in g.config], fontsize=7)
    fig.tight_layout()
    save(fig, "pipeline")


def resources():
    f = RES / "resources.csv"
    if not f.exists():
        return
    df = pd.read_csv(f)
    df["kind"] = df["process"].str.replace(r"-\d+$", "", regex=True)
    # CPU: se suman las réplicas de cada tipo en cada instante y se promedia en el tiempo
    cpu = df.groupby(["tag", "kind", "timestamp"])["cpu_percent"].sum().groupby(["tag", "kind"]).mean()
    rss = df.groupby(["tag", "kind"])["rss_mb"].max()
    g = pd.concat([cpu.rename("cpu"), rss.rename("rss")], axis=1).reset_index()
    tags = list(dict.fromkeys(df["tag"]))
    kinds = [k for k in ["ingestion", "indexing", "search", "HOST"] if k in set(g.kind)]
    fig, axes = plt.subplots(1, 2, figsize=(11, max(3.2, 0.32 * len(tags) + 1.5)))
    w = 0.8 / max(1, len(kinds))
    for j, k in enumerate(kinds):
        d = g[g.kind == k].set_index("tag").reindex(tags)
        y = [i + j * w for i in range(len(tags))]
        axes[0].barh(y, d.cpu, w, color=COLORS[j], label=k)
        if k != "HOST":
            axes[1].barh(y, d.rss, w, color=COLORS[j], label=k)
    for ax in axes:
        ax.set_yticks([i + 0.4 - w / 2 for i in range(len(tags))], tags, fontsize=7)
        ax.invert_yaxis()
    axes[0].set_title("CPU media por tipo de proceso (suma de réplicas = % de un núcleo)", loc="left")
    axes[0].set_xlabel("% CPU (HOST = % de toda la máquina)")
    axes[1].set_title("Memoria máxima (RSS) de un proceso", loc="left")
    axes[1].set_xlabel("MB")
    axes[0].legend(fontsize=8)
    fig.tight_layout()
    save(fig, "resources")


def jmh():
    f = RES / "jmh_all.csv"
    if f.exists():
        df = pd.read_csv(f)
        params = [c for c in df.columns if c.startswith("Param: ")]
        df["name"] = df["Benchmark"].str.split(".").str[-2:].str.join(".") + df[params].apply(
            lambda r: " " + ",".join(f"{c[7:]}={v:g}" if isinstance(v, float) else f"{c[7:]}={v}"
                                     for c, v in r.items() if pd.notna(v)), axis=1)
        # cada benchmark tiene su unidad (us/op, ms/op, ops/ms...): se pasa todo a una común
        to_ms = {"ns/op": 1e-6, "us/op": 1e-3, "ms/op": 1.0, "s/op": 1e3}
        to_s = {"ops/ns": 1e9, "ops/us": 1e6, "ops/ms": 1e3, "ops/s": 1.0, "ops/min": 1 / 60}
        for mode, d in df.groupby("Mode"):
            factor, unit = (d["Unit"].map(to_ms), "ms/op") if mode == "avgt" else (d["Unit"].map(to_s), "ops/s")
            fig, ax = plt.subplots(figsize=(8, 0.3 * len(d) + 1.2))
            ax.barh(range(len(d)), d["Score"] * factor, xerr=d["Score Error (99.9%)"] * factor,
                    color=COLORS[0], capsize=3)
            ax.set_yticks(range(len(d)), d["name"], fontsize=7)
            ax.invert_yaxis()
            ax.set_xscale("log")  # los tiempos van de microsegundos a segundos
            ax.set_xlabel(unit + " (escala logarítmica)")
            ax.set_title(f"JMH ({mode}), media ± error al 99,9 %", loc="left")
            save(fig, f"jmh_{mode}")
    files = sorted(RES.glob("jmh_threads_*_t*.csv"))
    if files:
        rows = []
        for p in files:
            t = int(re.search(r"_t(\d+)$", p.stem).group(1))
            d = pd.read_csv(p)
            d["threads"] = t
            rows.append(d[d["Mode"] == "thrpt"] if (d["Mode"] == "thrpt").any() else d)
        df = pd.concat(rows)
        fig, ax = plt.subplots(figsize=(6, 3.4))
        for i, (b, d) in enumerate(df.groupby("Benchmark")):
            d = d.sort_values("threads")
            # en modo avgt el Score es tiempo por operación y por hilo: rendimiento total = hilos / tiempo
            speed = d.threads / d["Score"] if d["Mode"].iloc[0] == "avgt" else d["Score"]
            ax.plot(d.threads, speed / speed.iloc[0], marker=MARKERS[i], color=COLORS[i], linewidth=2, label=b.split(".")[-2])
        ax.plot([1, df.threads.max()], [1, df.threads.max()], "--", color="#8a8984", linewidth=1, label="ideal")
        ax.set_xlabel("hilos (-t)")
        ax.set_ylabel("speedup frente a 1 hilo")
        ax.set_title("Escalado con hilos dentro de un proceso (JMH)", loc="left")
        ax.legend(fontsize=8)
        save(fig, "jmh_threads")


if __name__ == "__main__":
    search_load()
    pipeline()
    resources()
    jmh()
