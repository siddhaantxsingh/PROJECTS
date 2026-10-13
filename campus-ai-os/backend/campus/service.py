"""Business logic with authorization checks. Every public function takes the
acting user and raises `Forbidden` / `NotFound` itself, so the HTTP layer
cannot forget a check.

Authorization model
  platform role  admin   : manage users and roles, delete any course, stats.
                            Does NOT read course content unless a member.
                 faculty : may create courses
                 student : default for self-registration
  course role    instructor : upload/delete documents, manage quizzes,
                              see aggregated analytics, add instructors
                 student    : ask, research, study, compare, attempt quizzes
A course id the user cannot access is reported as 404 (no probing).
"""
from __future__ import annotations

import re
import secrets
import threading
import time
import uuid

from groundwork.engine import Engine
from groundwork.ingest import ParseError

from . import study
from .db import Db


class Forbidden(Exception):
    pass


class NotFound(Exception):
    pass


class QuotaExceeded(Exception):
    pass


class Invalid(Exception):
    pass


def tenant(course_id: str) -> str:
    return f"course:{course_id}"


def _uuid(s: str) -> str:
    try:
        return str(uuid.UUID(str(s)))
    except ValueError:
        raise NotFound("not found")


class Campus:
    MIN_ANALYTICS_USERS = 5  # k-anonymity threshold for every analytics breakdown
    ANALYTICS_MIN_MEMBERSHIP_AGE = "1 day"  # askers who joined later are not counted (limits sock-puppet probing)

    def __init__(self, db: Db, engine: Engine) -> None:
        self.db, self.engine = db, engine
        self._course_locks: dict[str, threading.Lock] = {}
        self._locks_guard = threading.Lock()

    def _course_lock(self, course_id: str) -> threading.Lock:
        with self._locks_guard:
            return self._course_locks.setdefault(course_id, threading.Lock())

    # ---------------------------------------------------------------- helpers

    def audit(self, actor: dict | None, action: str, target: str, **detail) -> None:
        import json

        self.db.run("INSERT INTO app_audit (actor_id, action, target, detail) VALUES (%s,%s,%s,%s)",
                    (actor["id"] if actor else None, action, target, json.dumps(detail)))

    def course_role(self, user: dict, course_id: str) -> str:
        course_id = _uuid(course_id)
        r = self.db.one("SELECT m.role FROM app_memberships m JOIN app_courses c ON c.id = m.course_id "
                        "WHERE m.course_id = %s AND m.user_id = %s AND c.state = 'active'", (course_id, user["id"]))
        if not r:
            raise NotFound("course not found")
        return r["role"]

    def require_instructor(self, user: dict, course_id: str) -> None:
        # Both the course membership AND a current faculty/admin platform role
        # are required, so a demoted account loses instructor rights at once.
        if self.course_role(user, course_id) != "instructor" or user["role"] not in ("faculty", "admin"):
            raise Forbidden("instructors only")

    # ---------------------------------------------------------------- courses

    def create_course(self, user: dict, code: str, title: str) -> dict:
        if user["role"] not in ("faculty", "admin"):
            raise Forbidden("only faculty can create courses")
        code, title = code.strip(), title.strip()
        if not (1 <= len(code) <= 20 and 1 <= len(title) <= 200):
            raise Invalid("code must be 1-20 and title 1-200 characters")
        cid = str(uuid.uuid4())
        join = secrets.token_urlsafe(6)
        with self.db.tx() as c:
            c.execute("INSERT INTO app_courses (id, code, title, owner_id, join_code) VALUES (%s,%s,%s,%s,%s)",
                      (cid, code, title, user["id"], join))
            c.execute("INSERT INTO app_memberships (course_id, user_id, role) VALUES (%s,%s,'instructor')", (cid, user["id"]))
        self.audit(user, "course.create", cid)
        return {"id": cid, "code": code, "title": title, "join_code": join, "role": "instructor"}

    def my_courses(self, user: dict) -> list[dict]:
        rows = self.db.all(
            "SELECT c.id, c.code, c.title, m.role, CASE WHEN m.role = 'instructor' THEN c.join_code END AS join_code "
            "FROM app_memberships m JOIN app_courses c ON c.id = m.course_id "
            "WHERE m.user_id = %s AND c.state = 'active' ORDER BY c.code", (user["id"],))
        return [{**r, "id": str(r["id"])} for r in rows]

    def join_course(self, user: dict, join_code: str) -> dict:
        c = self.db.one("SELECT id, code, title FROM app_courses WHERE join_code = %s AND state = 'active'", (join_code.strip(),))
        if not c:
            raise NotFound("invalid join code")
        self.db.run("INSERT INTO app_memberships (course_id, user_id, role) VALUES (%s,%s,'student') ON CONFLICT DO NOTHING",
                    (c["id"], user["id"]))
        self.audit(user, "course.join", str(c["id"]))
        return {"id": str(c["id"]), "code": c["code"], "title": c["title"], "role": self.course_role(user, str(c["id"]))}

    def add_instructor(self, user: dict, course_id: str, email: str) -> None:
        self.require_instructor(user, course_id)
        target = self.db.one("SELECT id, role FROM app_users WHERE email = %s AND NOT disabled", (email.strip().lower(),))
        if not target or target["role"] not in ("faculty", "admin"):
            raise Invalid("only an existing faculty account can be made an instructor")
        self.db.run("INSERT INTO app_memberships (course_id, user_id, role) VALUES (%s,%s,'instructor') "
                    "ON CONFLICT (course_id, user_id) DO UPDATE SET role = 'instructor'", (course_id, target["id"]))
        self.audit(user, "course.add_instructor", course_id, user=str(target["id"]))

    def remove_instructor(self, user: dict, course_id: str, target_id: str) -> None:
        """Turn an instructor back into a student member; the last instructor
        cannot be removed (the course would become unmanageable)."""
        self.require_instructor(user, course_id)
        target_id = _uuid(target_id)
        with self.db.tx() as c:
            n = c.execute("SELECT count(*) AS n FROM app_memberships WHERE course_id = %s AND role = 'instructor'", (course_id,)).fetchone()["n"]
            row = c.execute("SELECT role FROM app_memberships WHERE course_id = %s AND user_id = %s", (course_id, target_id)).fetchone()
            if not row or row["role"] != "instructor":
                raise NotFound("instructor not found")
            if n <= 1:
                raise Invalid("cannot remove the last instructor")
            c.execute("UPDATE app_memberships SET role = 'student' WHERE course_id = %s AND user_id = %s", (course_id, target_id))
        self.audit(user, "course.remove_instructor", course_id, user=target_id)

    def delete_course(self, user: dict, course_id: str) -> None:
        """Idempotent and resumable: the course is marked 'deleting' first
        (it disappears for everyone), then its documents are removed from the
        retrieval engine one by one, then the course row (cascading to all
        app data). A crash in between leaves a 'deleting' course that a
        retried call finishes."""
        course_id = _uuid(course_id)
        c = self.db.one("SELECT owner_id, state FROM app_courses WHERE id = %s", (course_id,))
        if not c:
            raise NotFound("course not found")
        if user["role"] != "admin":
            if c["state"] != "active" and str(c["owner_id"]) != user["id"]:
                raise NotFound("course not found")
            if c["state"] == "active":
                self.require_instructor(user, course_id)
        self.db.run("UPDATE app_courses SET state = 'deleting' WHERE id = %s", (course_id,))
        with self.engine.lock:
            docs = self.engine.store.list_documents(tenant(course_id))
        for d in docs:
            self.engine.delete(tenant(course_id), d["id"])
        self.db.run("DELETE FROM app_courses WHERE id = %s", (course_id,))
        self.engine.tenants.pop(tenant(course_id), None)
        self.audit(user, "course.delete", course_id, documents=len(docs))

    # ---------------------------------------------------------------- documents

    def upload(self, user: dict, course_id: str, filename: str, data: bytes) -> dict:
        self.require_instructor(user, course_id)
        # Check-ingest-record is serialized per course so concurrent uploads
        # cannot each pass the storage check (single process; see docs).
        with self._course_lock(course_id):
            return self._upload_locked(user, course_id, filename, data)

    def _upload_locked(self, user: dict, course_id: str, filename: str, data: bytes) -> dict:
        quota = self.db.one("SELECT storage_quota_bytes AS q, (SELECT coalesce(sum(bytes),0) FROM app_documents WHERE course_id = %s) AS used "
                            "FROM app_courses WHERE id = %s", (course_id, course_id))
        if quota["used"] + len(data) > quota["q"]:
            raise QuotaExceeded(f"course storage quota exceeded ({quota['used']} of {quota['q']} bytes used)")
        try:
            info = self.engine.ingest(tenant(course_id), filename, data)
        except ParseError as e:
            raise Invalid(str(e))
        except ValueError as e:
            raise Invalid(str(e))
        if info["created"]:
            corpus = self.engine.load_tenant(tenant(course_id)).corpus
            cards = study.flashcards_for(corpus, info["id"])
            with self.db.tx() as c:
                c.execute("INSERT INTO app_documents (id, course_id, uploaded_by, name, bytes, pages) VALUES (%s,%s,%s,%s,%s,%s) "
                          "ON CONFLICT (id) DO NOTHING", (info["id"], course_id, user["id"], info["name"], len(data), info["pages"]))
                for card in cards:
                    c.execute("INSERT INTO app_flashcards (id, course_id, document_id, term, definition, page, section) "
                              "VALUES (%s,%s,%s,%s,%s,%s,%s) ON CONFLICT (course_id, term) DO NOTHING",
                              (str(uuid.uuid4()), course_id, info["id"], card["term"], card["definition"], card["page"], card["section"]))
            info["flashcards"] = len(cards)
            self.audit(user, "document.upload", info["id"], course=course_id, bytes=len(data))
        return info

    def documents(self, user: dict, course_id: str) -> list[dict]:
        self.course_role(user, course_id)
        rows = self.db.all("SELECT id, name, bytes, pages, created_at FROM app_documents WHERE course_id = %s ORDER BY created_at", (course_id,))
        return [{**r, "id": str(r["id"]), "created_at": r["created_at"].isoformat()} for r in rows]

    def delete_document(self, user: dict, course_id: str, doc_id: str) -> None:
        self.require_instructor(user, course_id)
        doc_id = _uuid(doc_id)
        if not self.db.one("SELECT 1 FROM app_documents WHERE id = %s AND course_id = %s", (doc_id, course_id)):
            raise NotFound("document not found")
        import json

        self.engine.delete(tenant(course_id), doc_id)
        with self.db.tx() as c:
            # Quizzes store copies of definition sentences: delete those that
            # quote the removed document so its content does not survive.
            c.execute("DELETE FROM app_quizzes WHERE course_id = %s AND questions @> %s::jsonb",
                      (course_id, json.dumps([{"source": {"document_id": doc_id}}])))
            c.execute("DELETE FROM app_documents WHERE id = %s", (doc_id,))
        self.audit(user, "document.delete", doc_id, course=course_id)

    # ---------------------------------------------------------------- asking

    def _reserve_question(self, user: dict, course_id: str, mode: str, query: str) -> str:
        """Atomically check the daily quota and record the question BEFORE the
        (slow) retrieval, holding a row lock on the user so concurrent
        requests are counted one after another."""
        qid = str(uuid.uuid4())
        with self.db.tx() as c:
            u = c.execute("SELECT role, daily_question_quota FROM app_users WHERE id = %s FOR UPDATE", (user["id"],)).fetchone()
            if u["role"] != "admin":
                n = c.execute("SELECT count(*) AS n FROM app_questions WHERE user_id = %s AND created_at >= date_trunc('day', now())",
                              (user["id"],)).fetchone()["n"]
                if n >= u["daily_question_quota"]:
                    raise QuotaExceeded(f"daily question quota of {u['daily_question_quota']} reached")
            c.execute("INSERT INTO app_questions (id, course_id, user_id, mode, query, abstained, latency_ms) VALUES (%s,%s,%s,%s,%s,NULL,0)",
                      (qid, course_id, user["id"], mode, query))
        return qid

    def _complete_question(self, qid: str, abstained, latency_ms: float, docs: list[str]) -> None:
        self.db.run("UPDATE app_questions SET abstained = %s, latency_ms = %s, cited_documents = %s WHERE id = %s",
                    (abstained, latency_ms, docs, qid))

    def ask(self, user: dict, course_id: str, query: str, doc_ids: list[str] | None = None) -> dict:
        self.course_role(user, course_id)
        query = query.strip()
        if not 1 <= len(query) <= 1000:
            raise Invalid("question must be 1-1000 characters")
        ids = [_uuid(d) for d in doc_ids] if doc_ids else None
        qid = self._reserve_question(user, course_id, "ask", query)
        t0 = time.perf_counter()
        try:
            res = self.engine.query(tenant(course_id), query, k=5, generate=True, doc_ids=ids)
        except Exception:
            self.db.run("DELETE FROM app_questions WHERE id = %s", (qid,))
            raise
        cited = sorted({s["doc_id"] for s in res.get("sources", [])})
        self._complete_question(qid, res.get("abstained"), (time.perf_counter() - t0) * 1e3, cited)
        res["question_id"] = qid
        return res

    def research(self, user: dict, course_id: str, query: str) -> dict:
        self.course_role(user, course_id)
        query = query.strip()
        if not 1 <= len(query) <= 1000:
            raise Invalid("question must be 1-1000 characters")
        qid = self._reserve_question(user, course_id, "research", query)
        t0 = time.perf_counter()
        try:
            with self.engine.lock:
                corpus = self.engine.load_tenant(tenant(course_id)).corpus
                out = study.research(corpus, query)
        except Exception:
            self.db.run("DELETE FROM app_questions WHERE id = %s", (qid,))
            raise
        self._complete_question(qid, not out["documents"], (time.perf_counter() - t0) * 1e3, [d["document_id"] for d in out["documents"]])
        out["question_id"] = qid
        return out

    def feedback(self, user: dict, question_id: str, rating: int, comment: str) -> None:
        q = self.db.one("SELECT user_id FROM app_questions WHERE id = %s", (_uuid(question_id),))
        if not q or str(q["user_id"]) != user["id"]:
            raise NotFound("question not found")
        if rating not in (-1, 1) or len(comment) > 2000:
            raise Invalid("rating must be -1 or 1; comment at most 2000 characters")
        self.db.run("INSERT INTO app_feedback (id, question_id, user_id, rating, comment) VALUES (%s,%s,%s,%s,%s) "
                    "ON CONFLICT (question_id, user_id) DO UPDATE SET rating = EXCLUDED.rating, comment = EXCLUDED.comment",
                    (str(uuid.uuid4()), question_id, user["id"], rating, comment))

    # ---------------------------------------------------------------- study

    def flashcards(self, user: dict, course_id: str) -> list[dict]:
        self.course_role(user, course_id)
        rows = self.db.all("SELECT f.id, f.term, f.definition, f.page, f.section, d.name AS document, f.document_id "
                           "FROM app_flashcards f JOIN app_documents d ON d.id = f.document_id WHERE f.course_id = %s ORDER BY f.term",
                           (course_id,))
        return [{**r, "id": str(r["id"]), "document_id": str(r["document_id"])} for r in rows]

    def create_quiz(self, user: dict, course_id: str, title: str, n: int = 5) -> dict:
        self.course_role(user, course_id)
        if not 1 <= n <= 20 or not 1 <= len(title.strip()) <= 200:
            raise Invalid("quiz needs a title and 1-20 questions")
        cards = self.flashcards(user, course_id)
        seed = secrets.randbits(32)
        try:
            qs = study.make_quiz(cards, n, seed)
        except ValueError as e:
            raise Invalid(str(e))
        qid = str(uuid.uuid4())
        import json

        official = self.course_role(user, course_id) == "instructor" and user["role"] in ("faculty", "admin")
        self.db.run("INSERT INTO app_quizzes (id, course_id, created_by, title, official, questions) VALUES (%s,%s,%s,%s,%s,%s)",
                    (qid, course_id, user["id"], title.strip(), official, json.dumps(qs)))
        return {"id": qid, "title": title.strip(), "questions": len(qs)}

    def _visible_quiz(self, user: dict, quiz_id: str) -> dict:
        q = self.db.one("SELECT * FROM app_quizzes WHERE id = %s", (_uuid(quiz_id),))
        if not q:
            raise NotFound("quiz not found")
        role = self.course_role(user, str(q["course_id"]))
        if not (role == "instructor" or str(q["created_by"]) == user["id"] or q["official"]):
            raise NotFound("quiz not found")
        return q

    def quizzes(self, user: dict, course_id: str) -> list[dict]:
        self.course_role(user, course_id)
        rows = self.db.all("SELECT q.id, q.title, q.created_by, jsonb_array_length(q.questions) AS n, q.created_at, q.official "
                           "FROM app_quizzes q WHERE q.course_id = %s ORDER BY q.created_at DESC", (course_id,))
        role = self.course_role(user, course_id)
        return [{"id": str(r["id"]), "title": r["title"], "questions": r["n"], "official": r["official"], "created_at": r["created_at"].isoformat()}
                for r in rows if role == "instructor" or r["official"] or str(r["created_by"]) == user["id"]]

    def quiz(self, user: dict, quiz_id: str) -> dict:
        """Questions WITHOUT answers (answers are only revealed after an attempt)."""
        q = self._visible_quiz(user, quiz_id)
        return {"id": str(q["id"]), "title": q["title"],
                "questions": [{"prompt": x["prompt"], "options": x["options"]} for x in q["questions"]]}

    def attempt(self, user: dict, quiz_id: str, answers: list[int]) -> dict:
        q = self._visible_quiz(user, quiz_id)
        try:
            score, correct = study.grade(q["questions"], answers)
        except ValueError as e:
            raise Invalid(str(e))
        import json

        self.db.run("INSERT INTO app_quiz_attempts (id, quiz_id, user_id, answers, score, total) VALUES (%s,%s,%s,%s,%s,%s)",
                    (str(uuid.uuid4()), q["id"], user["id"], json.dumps(answers), score, len(correct)))
        return {"score": score, "total": len(correct),
                "results": [{"correct": ok, "answer_index": x["answer_index"], "source": x["source"]} for ok, x in zip(correct, q["questions"])]}

    def compare(self, user: dict, course_id: str, doc_a: str, doc_b: str) -> dict:
        self.course_role(user, course_id)
        a, b = _uuid(doc_a), _uuid(doc_b)
        with self.engine.lock:  # copy only; the O(n*m) work runs unlocked
            corpus = self.engine.load_tenant(tenant(course_id)).corpus
            try:
                snap = study.snapshot_for_compare(corpus, a, b)
            except ValueError as e:
                raise NotFound(str(e))
        return study.compare(snap)

    # ---------------------------------------------------------------- analytics

    def analytics(self, user: dict, course_id: str) -> dict:
        """Aggregates only, each subject to k-anonymity (k = MIN_ANALYTICS_USERS):
          * nothing but the total question count is shown until at least k
            distinct eligible students have asked;
          * a topic term, a day, or a document is listed only if at least k
            distinct eligible students contributed to it;
          * feedback is summarised only with at least k distinct raters.
        Eligible = a member for at least ANALYTICS_MIN_MEMBERSHIP_AGE before
        asking, so freshly created accounts cannot be used to push a single
        student's topic over the threshold instantly. Instructors' own
        questions are excluded. Individual questions are never shown."""
        self.require_instructor(user, course_id)
        k = self.MIN_ANALYTICS_USERS
        elig = ("FROM app_questions q JOIN app_memberships m ON m.course_id = q.course_id AND m.user_id = q.user_id "
                "WHERE q.course_id = %s AND m.role = 'student' AND m.created_at <= q.created_at - %s::interval")
        p = (course_id, self.ANALYTICS_MIN_MEMBERSHIP_AGE)
        total = self.db.one("SELECT count(*) AS n FROM app_questions WHERE course_id = %s", (course_id,))["n"]
        tot = self.db.one("SELECT count(DISTINCT q.user_id) AS askers, avg(CASE WHEN q.abstained THEN 1.0 ELSE 0.0 END) AS ab, "
                          "percentile_cont(0.95) WITHIN GROUP (ORDER BY q.latency_ms) AS p95 " + elig, p)
        out = {"questions": total, "eligible_askers_at_least_k": tot["askers"] >= k, "k": k,
               "abstention_rate": None, "latency_p95_ms": None, "feedback": None, "questions_per_day": [], "topics": [],
               "most_cited_documents": [], "quiz_attempts": None, "topic_min_askers": k}
        if tot["askers"] < k:
            return out
        out["distinct_askers"] = tot["askers"]
        out["abstention_rate"] = None if tot["ab"] is None else round(float(tot["ab"]), 4)
        out["latency_p95_ms"] = None if tot["p95"] is None else round(float(tot["p95"]), 2)
        fb = self.db.one("SELECT count(DISTINCT f.user_id) AS raters, count(*) AS n, avg(f.rating) AS avg FROM app_feedback f "
                         "JOIN app_questions q ON q.id = f.question_id WHERE q.course_id = %s", (course_id,))
        if fb["raters"] >= k:
            out["feedback"] = {"count": fb["n"], "avg_rating": round(float(fb["avg"]), 3)}
        out["questions_per_day"] = [{"day": r["day"].isoformat(), "count": r["n"]} for r in self.db.all(
            "SELECT date_trunc('day', q.created_at)::date AS day, count(*) AS n " + elig +
            " GROUP BY 1 HAVING count(DISTINCT q.user_id) >= %s ORDER BY 1 DESC LIMIT 30", p + (k,))]
        rows = self.db.all("SELECT q.user_id, q.query " + elig + " AND q.created_at > now() - interval '30 days'", p)
        askers: dict[str, set] = {}
        for r in rows:
            for t in set(tokenize_display(r["query"])):
                askers.setdefault(t, set()).add(str(r["user_id"]))
        out["topics"] = [{"term": t, "askers": n} for t, n in sorted(((t, len(u)) for t, u in askers.items() if len(u) >= k),
                                                                     key=lambda x: (-x[1], x[0]))[:20]]
        out["most_cited_documents"] = [{"document": r["name"], "citations": r["n"]} for r in self.db.all(
            "SELECT d.name, count(*) AS n FROM (SELECT q.user_id, unnest(q.cited_documents) AS doc_id " + elig + ") x "
            "JOIN app_documents d ON d.id = x.doc_id GROUP BY d.name HAVING count(DISTINCT x.user_id) >= %s ORDER BY n DESC LIMIT 10", p + (k,))]
        att = self.db.one("SELECT count(DISTINCT a.user_id) AS users, count(*) AS n, avg(a.score::float / nullif(a.total, 0)) AS avg "
                          "FROM app_quiz_attempts a JOIN app_quizzes q ON q.id = a.quiz_id WHERE q.course_id = %s", (course_id,))
        if att["users"] >= k:
            out["quiz_attempts"] = {"count": att["n"], "avg_score": round(float(att["avg"]), 3)}
        return out

    # ---------------------------------------------------------------- accounts / admin

    def delete_account(self, user: dict) -> None:
        """Deletes the user, sessions, memberships, quiz attempts and feedback
        (including comments). Question rows are kept only as anonymous counts
        for course statistics: user id removed AND question text erased.
        Refused while the user is the last instructor of an active course
        (delete the course or add another instructor first)."""
        with self.db.tx() as c:
            last = c.execute(
                "SELECT c.code FROM app_courses c JOIN app_memberships m ON m.course_id = c.id AND m.user_id = %s AND m.role = 'instructor' "
                "WHERE c.state = 'active' AND (SELECT count(*) FROM app_memberships x WHERE x.course_id = c.id AND x.role = 'instructor') = 1",
                (user["id"],)).fetchall()
            if last:
                raise Invalid("you are the last instructor of: " + ", ".join(r["code"] for r in last) +
                              "; add another instructor or delete the course first")
            c.execute("DELETE FROM app_feedback WHERE user_id = %s", (user["id"],))
            c.execute("UPDATE app_questions SET query = '' WHERE user_id = %s", (user["id"],))
            c.execute("DELETE FROM app_users WHERE id = %s", (user["id"],))
        self.audit(None, "account.delete", user["id"])

    def admin_users(self, admin: dict) -> list[dict]:
        if admin["role"] != "admin":
            raise Forbidden("admins only")
        rows = self.db.all("SELECT id, email, name, role, disabled, created_at FROM app_users ORDER BY created_at")
        return [{**r, "id": str(r["id"]), "created_at": r["created_at"].isoformat()} for r in rows]

    def admin_update_user(self, admin: dict, user_id: str, role: str | None, disabled: bool | None, quota: int | None) -> None:
        if admin["role"] != "admin":
            raise Forbidden("admins only")
        user_id = _uuid(user_id)
        if user_id == admin["id"] and (disabled or (role and role != "admin")):
            raise Invalid("admins cannot demote or disable themselves")
        if role is not None and role not in ("admin", "faculty", "student"):
            raise Invalid("invalid role")
        if quota is not None and not 0 <= quota <= 100000:
            raise Invalid("invalid quota")
        with self.db.tx() as c:
            if not c.execute("SELECT 1 FROM app_users WHERE id = %s", (user_id,)).fetchone():
                raise NotFound("user not found")
            if role is not None:
                c.execute("UPDATE app_users SET role = %s WHERE id = %s", (role, user_id))
                if role == "student":
                    # Demotion below faculty also ends course instructor roles.
                    c.execute("UPDATE app_memberships SET role = 'student' WHERE user_id = %s AND role = 'instructor'", (user_id,))
            if quota is not None:
                c.execute("UPDATE app_users SET daily_question_quota = %s WHERE id = %s", (quota, user_id))
            if disabled is not None:
                c.execute("UPDATE app_users SET disabled = %s WHERE id = %s", (disabled, user_id))
                if disabled:
                    c.execute("DELETE FROM app_sessions WHERE user_id = %s", (user_id,))
        self.audit(admin, "admin.update_user", user_id, role=role, disabled=disabled, quota=quota)

    def admin_stats(self, admin: dict) -> dict:
        if admin["role"] != "admin":
            raise Forbidden("admins only")
        r = self.db.one("SELECT (SELECT count(*) FROM app_users) AS users, (SELECT count(*) FROM app_courses) AS courses, "
                        "(SELECT count(*) FROM app_documents) AS documents, (SELECT coalesce(sum(bytes),0) FROM app_documents) AS bytes, "
                        "(SELECT count(*) FROM app_questions WHERE created_at > now() - interval '1 day') AS questions_24h")
        courses = self.db.all("SELECT id, code, title, state, created_at FROM app_courses ORDER BY created_at")
        return {**r, "course_list": [{**c, "id": str(c["id"]), "created_at": c["created_at"].isoformat()} for c in courses]}


_WORD = re.compile(r"[a-z][a-z0-9\-]{2,}")


def tokenize_display(text: str) -> list[str]:
    from groundwork.text import STOPWORDS

    return [w for w in _WORD.findall(text.lower()) if w not in STOPWORDS and w not in {"what", "how", "why", "does", "explain", "difference"}]
