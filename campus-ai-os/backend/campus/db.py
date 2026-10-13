"""Application schema (app_* tables) and a thread-safe connection wrapper.

The retrieval engine (Groundwork) keeps its own tables in the same database
(documents, chunks, ...). Each course is a Groundwork tenant named
"course:<course id>", so course isolation is enforced by the engine's tenant
scoping as well as by the application's membership checks.
"""
from __future__ import annotations

import contextlib
import threading

import psycopg
from psycopg.rows import dict_row

SCHEMA = """
CREATE TABLE IF NOT EXISTS app_users (
  id uuid PRIMARY KEY,
  email text NOT NULL UNIQUE,
  name text NOT NULL,
  password_hash text NOT NULL,
  role text NOT NULL CHECK (role IN ('admin', 'faculty', 'student')),
  disabled boolean NOT NULL DEFAULT false,
  daily_question_quota int NOT NULL DEFAULT 100,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS app_sessions (
  token_hash bytea PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS app_sessions_user ON app_sessions (user_id);
CREATE TABLE IF NOT EXISTS app_courses (
  id uuid PRIMARY KEY,
  code text NOT NULL,
  title text NOT NULL,
  owner_id uuid REFERENCES app_users(id) ON DELETE SET NULL,
  join_code text NOT NULL UNIQUE,
  storage_quota_bytes bigint NOT NULL DEFAULT 209715200,
  state text NOT NULL DEFAULT 'active' CHECK (state IN ('active', 'deleting')),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS app_memberships (
  course_id uuid NOT NULL REFERENCES app_courses(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
  role text NOT NULL CHECK (role IN ('instructor', 'student')),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (course_id, user_id)
);
CREATE TABLE IF NOT EXISTS app_documents (
  id uuid PRIMARY KEY,                -- = Groundwork document id
  course_id uuid NOT NULL REFERENCES app_courses(id) ON DELETE CASCADE,
  uploaded_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
  name text NOT NULL,
  bytes bigint NOT NULL,
  pages int NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS app_questions (
  id uuid PRIMARY KEY,
  course_id uuid NOT NULL REFERENCES app_courses(id) ON DELETE CASCADE,
  user_id uuid REFERENCES app_users(id) ON DELETE SET NULL,   -- anonymised on account deletion
  mode text NOT NULL,
  query text NOT NULL,
  abstained boolean,
  latency_ms double precision NOT NULL,
  cited_documents uuid[] NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS app_questions_user_day ON app_questions (user_id, created_at);
CREATE INDEX IF NOT EXISTS app_questions_course ON app_questions (course_id, created_at);
CREATE TABLE IF NOT EXISTS app_feedback (
  id uuid PRIMARY KEY,
  question_id uuid NOT NULL REFERENCES app_questions(id) ON DELETE CASCADE,
  user_id uuid REFERENCES app_users(id) ON DELETE SET NULL,
  rating smallint NOT NULL CHECK (rating IN (-1, 1)),
  comment text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (question_id, user_id)
);
CREATE TABLE IF NOT EXISTS app_flashcards (
  id uuid PRIMARY KEY,
  course_id uuid NOT NULL REFERENCES app_courses(id) ON DELETE CASCADE,
  document_id uuid NOT NULL REFERENCES app_documents(id) ON DELETE CASCADE,
  term text NOT NULL,
  definition text NOT NULL,
  page int NOT NULL,
  section text NOT NULL,
  UNIQUE (course_id, term)
);
CREATE TABLE IF NOT EXISTS app_quizzes (
  id uuid PRIMARY KEY,
  course_id uuid NOT NULL REFERENCES app_courses(id) ON DELETE CASCADE,
  created_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
  title text NOT NULL,
  official boolean NOT NULL DEFAULT false,  -- created by an instructor (fixed at creation)
  questions jsonb NOT NULL,           -- [{prompt, options, answer_index, source}]
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS app_quiz_attempts (
  id uuid PRIMARY KEY,
  quiz_id uuid NOT NULL REFERENCES app_quizzes(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
  answers jsonb NOT NULL,
  score int NOT NULL,
  total int NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS app_audit (
  id bigserial PRIMARY KEY,
  actor_id uuid REFERENCES app_users(id) ON DELETE SET NULL,
  action text NOT NULL,
  target text NOT NULL,
  detail jsonb NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT now()
);
"""


class Db:
    """One psycopg connection guarded by a re-entrant lock. `tx()` opens a
    transaction (nested calls become savepoints)."""

    def __init__(self, url: str) -> None:
        self.conn = psycopg.connect(url, autocommit=True, row_factory=dict_row)
        self.lock = threading.RLock()

    def migrate(self) -> None:
        with self.tx():
            self.conn.execute(SCHEMA)

    @contextlib.contextmanager
    def tx(self):
        with self.lock, self.conn.transaction():
            yield self.conn

    def one(self, sql: str, params=()) -> dict | None:
        with self.lock:
            return self.conn.execute(sql, params).fetchone()

    def all(self, sql: str, params=()) -> list[dict]:
        with self.lock:
            return self.conn.execute(sql, params).fetchall()

    def run(self, sql: str, params=()) -> int:
        with self.lock:
            return self.conn.execute(sql, params).rowcount
