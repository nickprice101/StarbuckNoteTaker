"""Simple duplicate detection for training notes.

Usage:
    python detect_duplicates.py --threshold 0.85

The script loads ``training_data_large.py`` and computes TF-IDF cosine similarity
for notes within each category. Any pair whose similarity exceeds the threshold
is reported to help maintain variety in the dataset.
"""
from __future__ import annotations

import argparse
import itertools
from pathlib import Path
from typing import Dict, List, Tuple

try:
    from sklearn.feature_extraction.text import TfidfVectorizer
    from sklearn.metrics.pairwise import cosine_similarity
except ImportError as exc:  # pragma: no cover - dependency message
    raise SystemExit(
        "scikit-learn is required for duplicate detection. Install with 'pip install scikit-learn'."
    ) from exc

# Import dataset by executing the training data file.
TRAINING_DATA_PATH = Path(__file__).with_name("training_data_large.py")


def load_training_data() -> Dict[str, List[Dict[str, str]]]:
    namespace: Dict[str, object] = {}
    exec(TRAINING_DATA_PATH.read_text(), namespace)
    return namespace["category_examples"]


def find_similar_pairs(notes: List[str], threshold: float) -> List[Tuple[int, int, float]]:
    """Return pairs of indices whose cosine similarity is above the threshold."""
    if len(notes) < 2:
        return []
    vectorizer = TfidfVectorizer(stop_words="english")
    matrix = vectorizer.fit_transform(notes)
    similarities = cosine_similarity(matrix)
    flagged: List[Tuple[int, int, float]] = []
    for i, j in itertools.combinations(range(len(notes)), 2):
        score = similarities[i, j]
        if score >= threshold:
            flagged.append((i, j, float(score)))
    return flagged


def main() -> None:
    parser = argparse.ArgumentParser(description="Detect near-duplicate notes by category.")
    parser.add_argument(
        "--threshold",
        type=float,
        default=0.85,
        help="Cosine similarity threshold for reporting pairs (default: 0.85)",
    )
    args = parser.parse_args()

    data = load_training_data()
    duplicates_found = False
    for category, entries in data.items():
        notes = [entry["note"] for entry in entries]
        pairs = find_similar_pairs(notes, args.threshold)
        if pairs:
            duplicates_found = True
            print(f"Category: {category}")
            for i, j, score in sorted(pairs, key=lambda item: item[2], reverse=True):
                print(f"  Pair ({i}, {j}) similarity={score:.3f}")
                print(f"    A: {notes[i]}")
                print(f"    B: {notes[j]}\n")
    if not duplicates_found:
        print("No potential duplicates detected above the threshold.")


if __name__ == "__main__":
    main()
