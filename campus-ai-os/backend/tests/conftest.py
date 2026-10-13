import os
import uuid

import psycopg
import pytest
from fastapi.testclient import TestClient

ADMIN_URL = os.environ.get("CAMPUS_TEST_ADMIN_URL", "postgresql://postgres@127.0.0.1:5432/postgres")
ORIGIN = "http://testserver"

LECTURE = b"""# CS201 Data Structures

## Arrays

An array is a contiguous block of memory that stores elements of the same type.
Random access into an array takes constant time because the address is computed from the index.

## Linked lists

A linked list is a sequence of nodes where each node stores a value and a pointer to the next node.
Insertion at the head of a linked list takes constant time.

## Hashing

A hash table is a data structure that maps keys to values using a hash function.
Collisions occur when two keys hash to the same bucket. Separate chaining resolves collisions with per-bucket lists.

## Trees

A binary search tree is a tree in which every node's left subtree holds smaller keys and its right subtree larger keys.
A heap is a complete binary tree that satisfies the heap property between parents and children.
"""

SYLLABUS = b"""# CS201 Syllabus

## Grading

The final exam is worth 40 percent of the grade. Weekly quizzes are worth 20 percent.
Late homework loses ten percent per day.

## Hashing week

Week six covers hash tables, hash functions and collision resolution with chaining and open addressing.
"""


@pytest.fixture
def db_url():
    name = "campus_test_" + uuid.uuid4().hex[:10]
    with psycopg.connect(ADMIN_URL, autocommit=True) as c:
        c.execute(f"CREATE DATABASE {name}")
    yield ADMIN_URL.rsplit("/", 1)[0] + "/" + name
    with psycopg.connect(ADMIN_URL, autocommit=True) as c:
        c.execute(f"DROP DATABASE IF EXISTS {name} WITH (FORCE)")


class Client:
    """A browser-like client: own cookie jar, Origin header on every request."""

    def __init__(self, app):
        self.c = TestClient(app, headers={"Origin": ORIGIN})

    def __getattr__(self, k):
        return getattr(self.c, k)

    def register_login(self, email, name="User", pw="correct horse battery"):
        r = self.c.post("/api/auth/register", json={"email": email, "name": name, "password": pw})
        assert r.status_code == 201, r.text
        r = self.c.post("/api/auth/login", json={"email": email, "password": pw})
        assert r.status_code == 200, r.text
        return r.json()


@pytest.fixture
def world(db_url, monkeypatch):
    """admin, faculty (promoted by admin), two students, one course with two documents."""
    from campus.app import build, create_app

    monkeypatch.setenv("CAMPUS_BOOTSTRAP_ADMIN", "admin@uni.edu:admin-password-123")
    _, _, campus = build(db_url, dim=64)
    app = create_app(campus, allowed_origins=[ORIGIN], login_per_min=1000)
    admin = Client(app)
    assert admin.post("/api/auth/login", json={"email": "admin@uni.edu", "password": "admin-password-123"}).status_code == 200
    prof = Client(app)
    p = prof.register_login("prof@uni.edu", "Prof")
    assert admin.patch(f"/api/admin/users/{p['id']}", json={"role": "faculty"}).status_code == 204
    course = prof.post("/api/courses", json={"code": "CS201", "title": "Data Structures"}).json()
    d1 = prof.post(f"/api/courses/{course['id']}/documents", files={"file": ("lecture.md", LECTURE)}).json()
    d2 = prof.post(f"/api/courses/{course['id']}/documents", files={"file": ("syllabus.md", SYLLABUS)}).json()
    s1, s2 = Client(app), Client(app)
    u1 = s1.register_login("alice@uni.edu", "Alice")
    u2 = s2.register_login("bob@uni.edu", "Bob")
    assert s1.post("/api/courses/join", json={"join_code": course["join_code"]}).status_code == 200
    outsider = Client(app)
    outsider.register_login("eve@uni.edu", "Eve")
    return {"app": app, "campus": campus, "admin": admin, "prof": prof, "s1": s1, "s2": s2, "outsider": outsider,
            "course": course, "docs": [d1, d2], "users": {"prof": p, "alice": u1, "bob": u2}}
