"""Study tools built on the course's retrieval index — deterministic, every
item traceable to a document page.

* Flashcards: definitional sentences ("X is ...", "X refers to ...",
  "X is defined as ...", "X: ...") extracted from course chunks. The front
  is the term, the back is the source sentence, with page and section.
* Quizzes: multiple-choice cloze questions generated from flashcards: the
  definition with the term blanked out, the correct term, and three
  distractor terms from other flashcards of the same course (seeded
  shuffle, so a quiz is reproducible). Needs >= 4 flashcards.
* Research mode: deeper retrieval (RM3 expansion, more candidates) grouped
  by document, with the best evidence sentences per document.
* Comparison: distinctive and shared key terms of two documents (BM25 idf
  weighted term frequencies) and section alignment by embedding cosine.

These are retrieval/extraction features, not LLM generation; their limits
are documented in docs/limitations.md.
"""
from __future__ import annotations

import math
import random
import re
from collections import Counter, defaultdict

from groundwork.retrieve import Corpus, RetrievalConfig, retrieve
from groundwork.text import STOPWORDS, split_sentences, tokenize

_DEF_PATTERNS = [
    re.compile(r"^(?:An?|The)?\s*(?P<term>[A-Za-z][\w\-/() ]{1,48}?)\s+(?:is|are)\s+(?:defined as|called|known as)\s+(?P<def>.{15,})$"),
    re.compile(r"^(?:An?|The)?\s*(?P<term>[A-Za-z][\w\-/() ]{1,48}?)\s+(?:refers to|means|denotes)\s+(?P<def>.{15,})$"),
    re.compile(r"^(?:An?|The)?\s*(?P<term>[A-Za-z][\w\-/() ]{1,48}?)\s+(?:is|are)\s+(?P<def>(?:an?|the)\s.{12,})$"),
    re.compile(r"^(?P<term>[A-Z][\w\-/() ]{1,48}?):\s+(?P<def>.{20,})$"),
]
_BAD_TERM_START = {"it", "this", "that", "these", "there", "they", "he", "she", "we", "you", "which", "what", "each", "here", "one"}


def extract_definitions(text: str) -> list[tuple[str, str]]:
    """(term, sentence) pairs. Terms of 1-5 words that are not pronouns;
    sentences of 6-45 words."""
    out = []
    for sent in split_sentences(text):
        words = sent.split()
        if not 6 <= len(words) <= 45:
            continue
        for pat in _DEF_PATTERNS:
            m = pat.match(sent.strip())
            if not m:
                continue
            term = m.group("term").strip(" ,;")
            tw = term.split()
            if not 1 <= len(tw) <= 5 or tw[0].lower() in _BAD_TERM_START or term.lower() in STOPWORDS:
                break
            out.append((term, sent.strip()))
            break
    return out


def flashcards_for(corpus: Corpus, doc_id: str) -> list[dict]:
    cards, seen = [], set()
    for c in sorted((c for c in corpus.chunks.values() if c.doc_id == doc_id), key=lambda c: c.ordinal):
        for term, sent in extract_definitions(c.text):
            key = term.lower()
            if key in seen:
                continue
            seen.add(key)
            cards.append({"term": term, "definition": sent, "page": c.page_start, "section": c.section})
    return cards


def blank_term(definition: str, term: str) -> str:
    """Blank EVERY whole-word occurrence of the term (a remaining occurrence
    would give the answer away; a match inside another word is not one)."""
    pat = re.compile(r"(?<![\w-])" + re.escape(term) + r"(?![\w-])", re.I)
    return pat.sub("_____", definition) if pat.search(definition) else "_____: " + definition


def make_quiz(cards: list[dict], n: int, seed: int) -> list[dict]:
    """Cloze MCQs. Distractors are terms of OTHER cards; options shuffled
    with a seeded RNG. Raises ValueError with fewer than 4 cards."""
    if len(cards) < 4:
        raise ValueError("at least 4 flashcards are needed to build a quiz")
    rng = random.Random(seed)
    chosen = rng.sample(cards, min(n, len(cards)))
    qs = []
    for card in chosen:
        others = [c["term"] for c in cards if c["term"].lower() != card["term"].lower()]
        distractors = rng.sample(others, 3)
        options = distractors + [card["term"]]
        rng.shuffle(options)
        qs.append({"prompt": blank_term(card["definition"], card["term"]), "options": options,
                   "answer_index": options.index(card["term"]),
                   "source": {"document_id": card["document_id"], "page": card["page"], "section": card["section"]}})
    return qs


def grade(questions: list[dict], answers: list[int]) -> tuple[int, list[bool]]:
    if len(answers) != len(questions):
        raise ValueError("answer count does not match the quiz")
    correct = [isinstance(a, int) and a == q["answer_index"] for q, a in zip(questions, answers)]
    return sum(correct), correct


def research(corpus: Corpus, query: str, per_doc: int = 2, k: int = 12) -> dict:
    hits = retrieve(corpus, query, k, RetrievalConfig("bm25", rewrite="rm3", rerank=True, candidates=200))
    qterms = set(tokenize(query))
    by_doc: dict[str, list] = defaultdict(list)
    for h in hits:
        by_doc[h.chunk.doc_id].append(h)
    docs = []
    for doc_id, hs in by_doc.items():
        evidence = []
        for h in hs:
            for s in split_sentences(h.chunk.text):
                st = set(tokenize(s))
                cov = len(st & qterms) / len(qterms) if qterms else 0
                if cov > 0:
                    evidence.append((cov, h.score, s, h.chunk))
        evidence.sort(key=lambda e: (-e[0], -e[1]))
        docs.append({"document_id": doc_id, "document": hs[0].chunk.doc_name, "best_score": round(hs[0].score, 4),
                     "evidence": [{"sentence": s, "page": ch.page_start, "section": ch.section, "coverage": round(cov, 3)}
                                  for cov, _, s, ch in evidence[:per_doc]]})
    docs.sort(key=lambda d: -d["best_score"])
    return {"query": query, "documents": docs}


MAX_COMPARE_CHUNKS = 2000


def _even_sample(items: list, n: int) -> list:
    if len(items) <= n:
        return items
    step = len(items) / n
    return [items[int(i * step)] for i in range(n)]


def snapshot_for_compare(corpus: Corpus, doc_a: str, doc_b: str) -> dict:
    """Copy what `compare` needs (under the engine lock, cheap), so the
    expensive part runs without holding the lock. Each side is capped at
    MAX_COMPARE_CHUNKS chunks (evenly sampled, in document order)."""
    ca = sorted((c for c in corpus.chunks.values() if c.doc_id == doc_a), key=lambda c: c.ordinal)
    cb = sorted((c for c in corpus.chunks.values() if c.doc_id == doc_b), key=lambda c: c.ordinal)
    if not ca or not cb:
        raise ValueError("both documents must belong to this course")
    ca, cb = _even_sample(ca, MAX_COMPARE_CHUNKS), _even_sample(cb, MAX_COMPARE_CHUNKS)
    terms = {t for c in ca + cb for t in tokenize(c.text)}
    return {"ca": ca, "cb": cb, "idf": {t: corpus.bm25.idf(t) for t in terms},
            "va": corpus.vectors_for([c.id for c in ca]), "vb": corpus.vectors_for([c.id for c in cb]),
            "sampled": len(ca) == MAX_COMPARE_CHUNKS or len(cb) == MAX_COMPARE_CHUNKS}


def compare(snap: dict, top: int = 12, align_threshold: float = 0.35) -> dict:
    ca, cb, idf = snap["ca"], snap["cb"], snap["idf"]

    def weights(chunks) -> Counter:
        tf = Counter(t for c in chunks for t in tokenize(c.text) if len(t) > 2 and not t.isdigit())
        return Counter({t: (1 + math.log(n)) * idf.get(t, 0.0) for t, n in tf.items()})

    wa, wb = weights(ca), weights(cb)
    shared = sorted(set(wa) & set(wb), key=lambda t: -(wa[t] + wb[t]))[:top]
    only_a = [t for t, _ in wa.most_common() if t not in wb][:top]
    only_b = [t for t, _ in wb.most_common() if t not in wa][:top]
    alignments = []
    va, vb = snap["va"], snap["vb"]
    if va is not None and vb is not None:
        for start in range(0, len(ca), 256):  # blockwise: memory O(256 * |b|), never |a| x |b|
            sims = va[start:start + 256] @ vb.T
            best = sims.argmax(axis=1)
            for k, j in enumerate(best):
                sim = float(sims[k, j])
                if sim >= align_threshold:
                    c = ca[start + k]
                    alignments.append({"a": {"section": c.section, "page": c.page_start, "excerpt": c.text[:200]},
                                       "b": {"section": cb[j].section, "page": cb[j].page_start, "excerpt": cb[j].text[:200]},
                                       "similarity": round(sim, 3)})
        alignments.sort(key=lambda a: -a["similarity"])
    return {"shared_terms": shared, "distinctive_a": only_a, "distinctive_b": only_b,
            "aligned_sections": alignments[:top], "sampled": snap["sampled"]}
