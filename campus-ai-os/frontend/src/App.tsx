import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from "react";
import { api, ApiError, type Answer, type AttemptResult, type Card, type Comparison, type Course, type Doc, type Quiz, type QuizSummary, type Research, type User } from "./api";

function useAsync() {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const run = useCallback(async <T,>(fn: () => Promise<T>): Promise<T | undefined> => {
    setBusy(true);
    setError(null);
    try {
      return await fn();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
      return undefined;
    } finally {
      setBusy(false);
    }
  }, []);
  return { error, busy, run, setError };
}

function ErrorLine({ error }: { error: string | null }) {
  return error ? <p className="error" role="alert">{error}</p> : null;
}

// ------------------------------------------------------------------ auth

function Auth({ onLogin }: { onLogin: (u: User) => void }) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const { error, busy, run } = useAsync();
  const submit = (e: FormEvent) => {
    e.preventDefault();
    run(async () => {
      if (mode === "register") await api.register(email, name, password);
      onLogin(await api.login(email, password));
    });
  };
  return (
    <main className="auth">
      <h1>Campus AI OS</h1>
      <p className="muted">Answers from your course material, with page-level citations.</p>
      <form onSubmit={submit} className="card">
        <h2>{mode === "login" ? "Sign in" : "Create a student account"}</h2>
        <label>Email<input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="username" /></label>
        {mode === "register" && <label>Name<input value={name} onChange={(e) => setName(e.target.value)} required /></label>}
        <label>Password<input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required minLength={10}
          autoComplete={mode === "login" ? "current-password" : "new-password"} /></label>
        <button disabled={busy}>{mode === "login" ? "Sign in" : "Register"}</button>
        <ErrorLine error={error} />
        <button type="button" className="link" onClick={() => setMode(mode === "login" ? "register" : "login")}>
          {mode === "login" ? "New here? Create an account" : "Have an account? Sign in"}
        </button>
      </form>
    </main>
  );
}

// ------------------------------------------------------------------ dashboard

function Dashboard({ user, open }: { user: User; open: (c: Course) => void }) {
  const [courses, setCourses] = useState<Course[]>([]);
  const [code, setCode] = useState("");
  const [title, setTitle] = useState("");
  const [join, setJoin] = useState("");
  const { error, run } = useAsync();
  const load = useCallback(() => run(async () => setCourses(await api.courses())), [run]);
  useEffect(() => { load(); }, [load]);
  return (
    <section>
      <h2>My courses</h2>
      {courses.length === 0 && <p className="muted">No courses yet. Join one with a code from your instructor.</p>}
      <ul className="courses">
        {courses.map((c) => (
          <li key={c.id}><button className="course" onClick={() => open(c)}><strong>{c.code}</strong> {c.title}<span className="badge">{c.role}</span></button></li>
        ))}
      </ul>
      <form className="row" onSubmit={(e) => { e.preventDefault(); run(async () => { await api.join(join); setJoin(""); await load(); }); }}>
        <input placeholder="Join code" value={join} onChange={(e) => setJoin(e.target.value)} required aria-label="Join code" />
        <button>Join course</button>
      </form>
      {(user.role === "faculty" || user.role === "admin") && (
        <form className="row" onSubmit={(e) => { e.preventDefault(); run(async () => { await api.createCourse(code, title); setCode(""); setTitle(""); await load(); }); }}>
          <input placeholder="Code (e.g. CS201)" value={code} onChange={(e) => setCode(e.target.value)} required aria-label="Course code" />
          <input placeholder="Title" value={title} onChange={(e) => setTitle(e.target.value)} required aria-label="Course title" />
          <button>Create course</button>
        </form>
      )}
      <ErrorLine error={error} />
    </section>
  );
}

// ------------------------------------------------------------------ course tabs

function Citations({ sources }: { sources: Answer["sources"] }) {
  if (!sources.length) return null;
  return (
    <ol className="sources">
      {sources.map((s) => (
        <li key={s.n}>
          <strong>[{s.n}]</strong> {s.document} — p. {s.pages[0]}{s.pages[1] !== s.pages[0] ? `–${s.pages[1]}` : ""}
          {s.section && <span className="muted"> · {s.section.split(" > ").slice(-1)[0]}</span>}
          <blockquote>{s.excerpt}</blockquote>
        </li>
      ))}
    </ol>
  );
}

function providerLabel(a: Answer): string {
  if (!a.provider || a.provider === "extractive") return a.abstained ? "" : "Answer quoted from the course material.";
  const model = a.provider.replace(/^llamacpp:/, "").replace(/\+enforced$/, "");
  const dropped = a.usage?.dropped_sentences ?? 0;
  if (a.abstained) return `The local model (${model}) gave no answer that its cited sources support.`;
  const auto = a.usage?.auto_attributed_sentences ?? 0;
  return `Generated by a local model (${model}); every sentence was checked against its cited source` +
    (dropped ? `; ${dropped} unsupported sentence(s) were removed` : "") +
    (auto ? `; ${auto} citation(s) were added by the checker because the model left them out` : "") + ".";
}

function Ask({ course }: { course: Course }) {
  const [q, setQ] = useState("");
  const [a, setA] = useState<Answer | null>(null);
  const [rated, setRated] = useState(false);
  const { error, busy, run } = useAsync();
  return (
    <div>
      <form className="row" onSubmit={(e) => { e.preventDefault(); run(async () => { setRated(false); setA(await api.ask(course.id, q)); }); }}>
        <input className="grow" placeholder="Ask a question about the course material" value={q} onChange={(e) => setQ(e.target.value)} required aria-label="Question" />
        <button disabled={busy}>Ask</button>
      </form>
      <ErrorLine error={error} />
      {a && (
        <div className="card answer">
          <p data-testid="answer">{a.answer}</p>
          {!a.abstained && a.grounding.groundedness !== null && (
            <p className="muted">Grounded sentences: {Math.round(a.grounding.groundedness * 100)}% · {Math.round(a.timings_ms.request_ms ?? 0)} ms</p>
          )}
          <p className="muted" data-testid="provider">{providerLabel(a)}</p>
          <Citations sources={a.sources} />
          {a.excluded_sources.length > 0 && <p className="warn">{a.excluded_sources.length} source(s) were excluded because they contained instructions aimed at the assistant.</p>}
          {!rated ? (
            <div className="row">
              <span className="muted">Was this helpful?</span>
              <button className="small" onClick={() => run(async () => { await api.feedback(a.question_id, 1); setRated(true); })}>Yes</button>
              <button className="small" onClick={() => run(async () => { await api.feedback(a.question_id, -1); setRated(true); })}>No</button>
            </div>
          ) : <p className="muted">Thanks for the feedback.</p>}
        </div>
      )}
    </div>
  );
}

function ResearchTab({ course }: { course: Course }) {
  const [q, setQ] = useState("");
  const [r, setR] = useState<Research | null>(null);
  const { error, busy, run } = useAsync();
  return (
    <div>
      <form className="row" onSubmit={(e) => { e.preventDefault(); run(async () => setR(await api.research(course.id, q))); }}>
        <input className="grow" placeholder="Research a topic across all documents" value={q} onChange={(e) => setQ(e.target.value)} required aria-label="Research topic" />
        <button disabled={busy}>Research</button>
      </form>
      <ErrorLine error={error} />
      {r && (r.documents.length === 0 ? <p className="muted">No matching material.</p> : r.documents.map((d) => (
        <div className="card" key={d.document_id}>
          <h3>{d.document}</h3>
          <ul>{d.evidence.map((e, i) => <li key={i}>{e.sentence} <span className="muted">(p. {e.page}{e.section ? `, ${e.section.split(" > ").slice(-1)[0]}` : ""})</span></li>)}</ul>
        </div>
      )))}
    </div>
  );
}

function Study({ course }: { course: Course }) {
  const [cards, setCards] = useState<Card[]>([]);
  const [flipped, setFlipped] = useState<Record<string, boolean>>({});
  const [quizzes, setQuizzes] = useState<QuizSummary[]>([]);
  const [quiz, setQuiz] = useState<Quiz | null>(null);
  const [answers, setAnswers] = useState<number[]>([]);
  const [result, setResult] = useState<AttemptResult | null>(null);
  const { error, run } = useAsync();
  const load = useCallback(() => run(async () => { setCards(await api.cards(course.id)); setQuizzes(await api.quizzes(course.id)); }), [run, course.id]);
  useEffect(() => { load(); }, [load]);
  const start = (id: string) => run(async () => { const q = await api.quiz(id); setQuiz(q); setAnswers(q.questions.map(() => -1)); setResult(null); });
  return (
    <div>
      <h3>Flashcards ({cards.length})</h3>
      <div className="cards">
        {cards.map((c) => (
          <button key={c.id} className="flash" onClick={() => setFlipped({ ...flipped, [c.id]: !flipped[c.id] })}>
            {flipped[c.id] ? <><span>{c.definition}</span><small>{c.document}, p. {c.page}</small></> : <strong>{c.term}</strong>}
          </button>
        ))}
      </div>
      <h3>Quizzes</h3>
      <div className="row">
        <button onClick={() => run(async () => { const q = await api.createQuiz(course.id, "Practice quiz", 5); await load(); await start(q.id); })}>New practice quiz</button>
      </div>
      <ul>{quizzes.map((q) => <li key={q.id}><button className="link" onClick={() => start(q.id)}>{q.title}</button> <span className="muted">{q.questions} questions{q.official ? " · from instructor" : ""}</span></li>)}</ul>
      {quiz && (
        <form className="card" onSubmit={(e) => { e.preventDefault(); run(async () => setResult(await api.attempt(quiz.id, answers))); }}>
          <h3>{quiz.title}</h3>
          {quiz.questions.map((q, i) => (
            <fieldset key={i}>
              <legend>{i + 1}. {q.prompt}</legend>
              {q.options.map((o, j) => (
                <label key={j} className={result ? (result.results[i].answer_index === j ? "right" : answers[i] === j ? "wrong" : "") : ""}>
                  <input type="radio" name={`q${i}`} checked={answers[i] === j} disabled={!!result}
                    onChange={() => setAnswers(answers.map((a, k) => (k === i ? j : a)))} /> {o}
                </label>
              ))}
              {result && <small className="muted">Source: p. {result.results[i].source.page} {result.results[i].source.section.split(" > ").slice(-1)[0]}</small>}
            </fieldset>
          ))}
          {result ? <p data-testid="score"><strong>Score: {result.score} / {result.total}</strong></p> : <button disabled={answers.includes(-1)}>Submit answers</button>}
        </form>
      )}
      <ErrorLine error={error} />
    </div>
  );
}

function Compare({ course, docs }: { course: Course; docs: Doc[] }) {
  const [a, setA] = useState("");
  const [b, setB] = useState("");
  const [r, setR] = useState<Comparison | null>(null);
  const { error, run } = useAsync();
  return (
    <div>
      <form className="row" onSubmit={(e) => { e.preventDefault(); run(async () => setR(await api.compare(course.id, a, b))); }}>
        <select value={a} onChange={(e) => setA(e.target.value)} required aria-label="First document"><option value="">First document…</option>{docs.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</select>
        <select value={b} onChange={(e) => setB(e.target.value)} required aria-label="Second document"><option value="">Second document…</option>{docs.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</select>
        <button>Compare</button>
      </form>
      <ErrorLine error={error} />
      {r && (
        <div className="grid3">
          <div className="card"><h4>Only in first</h4><p>{r.distinctive_a.join(", ") || "—"}</p></div>
          <div className="card"><h4>Shared</h4><p>{r.shared_terms.join(", ") || "—"}</p></div>
          <div className="card"><h4>Only in second</h4><p>{r.distinctive_b.join(", ") || "—"}</p></div>
          {r.aligned_sections.length > 0 && <div className="card span3"><h4>Matching sections</h4>
            <ul>{r.aligned_sections.map((x, i) => <li key={i}>{x.a.section.split(" > ").slice(-1)[0]} (p. {x.a.page}) ↔ {x.b.section.split(" > ").slice(-1)[0]} (p. {x.b.page}) <span className="muted">similarity {x.similarity}</span></li>)}</ul></div>}
        </div>
      )}
    </div>
  );
}

function Documents({ course, docs, reload }: { course: Course; docs: Doc[]; reload: () => void }) {
  const { error, busy, run } = useAsync();
  const [msg, setMsg] = useState("");
  return (
    <div>
      {course.role === "instructor" && (
        <div className="row">
          <label className="button">Upload document (PDF, Markdown, HTML, text)
            <input type="file" hidden accept=".pdf,.md,.markdown,.html,.htm,.txt" onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) run(async () => { const r = await api.upload(course.id, f); setMsg(`Uploaded ${f.name}${r.flashcards !== undefined ? ` · ${r.flashcards} flashcards` : ""}`); reload(); });
              e.target.value = "";
            }} />
          </label>
          {busy && <span className="muted">Processing…</span>}
          {msg && <span className="ok">{msg}</span>}
        </div>
      )}
      {course.role === "instructor" && course.join_code && <p>Students join with code <code data-testid="join-code">{course.join_code}</code></p>}
      <table>
        <thead><tr><th>Document</th><th>Pages</th><th>Size</th>{course.role === "instructor" && <th />}</tr></thead>
        <tbody>{docs.map((d) => (
          <tr key={d.id}><td>{d.name}</td><td>{d.pages}</td><td>{(d.bytes / 1024).toFixed(1)} KB</td>
            {course.role === "instructor" && <td><button className="small danger" onClick={() => run(async () => { await api.deleteDoc(course.id, d.id); reload(); })}>Delete</button></td>}</tr>
        ))}</tbody>
      </table>
      <ErrorLine error={error} />
    </div>
  );
}

function Analytics({ course }: { course: Course }) {
  const [a, setA] = useState<Record<string, any> | null>(null);
  const { error, run } = useAsync();
  useEffect(() => { run(async () => setA(await api.analytics(course.id))); }, [run, course.id]);
  if (!a) return <ErrorLine error={error} />;
  if (!a.eligible_askers_at_least_k)
    return (
      <div className="card">
        <h4>Questions asked: {a.questions}</h4>
        <p className="muted">Detailed analytics appear once at least {a.k} students (members for more than a day) have asked questions.
          Every figure is shown only when at least {a.k} students contributed to it, and individual questions are never shown.</p>
      </div>
    );
  return (
    <div className="grid3">
      <div className="card"><h4>Questions</h4><p className="big">{a.questions}</p><p className="muted">{a.distinct_askers} students</p></div>
      <div className="card"><h4>No answer found</h4><p className="big">{a.abstention_rate === null ? "—" : `${Math.round(a.abstention_rate * 100)}%`}</p><p className="muted">signals gaps in the material</p></div>
      <div className="card"><h4>Feedback</h4><p className="big">{a.feedback ? a.feedback.avg_rating : "—"}</p><p className="muted">{a.feedback ? `${a.feedback.count} ratings (−1…1)` : `fewer than ${a.k} raters`}</p></div>
      <div className="card span3"><h4>Topics asked by at least {a.k} students</h4>
        <p>{a.topics.length ? a.topics.map((t: { term: string; askers: number }) => `${t.term} (${t.askers})`).join(", ") : "None yet."}</p></div>
      <div className="card span3"><h4>Most cited documents</h4><ul>{a.most_cited_documents.map((d: { document: string; citations: number }) => <li key={d.document}>{d.document}: {d.citations}</li>)}</ul></div>
    </div>
  );
}

function CourseView({ course, back }: { course: Course; back: () => void }) {
  const tabs = ["Ask", "Research", "Study", "Compare", "Documents", ...(course.role === "instructor" ? ["Analytics"] : [])];
  const [tab, setTab] = useState("Ask");
  const [docs, setDocs] = useState<Doc[]>([]);
  const reload = useCallback(() => { api.docs(course.id).then(setDocs).catch(() => setDocs([])); }, [course.id]);
  useEffect(() => { reload(); }, [reload]);
  const body: Record<string, ReactNode> = {
    Ask: <Ask course={course} />, Research: <ResearchTab course={course} />, Study: <Study course={course} />,
    Compare: <Compare course={course} docs={docs} />, Documents: <Documents course={course} docs={docs} reload={reload} />,
    Analytics: <Analytics course={course} />,
  };
  return (
    <section>
      <button className="link" onClick={back}>← All courses</button>
      <h2>{course.code} · {course.title}</h2>
      <nav className="tabs" role="tablist">{tabs.map((t) => <button key={t} role="tab" aria-selected={tab === t} className={tab === t ? "active" : ""} onClick={() => setTab(t)}>{t}</button>)}</nav>
      {body[tab]}
    </section>
  );
}

function Admin() {
  const [users, setUsers] = useState<(User & { disabled: boolean })[]>([]);
  const { error, run } = useAsync();
  const load = useCallback(() => run(async () => setUsers(await api.adminUsers())), [run]);
  useEffect(() => { load(); }, [load]);
  return (
    <section>
      <h2>Users</h2>
      <table>
        <thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Status</th></tr></thead>
        <tbody>{users.map((u) => (
          <tr key={u.id}><td>{u.name}</td><td>{u.email}</td>
            <td><select value={u.role} aria-label={`Role of ${u.email}`} onChange={(e) => run(async () => { await api.adminUpdate(u.id, { role: e.target.value }); await load(); })}>
              <option value="student">student</option><option value="faculty">faculty</option><option value="admin">admin</option></select></td>
            <td><button className="small" onClick={() => run(async () => { await api.adminUpdate(u.id, { disabled: !u.disabled }); await load(); })}>{u.disabled ? "Enable" : "Disable"}</button></td></tr>
        ))}</tbody>
      </table>
      <ErrorLine error={error} />
    </section>
  );
}

export default function App() {
  const [user, setUser] = useState<User | null | undefined>(undefined);
  const [course, setCourse] = useState<Course | null>(null);
  const [page, setPage] = useState<"courses" | "admin">("courses");
  useEffect(() => { api.me().then(setUser).catch(() => setUser(null)); }, []);
  if (user === undefined) return <p className="muted center">Loading…</p>;
  if (user === null) return <Auth onLogin={setUser} />;
  return (
    <div className="app">
      <header>
        <strong>Campus AI OS</strong>
        <nav>
          <button className="link" onClick={() => { setPage("courses"); setCourse(null); }}>Courses</button>
          {user.role === "admin" && <button className="link" onClick={() => setPage("admin")}>Admin</button>}
          <span className="muted">{user.name} ({user.role})</span>
          <button className="link" onClick={async () => { await api.logout(); setUser(null); setCourse(null); }}>Sign out</button>
        </nav>
      </header>
      <main>
        {page === "admin" ? <Admin /> : course ? <CourseView course={course} back={() => setCourse(null)} /> : <Dashboard user={user} open={setCourse} />}
      </main>
    </div>
  );
}
