#!/usr/bin/env python3
"""Genera libros sintéticos con el formato de Project Gutenberg para probar sin red.

    python3 scripts/generate_sample_books.py --out sample_data/books --books 40

Escribe <id>.txt (header, marcadores START/END, cuerpo y footer) y book_ids.txt.
Úsalos con SOURCE=local:sample_data/books en el Ingestion Service.
"""
import argparse
import random
from pathlib import Path

WORDS = ("adventure island ship captain sea whale harpoon storm king queen castle war peace love "
         "letter murder detective london paris garden forest river mountain village journey gold "
         "treasure pirate voyage compass sailor merchant fortune night morning heart friend enemy").split()
FILLER = ("time house hand day eye place work world life way man woman child door voice room word "
          "face light thing water fire road horse sword book letter dream truth secret").split()
STOP = "the and of to a in that it was he".split()
AUTHORS = ["Daniel Defoe", "Jane Austen", "Herman Melville", "Mary Shelley", "Jules Verne",
           "Charles Dickens", "Arthur Conan Doyle", "Miguel de Cervantes"]
LANGS = ["English"] * 6 + ["French", "Spanish"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="sample_data/books")
    ap.add_argument("--books", type=int, default=40)
    ap.add_argument("--words", type=int, default=3000)
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    rng = random.Random(a.seed)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    ids = sorted(rng.sample(range(1, 3000), a.books))
    vocab = WORDS + FILLER
    for bid in ids:
        title = " ".join(w.capitalize() for w in rng.sample(WORDS, 2))
        n = int(a.words * rng.uniform(0.5, 1.5))
        weights = [rng.random() ** 3 for _ in vocab]
        words = rng.choices(vocab + STOP, weights=weights + [3.0] * len(STOP), k=n)
        lines = [" ".join(words[i:i + 12]).capitalize() + "." for i in range(0, n, 12)]
        year = rng.randint(1700, 1920)
        text = (f"The Project Gutenberg eBook of {title}\n\nTitle: {title}\n\n"
                f"Author: {rng.choice(AUTHORS)}\n\nRelease date: May {rng.randint(1, 28)}, {rng.randint(1994, 2024)} "
                f"[eBook #{bid}]\n\nLanguage: {rng.choice(LANGS)}\n\nOriginal publication: {year}\n\n"
                "Credits: SYNTHETIC BOOK for offline testing\n\n"
                f"*** START OF THE PROJECT GUTENBERG EBOOK {title.upper()} ***\n"
                + "\n".join(lines)
                + f"\n*** END OF THE PROJECT GUTENBERG EBOOK {title.upper()} ***\n\nEnd of the Project Gutenberg eBook.\n")
        (out / f"{bid}.txt").write_text(text, encoding="utf-8")
    (out / "book_ids.txt").write_text("".join(f"{i}\n" for i in ids))
    print(f"{len(ids)} libros sintéticos en {out}")


if __name__ == "__main__":
    main()
