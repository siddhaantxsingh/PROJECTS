"""End-to-end API tests against real PostgreSQL + pgvector."""
import pytest

from campus.study import blank_term, extract_definitions, grade, make_quiz
from tests.conftest import LECTURE, ORIGIN, Client


def cid(w):
    return w["course"]["id"]


# ------------------------------------------------------------------ vertical slice


def test_vertical_slice_upload_ask_grounded_answer(world):
    w = world
    r = w["s1"].post(f"/api/courses/{cid(w)}/ask", json={"query": "How are hash collisions resolved?"}).json()
    assert not r["abstained"]
    assert "chaining" in r["answer"].lower()
    assert r["grounding"]["groundedness"] == 1.0
    src = r["sources"][0]
    assert src["document"] == "CS201 Data Structures" and src["section"].endswith("Hashing") and src["pages"] == [1, 1]
    assert r["question_id"]


def test_answer_abstains_outside_course_material(world):
    r = world["s1"].post(f"/api/courses/{cid(world)}/ask", json={"query": "Who painted the Mona Lisa?"}).json()
    assert r["abstained"] and r["grounding"]["groundedness"] is None


def test_pdf_page_reference(world):
    from fpdf import FPDF

    pdf = FPDF()
    pdf.set_font("helvetica", size=11)
    for t in ["Course logistics.", "Dijkstra's algorithm finds shortest paths in graphs with non-negative edge weights.", "Appendix."]:
        pdf.add_page()
        pdf.multi_cell(0, 6, t)
    w = world
    assert w["prof"].post(f"/api/courses/{cid(w)}/documents", files={"file": ("graphs.pdf", bytes(pdf.output()))}).status_code == 201
    r = w["s1"].post(f"/api/courses/{cid(w)}/ask", json={"query": "Which algorithm finds shortest paths with non-negative weights?"}).json()
    assert r["sources"][0]["pages"] == [2, 2]


# ------------------------------------------------------------------ RBAC / isolation


def test_non_members_cannot_see_course(world):
    w, o = world, world["outsider"]
    for method, path, body in [("get", f"/api/courses/{cid(w)}/documents", None), ("post", f"/api/courses/{cid(w)}/ask", {"query": "hash"}),
                               ("get", f"/api/courses/{cid(w)}/flashcards", None), ("get", f"/api/courses/{cid(w)}/analytics", None),
                               ("post", f"/api/courses/{cid(w)}/research", {"query": "hash"})]:
        r = getattr(o, method)(path, json=body) if body else getattr(o, method)(path)
        assert r.status_code == 404, (path, r.status_code)
    # The admin manages but does not read course content.
    assert w["admin"].post(f"/api/courses/{cid(w)}/ask", json={"query": "hash"}).status_code == 404


def test_students_cannot_do_instructor_actions(world):
    w, s = world, world["s1"]
    assert s.post(f"/api/courses/{cid(w)}/documents", files={"file": ("x.md", b"# X\n\nText.")}).status_code == 403
    assert s.delete(f"/api/courses/{cid(w)}/documents/{w['docs'][0]['id']}").status_code == 403
    assert s.get(f"/api/courses/{cid(w)}/analytics").status_code == 403
    assert s.delete(f"/api/courses/{cid(w)}").status_code == 403
    assert s.post("/api/courses", json={"code": "X1", "title": "Mine"}).status_code == 403
    assert s.get("/api/admin/users").status_code == 403
    assert all(c.get("join_code") is None for c in s.get("/api/courses").json())


def test_courses_are_isolated_in_retrieval(world):
    w = world
    other = w["prof"].post("/api/courses", json={"code": "HIST1", "title": "History"}).json()
    w["prof"].post(f"/api/courses/{other['id']}/documents", files={"file": ("h.md", b"# History\n\n## Rome\n\nThe Roman republic used chaining of hash tables never.\n")})
    r = w["prof"].post(f"/api/courses/{other['id']}/ask", json={"query": "separate chaining per-bucket lists collisions", }).json()
    assert all(s["document"] != "CS201 Data Structures" for s in r["sources"])
    # doc_ids of another course cannot be used to reach its content
    r = w["prof"].post(f"/api/courses/{other['id']}/ask", json={"query": "hash table", "doc_ids": [w["docs"][0]["id"]]}).json()
    assert r["sources"] == [] and r["abstained"]


def test_csrf_origin_and_auth_required(world):
    w = world
    assert w["s1"].post(f"/api/courses/{cid(w)}/ask", json={"query": "hash"}, headers={"Origin": "https://evil.example"}).status_code == 403
    anon = Client(w["app"])
    assert anon.get("/api/courses").status_code == 401
    r = anon.c.post("/api/auth/login", json={"email": "alice@uni.edu", "password": "correct horse battery"}, headers={"Origin": ""})
    assert r.status_code == 403  # no Origin on a state-changing request


def test_self_registration_cannot_choose_role(world):
    anon = Client(world["app"])
    r = anon.post("/api/auth/register", json={"email": "x@uni.edu", "name": "X", "password": "correct horse battery", "role": "admin"})
    assert r.status_code == 201 and r.json()["role"] == "student"


def test_login_errors_and_rate_limit(db_url, monkeypatch):
    from campus.app import build, create_app

    _, _, campus = build(db_url, dim=64)
    c = Client(create_app(campus, allowed_origins=[ORIGIN], login_per_min=3))
    c.post("/api/auth/register", json={"email": "a@uni.edu", "name": "A", "password": "correct horse battery"})
    assert c.post("/api/auth/login", json={"email": "a@uni.edu", "password": "wrong password!"}).status_code == 401
    assert c.post("/api/auth/login", json={"email": "nobody@uni.edu", "password": "wrong password!"}).status_code == 401
    assert c.post("/api/auth/login", json={"email": "a@uni.edu", "password": "correct horse battery"}).status_code == 200
    assert c.post("/api/auth/login", json={"email": "a@uni.edu", "password": "correct horse battery"}).status_code == 429
    assert c.post("/api/auth/register", json={"email": "b@uni.edu", "name": "B", "password": "short"}).status_code == 422
    assert c.post("/api/auth/register", json={"email": "a@uni.edu", "name": "A", "password": "correct horse battery"}).status_code == 409


def test_session_cookie_flags_and_logout(world):
    c = Client(world["app"])
    c.post("/api/auth/register", json={"email": "c@uni.edu", "name": "C", "password": "correct horse battery"})
    r = c.post("/api/auth/login", json={"email": "c@uni.edu", "password": "correct horse battery"})
    cookie = r.headers["set-cookie"].lower()
    assert "httponly" in cookie and "samesite=lax" in cookie
    assert c.get("/api/me").status_code == 200
    assert c.post("/api/auth/logout").status_code == 204
    assert c.get("/api/me").status_code == 401


def test_password_change_revokes_sessions(world):
    s = world["s1"]
    other = Client(world["app"])
    other.post("/api/auth/login", json={"email": "alice@uni.edu", "password": "correct horse battery"})
    assert s.post("/api/me/password", json={"old_password": "correct horse battery", "new_password": "a much better passphrase"}).status_code == 204
    assert other.get("/api/me").status_code == 401 and s.get("/api/me").status_code == 401


# ------------------------------------------------------------------ study tools


def test_flashcards_extracted_with_citations(world):
    cards = world["s1"].get(f"/api/courses/{cid(world)}/flashcards").json()
    terms = {c["term"].lower() for c in cards}
    assert {"array", "linked list", "hash table", "binary search tree", "heap"} <= terms
    arr = next(c for c in cards if c["term"].lower() == "array")
    assert arr["section"].endswith("Arrays") and arr["page"] == 1


def test_quiz_hides_answers_and_grades(world):
    w, s = world, world["s1"]
    q = s.post(f"/api/courses/{cid(w)}/quizzes", json={"title": "Practice", "questions": 4}).json()
    quiz = s.get(f"/api/quizzes/{q['id']}").json()
    assert all("answer_index" not in x for x in quiz["questions"])
    assert all(len(x["options"]) == 4 and "_____" in x["prompt"] for x in quiz["questions"])
    # Answer by reading the blank's definition: pick the option whose card defines it.
    cards = {c["term"]: c["definition"] for c in s.get(f"/api/courses/{cid(w)}/flashcards").json()}
    answers = []
    for x in quiz["questions"]:
        answers.append(next(i for i, o in enumerate(x["options"]) if blank_term(cards[o], o) == x["prompt"]))
    res = s.post(f"/api/quizzes/{q['id']}/attempts", json={"answers": answers}).json()
    assert res["score"] == res["total"] == 4
    assert s.post(f"/api/quizzes/{q['id']}/attempts", json={"answers": [0]}).status_code == 422
    # Another student cannot see Alice's practice quiz; the instructor can.
    w["s2"].post("/api/courses/join", json={"join_code": w["course"]["join_code"]})
    assert w["s2"].get(f"/api/quizzes/{q['id']}").status_code == 404
    assert w["prof"].get(f"/api/quizzes/{q['id']}").status_code == 200


def test_instructor_quiz_visible_to_students(world):
    w = world
    q = w["prof"].post(f"/api/courses/{cid(w)}/quizzes", json={"title": "Week 1", "questions": 3}).json()
    listed = w["s1"].get(f"/api/courses/{cid(w)}/quizzes").json()
    assert any(x["id"] == q["id"] and x["official"] for x in listed)


def test_research_mode_groups_by_document(world):
    r = world["s1"].post(f"/api/courses/{cid(world)}/research", json={"query": "hash tables collision resolution"}).json()
    names = [d["document"] for d in r["documents"]]
    assert "CS201 Data Structures" in names and "CS201 Syllabus" in names
    assert all(e["page"] == 1 and e["sentence"] for d in r["documents"] for e in d["evidence"])


def test_compare_documents(world):
    w = world
    r = w["s1"].post(f"/api/courses/{cid(w)}/compare", json={"doc_a": w["docs"][0]["id"], "doc_b": w["docs"][1]["id"]}).json()
    assert "hash" in r["shared_terms"]
    assert "exam" in r["distinctive_b"] and "array" in r["distinctive_a"]
    other = w["prof"].post("/api/courses", json={"code": "Z", "title": "Z"}).json()
    assert w["prof"].post(f"/api/courses/{other['id']}/compare", json={"doc_a": w["docs"][0]["id"], "doc_b": w["docs"][1]["id"]}).status_code == 404


def test_study_units():
    defs = dict(extract_definitions("A stack is a collection that follows last-in first-out order. It is used widely. "
                                    "Recursion refers to a function calling itself directly or indirectly."))
    assert set(defs) == {"stack", "Recursion"}
    cards = [{"term": t, "definition": f"{t} is a thing number {i} here.", "page": 1, "section": "", "document_id": "d"} for i, t in enumerate("abcde")]
    qs = make_quiz(cards, 3, seed=7)
    assert qs == make_quiz(cards, 3, seed=7)  # reproducible
    assert grade(qs, [q["answer_index"] for q in qs]) == (3, [True, True, True])
    with pytest.raises(ValueError):
        make_quiz(cards[:3], 2, seed=1)


# ------------------------------------------------------------------ feedback, quotas, analytics


def test_feedback_only_on_own_questions(world):
    w = world
    qid = w["s1"].post(f"/api/courses/{cid(w)}/ask", json={"query": "array access time"}).json()["question_id"]
    assert w["s1"].post(f"/api/questions/{qid}/feedback", json={"rating": 1, "comment": "helpful"}).status_code == 204
    assert w["s1"].post(f"/api/questions/{qid}/feedback", json={"rating": -1}).status_code == 204  # update, not duplicate
    assert w["prof"].post(f"/api/questions/{qid}/feedback", json={"rating": 1}).status_code == 404
    assert w["s1"].post(f"/api/questions/{qid}/feedback", json={"rating": 5}).status_code == 422


def test_daily_question_quota(world):
    w = world
    alice = w["users"]["alice"]
    assert w["admin"].patch(f"/api/admin/users/{alice['id']}", json={"daily_question_quota": 2}).status_code == 204
    codes = [w["s1"].post(f"/api/courses/{cid(w)}/ask", json={"query": "heap property"}).status_code for _ in range(3)]
    assert codes == [200, 200, 429]


def test_storage_quota(world):
    w = world
    w["campus"].db.run("UPDATE app_courses SET storage_quota_bytes = 3000 WHERE id = %s", (cid(w),))
    r = w["prof"].post(f"/api/courses/{cid(w)}/documents", files={"file": ("big.md", b"# Big\n\n" + b"word " * 1000)})
    assert r.status_code == 429


def test_analytics_are_aggregated_and_k_anonymous(world):
    w = world
    clients = [w["s1"]]
    for i in range(5):
        c = Client(w["app"])
        c.register_login(f"s{i}@uni.edu", f"S{i}")
        c.post("/api/courses/join", json={"join_code": w["course"]["join_code"]})
        clients.append(c)
    # Members for more than a day are eligible for analytics.
    w["campus"].db.run("UPDATE app_memberships SET created_at = now() - interval '2 days' WHERE role = 'student'")
    for c in clients[:5]:
        c.post(f"/api/courses/{cid(w)}/ask", json={"query": "explain hashing collisions"})
    clients[5].post(f"/api/courses/{cid(w)}/ask", json={"query": "my secret question about trees"})
    a = w["prof"].get(f"/api/courses/{cid(w)}/analytics").json()
    assert a["questions"] == 6 and a["distinct_askers"] == 6
    terms = {t["term"] for t in a["topics"]}
    assert {"hashing", "collisions"} <= terms
    assert "secret" not in terms and "trees" not in terms  # asked by fewer than 5 students
    assert "query" not in str(a)  # no raw questions
    assert a["most_cited_documents"][0]["document"] == "CS201 Data Structures"


# ------------------------------------------------------------------ deletion


def test_delete_document_removes_content_and_flashcards(world):
    w = world
    did = w["docs"][0]["id"]
    assert w["prof"].delete(f"/api/courses/{cid(w)}/documents/{did}").status_code == 204
    r = w["s1"].post(f"/api/courses/{cid(w)}/ask", json={"query": "linked list pointer next node"}).json()
    assert all(s["doc_id"] != did for s in r["sources"])
    assert w["s1"].get(f"/api/courses/{cid(w)}/flashcards").json() == []
    n = w["campus"].engine.store.conn.execute("SELECT count(*) FROM chunks WHERE doc_id = %s", (did,)).fetchone()[0]
    assert n == 0


def test_account_deletion_anonymises_questions(world):
    w = world
    w["s1"].post(f"/api/courses/{cid(w)}/ask", json={"query": "array"})
    assert w["s1"].post("/api/me/delete", json={"password": "wrong"}).status_code == 403
    assert w["s1"].post("/api/me/delete", json={"password": "correct horse battery"}).status_code == 204
    db = w["campus"].db
    assert db.one("SELECT count(*) AS n FROM app_users WHERE email = 'alice@uni.edu'")["n"] == 0
    assert db.one("SELECT count(*) AS n FROM app_questions WHERE query = 'array'")["n"] == 0  # text erased
    assert db.one("SELECT count(*) AS n FROM app_questions WHERE user_id IS NULL AND query = ''")["n"] == 1  # count kept
    assert w["s1"].get("/api/me").status_code == 401


def test_course_deletion_is_complete_and_resumable(world, monkeypatch):
    w = world
    campus = w["campus"]
    real = campus.engine.delete
    calls = {"n": 0}

    def flaky(tenant, doc_id):
        calls["n"] += 1
        if calls["n"] == 2:
            raise RuntimeError("crash mid-deletion")
        return real(tenant, doc_id)

    monkeypatch.setattr(campus.engine, "delete", flaky)
    assert w["s1"].get("/api/courses").json()  # visible before
    with pytest.raises(RuntimeError):
        w["prof"].delete(f"/api/courses/{cid(w)}")
    # Half-deleted course is invisible to everyone ...
    assert w["s1"].get("/api/courses").json() == []
    assert w["s1"].post(f"/api/courses/{cid(w)}/ask", json={"query": "array"}).status_code == 404
    # ... and a retry by the owner finishes the job.
    monkeypatch.setattr(campus.engine, "delete", real)
    assert w["prof"].delete(f"/api/courses/{cid(w)}").status_code == 204
    assert campus.db.one("SELECT count(*) AS n FROM app_courses")["n"] == 0
    assert campus.engine.store.conn.execute("SELECT count(*) FROM documents").fetchone()[0] == 0


def test_admin_disable_revokes_sessions_and_cannot_self_demote(world):
    w = world
    alice = w["users"]["alice"]
    assert w["admin"].patch(f"/api/admin/users/{alice['id']}", json={"disabled": True}).status_code == 204
    assert w["s1"].get("/api/me").status_code == 401
    assert w["s1"].post("/api/auth/login", json={"email": "alice@uni.edu", "password": "correct horse battery"}).status_code == 403
    me = w["admin"].get("/api/me").json()
    assert w["admin"].patch(f"/api/admin/users/{me['id']}", json={"role": "student"}).status_code == 422
    stats = w["admin"].get("/api/admin/stats").json()
    assert stats["users"] >= 5 and stats["courses"] == 1


def test_invalid_ids_and_inputs(world):
    w = world
    assert w["s1"].get("/api/courses/not-a-uuid/documents").status_code == 404
    assert w["s1"].get("/api/quizzes/00000000-0000-0000-0000-000000000000").status_code == 404
    assert w["s1"].post(f"/api/courses/{cid(w)}/ask", json={"query": ""}).status_code == 422
    assert w["prof"].post(f"/api/courses/{cid(w)}/documents", files={"file": ("bad.pdf", b"%PDF-1.4 junk")}).status_code == 422


def test_reupload_same_file_is_idempotent(world):
    w = world
    r = w["prof"].post(f"/api/courses/{cid(w)}/documents", files={"file": ("again.md", LECTURE)})
    assert r.status_code == 201 and r.json()["created"] is False
    assert len(w["prof"].get(f"/api/courses/{cid(w)}/documents").json()) == 2
