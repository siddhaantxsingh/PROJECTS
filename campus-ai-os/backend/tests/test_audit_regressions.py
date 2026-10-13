"""Regression tests for the security/privacy audit of Campus AI OS
(docs/audit.md C1-C10). Each failed on the code before its fix."""
import threading
import time

from campus.study import blank_term
from tests.conftest import Client


def cid(w):
    return w["course"]["id"]


def _students(w, n, prefix="x", age_days=2):
    out = []
    for i in range(n):
        c = Client(w["app"])
        c.register_login(f"{prefix}{i}@uni.edu", f"{prefix}{i}")
        c.post("/api/courses/join", json={"join_code": w["course"]["join_code"]})
        out.append(c)
    if age_days:
        w["campus"].db.run("UPDATE app_memberships SET created_at = now() - make_interval(days => %s) WHERE role = 'student'", (age_days,))
    return out


def test_c1_demoted_faculty_lose_instructor_rights(world):
    w = world
    assert w["admin"].patch(f"/api/admin/users/{w['users']['prof']['id']}", json={"role": "student"}).status_code == 204
    assert w["prof"].post(f"/api/courses/{cid(w)}/documents", files={"file": ("x.md", b"# X\n\nSome text here.")}).status_code == 403
    assert w["prof"].get(f"/api/courses/{cid(w)}/analytics").status_code == 403
    assert [c["role"] for c in w["prof"].get("/api/courses").json()] == ["student"]


def test_c1_remove_instructor_but_never_the_last(world):
    w = world
    p2 = Client(w["app"])
    u = p2.register_login("p2@uni.edu", "P2")
    w["admin"].patch(f"/api/admin/users/{u['id']}", json={"role": "faculty"})
    assert w["prof"].post(f"/api/courses/{cid(w)}/instructors", json={"email": "p2@uni.edu"}).status_code == 204
    assert w["prof"].delete(f"/api/courses/{cid(w)}/instructors/{u['id']}").status_code == 204
    assert p2.get(f"/api/courses/{cid(w)}/analytics").status_code == 403
    assert w["prof"].delete(f"/api/courses/{cid(w)}/instructors/{w['users']['prof']['id']}").status_code == 422


def test_c2_analytics_hide_everything_below_k(world):
    w = world
    w["campus"].db.run("UPDATE app_memberships SET created_at = now() - interval '2 days' WHERE role = 'student'")
    w["s1"].post(f"/api/courses/{cid(w)}/ask", json={"query": "when is the final exam"})
    qid = w["s1"].post(f"/api/courses/{cid(w)}/ask", json={"query": "grading"}).json()["question_id"]
    w["s1"].post(f"/api/questions/{qid}/feedback", json={"rating": -1})
    a = w["prof"].get(f"/api/courses/{cid(w)}/analytics").json()
    assert a["questions"] == 2 and not a["eligible_askers_at_least_k"]
    assert a["questions_per_day"] == [] and a["most_cited_documents"] == [] and a["feedback"] is None and a["topics"] == []


def test_c2_fresh_sock_puppets_do_not_unlock_a_single_students_topic(world):
    w = world
    w["campus"].db.run("UPDATE app_memberships SET created_at = now() - interval '2 days' WHERE role = 'student'")
    w["s1"].post(f"/api/courses/{cid(w)}/ask", json={"query": "final exam"})
    for c in _students(w, 4, "sock", age_days=0):  # joined just now
        c.post(f"/api/courses/{cid(w)}/ask", json={"query": "final exam"})
    a = w["prof"].get(f"/api/courses/{cid(w)}/analytics").json()
    assert a["topics"] == [] and not a["eligible_askers_at_least_k"]


def test_c3_account_deletion_erases_free_text(world):
    w = world
    qid = w["s1"].post(f"/api/courses/{cid(w)}/ask", json={"query": "my phone is 555-1234 grading"}).json()["question_id"]
    w["s1"].post(f"/api/questions/{qid}/feedback", json={"rating": 1, "comment": "call me at 555-1234"})
    assert w["s1"].post("/api/me/delete", json={"password": "correct horse battery"}).status_code == 204
    db = w["campus"].db
    assert db.one("SELECT count(*) AS n FROM app_feedback")["n"] == 0
    assert db.one("SELECT count(*) AS n FROM app_questions WHERE query LIKE '%%555%%'")["n"] == 0


def test_c4_question_quota_holds_under_concurrency(world):
    w = world
    w["admin"].patch(f"/api/admin/users/{w['users']['alice']['id']}", json={"daily_question_quota": 1})
    codes = []

    def go(mode):
        codes.append(w["s1"].post(f"/api/courses/{cid(w)}/{mode}", json={"query": "hash table collisions"}).status_code)

    ts = [threading.Thread(target=go, args=("ask" if i % 2 else "research",)) for i in range(16)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    assert codes.count(200) == 1
    assert w["campus"].db.one("SELECT count(*) AS n FROM app_questions")["n"] == 1


def test_c5_storage_quota_holds_under_concurrency(world):
    w = world
    used = w["campus"].db.one("SELECT sum(bytes) AS u FROM app_documents")["u"]
    w["campus"].db.run("UPDATE app_courses SET storage_quota_bytes = %s WHERE id = %s", (int(used) + 300, cid(w)))
    codes = []

    def up(i):
        codes.append(w["prof"].post(f"/api/courses/{cid(w)}/documents", files={"file": (f"n{i}.md", f"# N{i}\n\n".encode() + b"word " * 40 + str(i).encode())}).status_code)

    ts = [threading.Thread(target=up, args=(i,)) for i in range(8)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    r = w["campus"].db.one("SELECT storage_quota_bytes AS q, (SELECT sum(bytes) FROM app_documents) AS u FROM app_courses")
    assert r["u"] <= r["q"] and codes.count(201) == 1


def test_c6_official_quiz_survives_and_last_instructor_cannot_vanish(world):
    w = world
    q = w["prof"].post(f"/api/courses/{cid(w)}/quizzes", json={"title": "Week 1", "questions": 3}).json()
    assert w["prof"].post("/api/me/delete", json={"password": "correct horse battery"}).status_code == 422  # last instructor
    p2 = Client(w["app"])
    u = p2.register_login("p2@uni.edu", "P2")
    w["admin"].patch(f"/api/admin/users/{u['id']}", json={"role": "faculty"})
    w["prof"].post(f"/api/courses/{cid(w)}/instructors", json={"email": "p2@uni.edu"})
    assert w["prof"].post("/api/me/delete", json={"password": "correct horse battery"}).status_code == 204
    assert w["s1"].get(f"/api/quizzes/{q['id']}").status_code == 200


def test_c7_deleting_a_document_deletes_quizzes_quoting_it(world):
    w = world
    q = w["prof"].post(f"/api/courses/{cid(w)}/quizzes", json={"title": "Week 1", "questions": 4}).json()
    assert w["prof"].delete(f"/api/courses/{cid(w)}/documents/{w['docs'][0]['id']}").status_code == 204
    assert w["prof"].get(f"/api/quizzes/{q['id']}").status_code == 404


def test_c8_compare_does_not_block_other_queries(world):
    w = world
    big = lambda n: (f"# Big {n}\n\n" + "".join(f"## S{i}\n\nTopic {i % 97} sentence about item {i} and widget {i % 13} details here.\n\n" for i in range(3000))).encode()
    w["campus"].db.run("UPDATE app_courses SET storage_quota_bytes = 1000000000")
    a = w["prof"].post(f"/api/courses/{cid(w)}/documents", files={"file": ("a.md", big("a"))}).json()
    b = w["prof"].post(f"/api/courses/{cid(w)}/documents", files={"file": ("b.md", big("b"))}).json()
    waited = {}

    def ask():
        time.sleep(0.15)
        t0 = time.time()
        w["s1"].post(f"/api/courses/{cid(w)}/ask", json={"query": "array access"})
        waited["s"] = time.time() - t0

    t = threading.Thread(target=ask)
    t.start()
    r = w["prof"].post(f"/api/courses/{cid(w)}/compare", json={"doc_a": a["id"], "doc_b": b["id"]})
    t.join()
    assert r.status_code == 200 and r.json()["sampled"] is True
    assert waited["s"] < 1.0


def test_c9_blank_term_removes_every_whole_word_occurrence():
    assert blank_term("A heap is a complete binary tree that satisfies the heap property.", "heap") == \
        "A _____ is a complete binary tree that satisfies the _____ property."
    assert blank_term("An array is contiguous.", "a") == "_____: An array is contiguous."
    assert blank_term("A min-heap orders keys.", "heap") == "_____: A min-heap orders keys."


def test_c10_password_endpoints_are_rate_limited(world):
    from campus.app import create_app
    from tests.conftest import ORIGIN

    app = create_app(world["campus"], allowed_origins=[ORIGIN], login_per_min=4)  # register 4/min, password 2/min
    c = Client(app)
    codes = [c.post("/api/auth/register", json={"email": f"r{i}@uni.edu", "name": "R", "password": "correct horse battery"}).status_code
             for i in range(6)]
    assert codes[:4] == [201] * 4 and codes[4:] == [429, 429]
    c.post("/api/auth/login", json={"email": "r0@uni.edu", "password": "correct horse battery"})
    codes = [c.post("/api/me/password", json={"old_password": "nope-nope-nope", "new_password": "x" * 12}).status_code for _ in range(4)]
    assert codes == [403, 403, 429, 429]
