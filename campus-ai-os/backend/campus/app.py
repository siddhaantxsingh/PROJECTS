"""HTTP API + static frontend (single origin, cookie sessions).

Configuration (environment):
  CAMPUS_DATABASE_URL        PostgreSQL with pgvector (required)
  CAMPUS_ALLOWED_ORIGINS     comma-separated origins allowed for state-changing requests
  CAMPUS_BOOTSTRAP_ADMIN     "email:password" — creates the first admin if none exists
  CAMPUS_STATIC_DIR          built frontend (frontend/dist) to serve at /
  CAMPUS_SECURE_COOKIES      "1" behind HTTPS (sets the Secure flag)
  CAMPUS_LOGIN_PER_MIN       login attempts per client IP per minute (default 10)
"""
from __future__ import annotations

import os
from pathlib import Path

from fastapi import Depends, FastAPI, File, HTTPException, Request, Response, UploadFile
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field

from groundwork.cache import RateLimiter
from groundwork.chunking import ChunkSpec
from groundwork.engine import Engine, EngineConfig
from groundwork.ingest import MAX_DOCUMENT_BYTES
from groundwork.store import Store, connect, migrate

from . import auth
from .db import Db
from .service import Campus, Forbidden, Invalid, NotFound, QuotaExceeded


class Creds(BaseModel):
    email: str = Field(max_length=254)
    password: str = Field(max_length=200)


class Registration(Creds):
    name: str = Field(max_length=100)


class CourseIn(BaseModel):
    code: str = Field(max_length=20)
    title: str = Field(max_length=200)


class JoinIn(BaseModel):
    join_code: str = Field(max_length=40)


class AskIn(BaseModel):
    query: str = Field(min_length=1, max_length=1000)
    doc_ids: list[str] | None = Field(default=None, max_length=50)


class FeedbackIn(BaseModel):
    rating: int
    comment: str = Field(default="", max_length=2000)


class QuizIn(BaseModel):
    title: str = Field(max_length=200)
    questions: int = Field(default=5, ge=1, le=20)


class AttemptIn(BaseModel):
    answers: list[int] = Field(max_length=20)


class CompareIn(BaseModel):
    doc_a: str
    doc_b: str


class EmailIn(BaseModel):
    email: str = Field(max_length=254)


class PasswordChange(BaseModel):
    old_password: str = Field(max_length=200)
    new_password: str = Field(max_length=200)


class DeleteAccount(BaseModel):
    password: str = Field(max_length=200)


class AdminUserIn(BaseModel):
    role: str | None = None
    disabled: bool | None = None
    daily_question_quota: int | None = None


def build(db_url: str, dim: int = 256) -> tuple[Db, Engine, Campus]:
    db = Db(db_url)
    db.migrate()
    gconn = connect(db_url)
    migrate(gconn, dim)
    engine = Engine(Store(gconn), EngineConfig(dim=dim, chunking=ChunkSpec("structure", 200, 0)))
    return db, engine, Campus(db, engine)


def create_app(campus: Campus | None = None, allowed_origins: list[str] | None = None, static_dir: str | None = None,
               secure_cookies: bool | None = None, login_per_min: int | None = None) -> FastAPI:
    if campus is None:
        _, _, campus = build(os.environ["CAMPUS_DATABASE_URL"], int(os.environ.get("CAMPUS_DIM", "256")))
    db = campus.db
    origins = allowed_origins if allowed_origins is not None else [o.strip() for o in os.environ.get("CAMPUS_ALLOWED_ORIGINS", "").split(",") if o.strip()]
    static_dir = static_dir if static_dir is not None else os.environ.get("CAMPUS_STATIC_DIR")
    secure = secure_cookies if secure_cookies is not None else os.environ.get("CAMPUS_SECURE_COOKIES") == "1"
    login_limiter = RateLimiter(login_per_min if login_per_min is not None else int(os.environ.get("CAMPUS_LOGIN_PER_MIN", "10")))
    # Every endpoint that runs the (deliberately slow) password hash is limited.
    register_limiter = RateLimiter(login_limiter.per_minute)
    password_limiter = RateLimiter(max(1, login_limiter.per_minute // 2))
    boot = os.environ.get("CAMPUS_BOOTSTRAP_ADMIN")
    if boot and not db.one("SELECT 1 FROM app_users WHERE role = 'admin'"):
        email, _, pw = boot.partition(":")
        auth.register(db, email, "Administrator", pw, role="admin")

    app = FastAPI(title="Campus AI OS", version="0.1.0")

    @app.exception_handler(auth.AuthError)
    async def _auth_err(_, e: auth.AuthError):
        return JSONResponse({"detail": str(e)}, status_code=e.status)

    for exc, code in ((Forbidden, 403), (NotFound, 404), (QuotaExceeded, 429), (Invalid, 422)):
        def handler(_, e, code=code):
            return JSONResponse({"detail": str(e)}, status_code=code)
        app.add_exception_handler(exc, handler)

    @app.middleware("http")
    async def csrf_and_headers(request: Request, call_next):
        if request.method not in ("GET", "HEAD", "OPTIONS") and request.url.path.startswith("/api/"):
            if not auth.origin_allowed(request.headers.get("origin"), request.headers.get("referer"), origins):
                return JSONResponse({"detail": "cross-origin request rejected"}, status_code=403)
        resp = await call_next(request)
        resp.headers["X-Content-Type-Options"] = "nosniff"
        resp.headers["X-Frame-Options"] = "DENY"
        resp.headers["Referrer-Policy"] = "same-origin"
        resp.headers["Content-Security-Policy"] = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'"
        return resp

    def current(request: Request) -> dict:
        u = auth.session_user(db, request.cookies.get(auth.SESSION_COOKIE))
        if not u:
            raise HTTPException(401, "not logged in")
        u = dict(u)
        u["id"] = str(u["id"])
        return u

    # ------------------------------------------------------------ auth

    @app.post("/api/auth/register", status_code=201)
    def register(body: Registration, request: Request):
        if not register_limiter.allow(f"register:{request.client.host if request.client else '?'}"):
            raise HTTPException(429, "too many registrations")
        return auth.register(db, body.email, body.name, body.password)  # self-registration: students only

    @app.post("/api/auth/login")
    def login(body: Creds, request: Request, response: Response):
        ip = request.client.host if request.client else "?"
        if not login_limiter.allow(f"login:{ip}"):
            raise HTTPException(429, "too many login attempts")
        token, user = auth.login(db, body.email, body.password)
        response.set_cookie(auth.SESSION_COOKIE, token, httponly=True, samesite="lax", secure=secure,
                            max_age=auth.SESSION_DAYS * 86400, path="/")
        return user

    @app.post("/api/auth/logout", status_code=204)
    def logout(request: Request, response: Response):
        auth.logout(db, request.cookies.get(auth.SESSION_COOKIE))
        response.delete_cookie(auth.SESSION_COOKIE, path="/")

    @app.get("/api/me")
    def me(user=Depends(current)):
        return auth.public_user(user)

    @app.post("/api/me/password", status_code=204)
    def password(body: PasswordChange, user=Depends(current)):
        if not password_limiter.allow(f"pw:{user['id']}"):
            raise HTTPException(429, "too many attempts")
        auth.change_password(db, user["id"], body.old_password, body.new_password)

    @app.post("/api/me/delete", status_code=204)
    def delete_me(body: DeleteAccount, response: Response, user=Depends(current)):
        if not password_limiter.allow(f"pw:{user['id']}"):
            raise HTTPException(429, "too many attempts")
        if not auth.verify_password(body.password, user["password_hash"]):
            raise HTTPException(403, "password is wrong")
        campus.delete_account(user)
        response.delete_cookie(auth.SESSION_COOKIE, path="/")

    # ------------------------------------------------------------ courses

    @app.get("/api/courses")
    def courses(user=Depends(current)):
        return campus.my_courses(user)

    @app.post("/api/courses", status_code=201)
    def create_course(body: CourseIn, user=Depends(current)):
        return campus.create_course(user, body.code, body.title)

    @app.post("/api/courses/join")
    def join(body: JoinIn, user=Depends(current)):
        return campus.join_course(user, body.join_code)

    @app.delete("/api/courses/{cid}", status_code=204)
    def delete_course(cid: str, user=Depends(current)):
        campus.delete_course(user, cid)

    @app.post("/api/courses/{cid}/instructors", status_code=204)
    def add_instructor(cid: str, body: EmailIn, user=Depends(current)):
        campus.add_instructor(user, cid, body.email)

    @app.delete("/api/courses/{cid}/instructors/{uid}", status_code=204)
    def remove_instructor(cid: str, uid: str, user=Depends(current)):
        campus.remove_instructor(user, cid, uid)

    @app.get("/api/courses/{cid}/documents")
    def docs(cid: str, user=Depends(current)):
        return campus.documents(user, cid)

    @app.post("/api/courses/{cid}/documents", status_code=201)
    async def upload(cid: str, file: UploadFile = File(...), user=Depends(current)):
        data = await file.read(MAX_DOCUMENT_BYTES + 1)
        if len(data) > MAX_DOCUMENT_BYTES:
            raise HTTPException(413, "document too large")
        return campus.upload(user, cid, file.filename or "upload", data)

    @app.delete("/api/courses/{cid}/documents/{did}", status_code=204)
    def delete_doc(cid: str, did: str, user=Depends(current)):
        campus.delete_document(user, cid, did)

    @app.post("/api/courses/{cid}/ask")
    def ask(cid: str, body: AskIn, user=Depends(current)):
        return campus.ask(user, cid, body.query, body.doc_ids)

    @app.post("/api/courses/{cid}/research")
    def research(cid: str, body: AskIn, user=Depends(current)):
        return campus.research(user, cid, body.query)

    @app.post("/api/questions/{qid}/feedback", status_code=204)
    def feedback(qid: str, body: FeedbackIn, user=Depends(current)):
        campus.feedback(user, qid, body.rating, body.comment)

    @app.get("/api/courses/{cid}/flashcards")
    def flashcards(cid: str, user=Depends(current)):
        return campus.flashcards(user, cid)

    @app.get("/api/courses/{cid}/quizzes")
    def quizzes(cid: str, user=Depends(current)):
        return campus.quizzes(user, cid)

    @app.post("/api/courses/{cid}/quizzes", status_code=201)
    def create_quiz(cid: str, body: QuizIn, user=Depends(current)):
        return campus.create_quiz(user, cid, body.title, body.questions)

    @app.get("/api/quizzes/{qid}")
    def quiz(qid: str, user=Depends(current)):
        return campus.quiz(user, qid)

    @app.post("/api/quizzes/{qid}/attempts")
    def attempt(qid: str, body: AttemptIn, user=Depends(current)):
        return campus.attempt(user, qid, body.answers)

    @app.post("/api/courses/{cid}/compare")
    def compare(cid: str, body: CompareIn, user=Depends(current)):
        return campus.compare(user, cid, body.doc_a, body.doc_b)

    @app.get("/api/courses/{cid}/analytics")
    def analytics(cid: str, user=Depends(current)):
        return campus.analytics(user, cid)

    # ------------------------------------------------------------ admin

    @app.get("/api/admin/users")
    def admin_users(user=Depends(current)):
        return campus.admin_users(user)

    @app.patch("/api/admin/users/{uid}", status_code=204)
    def admin_update(uid: str, body: AdminUserIn, user=Depends(current)):
        campus.admin_update_user(user, uid, body.role, body.disabled, body.daily_question_quota)

    @app.get("/api/admin/stats")
    def admin_stats(user=Depends(current)):
        return campus.admin_stats(user)

    @app.get("/healthz")
    def health():
        try:
            db.one("SELECT 1 AS ok")
            return {"status": "ok"}
        except Exception:
            return JSONResponse({"status": "database unavailable"}, status_code=503)

    if static_dir and Path(static_dir).is_dir():
        root = Path(static_dir).resolve()

        @app.get("/{path:path}")
        def spa(path: str):
            if path.startswith("api/"):
                raise HTTPException(404, "not found")
            f = (root / path).resolve()
            if path and f.is_file() and root in f.parents:
                return FileResponse(f)
            return FileResponse(root / "index.html")

    app.state.campus = campus
    return app
