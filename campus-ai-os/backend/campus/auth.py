"""Accounts and sessions.

* Passwords: scrypt (n=2^15, r=8, p=1, 16-byte salt), encoded as
  `scrypt$n$r$p$salt$hash`; verification is constant time.
* Sessions: 32 random bytes in an HttpOnly, SameSite=Lax cookie; only the
  SHA-256 of the token is stored, so a database leak does not leak sessions.
  Sessions expire after 7 days and are deleted on logout, password change,
  account disable and account deletion.
* CSRF: state-changing requests must carry an Origin (or Referer) header
  matching the allowed origins, in addition to SameSite cookies.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import re
import secrets
import uuid
from datetime import datetime, timedelta, timezone

from .db import Db

SESSION_COOKIE = "campus_session"
SESSION_DAYS = 7
EMAIL_RE = re.compile(r"^[^@\s]{1,64}@[^@\s]{1,190}\.[a-z]{2,24}$", re.I)
_N, _R, _P = 2**15, 8, 1


class AuthError(Exception):
    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status


def hash_password(pw: str) -> str:
    salt = secrets.token_bytes(16)
    h = hashlib.scrypt(pw.encode(), salt=salt, n=_N, r=_R, p=_P, maxmem=64 * 1024 * 1024, dklen=32)
    b = lambda x: base64.b64encode(x).decode()  # noqa: E731
    return f"scrypt${_N}${_R}${_P}${b(salt)}${b(h)}"


def verify_password(pw: str, encoded: str) -> bool:
    try:
        _, n, r, p, salt, h = encoded.split("$")
        got = hashlib.scrypt(pw.encode(), salt=base64.b64decode(salt), n=int(n), r=int(r), p=int(p),
                             maxmem=64 * 1024 * 1024, dklen=32)
        return hmac.compare_digest(got, base64.b64decode(h))
    except (ValueError, TypeError):
        return False


# A valid hash to verify against when the user does not exist, so login
# timing does not reveal which emails are registered.
_DUMMY = hash_password("not-a-real-password")


def validate_password(pw: str) -> None:
    if len(pw) < 10 or len(pw) > 200:
        raise AuthError(422, "password must be 10-200 characters")


def register(db: Db, email: str, name: str, password: str, role: str = "student") -> dict:
    email = email.strip().lower()
    name = name.strip()
    if not EMAIL_RE.match(email):
        raise AuthError(422, "invalid email")
    if not 1 <= len(name) <= 100:
        raise AuthError(422, "name must be 1-100 characters")
    validate_password(password)
    if role not in ("student", "faculty", "admin"):
        raise AuthError(422, "invalid role")
    uid = str(uuid.uuid4())
    with db.tx() as c:
        if c.execute("SELECT 1 FROM app_users WHERE email = %s", (email,)).fetchone():
            raise AuthError(409, "email already registered")
        c.execute("INSERT INTO app_users (id, email, name, password_hash, role) VALUES (%s,%s,%s,%s,%s)",
                  (uid, email, name, hash_password(password), role))
    return {"id": uid, "email": email, "name": name, "role": role}


def login(db: Db, email: str, password: str) -> tuple[str, dict]:
    u = db.one("SELECT * FROM app_users WHERE email = %s", (email.strip().lower(),))
    ok = verify_password(password, u["password_hash"] if u else _DUMMY)
    if not u or not ok:
        raise AuthError(401, "invalid email or password")
    if u["disabled"]:
        raise AuthError(403, "account disabled")
    token = secrets.token_urlsafe(32)
    db.run("INSERT INTO app_sessions (token_hash, user_id, expires_at) VALUES (%s,%s,%s)",
           (hashlib.sha256(token.encode()).digest(), u["id"], datetime.now(timezone.utc) + timedelta(days=SESSION_DAYS)))
    return token, public_user(u)


def public_user(u: dict) -> dict:
    return {"id": str(u["id"]), "email": u["email"], "name": u["name"], "role": u["role"]}


def session_user(db: Db, token: str | None) -> dict | None:
    if not token or len(token) > 100:
        return None
    return db.one(
        "SELECT u.* FROM app_sessions s JOIN app_users u ON u.id = s.user_id "
        "WHERE s.token_hash = %s AND s.expires_at > now() AND NOT u.disabled",
        (hashlib.sha256(token.encode()).digest(),))


def logout(db: Db, token: str | None) -> None:
    if token:
        db.run("DELETE FROM app_sessions WHERE token_hash = %s", (hashlib.sha256(token.encode()).digest(),))


def change_password(db: Db, user_id: str, old: str, new: str) -> None:
    u = db.one("SELECT password_hash FROM app_users WHERE id = %s", (user_id,))
    if not u or not verify_password(old, u["password_hash"]):
        raise AuthError(403, "current password is wrong")
    validate_password(new)
    with db.tx() as c:
        c.execute("UPDATE app_users SET password_hash = %s WHERE id = %s", (hash_password(new), user_id))
        c.execute("DELETE FROM app_sessions WHERE user_id = %s", (user_id,))


def origin_allowed(origin: str | None, referer: str | None, allowed: list[str]) -> bool:
    src = origin or (re.match(r"^(https?://[^/]+)", referer or "") or [None])[0]
    return bool(src) and src in allowed
