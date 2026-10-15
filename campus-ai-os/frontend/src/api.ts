// Typed API client. Cookies carry the session; the browser adds Origin,
// which the server checks on state-changing requests.
export type User = { id: string; email: string; name: string; role: "admin" | "faculty" | "student" };
export type Course = { id: string; code: string; title: string; role: "instructor" | "student"; join_code?: string | null };
export type Doc = { id: string; name: string; bytes: number; pages: number; created_at: string };
export type Source = { n: number; document: string; doc_id: string; pages: [number, number]; section: string; excerpt: string };
export type Answer = {
  answer: string; abstained: boolean; question_id: string; sources: Source[]; provider: string;
  usage?: { dropped_sentences?: number; auto_attributed_sentences?: number; tokens_out?: number };
  grounding: { groundedness: number | null; citation_precision: number | null };
  timings_ms: Record<string, number>; excluded_sources: { document: string; reasons: string[] }[];
};
export type Card = { id: string; term: string; definition: string; page: number; section: string; document: string };
export type QuizSummary = { id: string; title: string; questions: number; official: boolean };
export type Quiz = { id: string; title: string; questions: { prompt: string; options: string[] }[] };
export type AttemptResult = { score: number; total: number; results: { correct: boolean; answer_index: number; source: { page: number; section: string } }[] };
export type Research = { documents: { document: string; document_id: string; evidence: { sentence: string; page: number; section: string }[] }[] };
export type Comparison = {
  shared_terms: string[]; distinctive_a: string[]; distinctive_b: string[];
  aligned_sections: { a: { section: string; page: number; excerpt: string }; b: { section: string; page: number; excerpt: string }; similarity: number }[];
};

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const init: RequestInit = { method, credentials: "same-origin", headers: {} };
  if (body instanceof FormData) init.body = body;
  else if (body !== undefined) {
    init.body = JSON.stringify(body);
    (init.headers as Record<string, string>)["Content-Type"] = "application/json";
  }
  const res = await fetch(path, init);
  if (!res.ok) {
    let msg = res.statusText;
    try {
      const j = await res.json();
      msg = typeof j.detail === "string" ? j.detail : JSON.stringify(j.detail);
    } catch { /* not JSON */ }
    throw new ApiError(res.status, msg);
  }
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

export const api = {
  me: () => call<User>("GET", "/api/me"),
  login: (email: string, password: string) => call<User>("POST", "/api/auth/login", { email, password }),
  register: (email: string, name: string, password: string) => call<User>("POST", "/api/auth/register", { email, name, password }),
  logout: () => call<void>("POST", "/api/auth/logout"),
  courses: () => call<Course[]>("GET", "/api/courses"),
  createCourse: (code: string, title: string) => call<Course>("POST", "/api/courses", { code, title }),
  join: (join_code: string) => call<Course>("POST", "/api/courses/join", { join_code }),
  docs: (c: string) => call<Doc[]>("GET", `/api/courses/${c}/documents`),
  upload: (c: string, f: File) => { const fd = new FormData(); fd.append("file", f); return call<{ flashcards?: number }>("POST", `/api/courses/${c}/documents`, fd); },
  deleteDoc: (c: string, d: string) => call<void>("DELETE", `/api/courses/${c}/documents/${d}`),
  ask: (c: string, query: string) => call<Answer>("POST", `/api/courses/${c}/ask`, { query }),
  research: (c: string, query: string) => call<Research>("POST", `/api/courses/${c}/research`, { query }),
  feedback: (q: string, rating: 1 | -1) => call<void>("POST", `/api/questions/${q}/feedback`, { rating }),
  cards: (c: string) => call<Card[]>("GET", `/api/courses/${c}/flashcards`),
  quizzes: (c: string) => call<QuizSummary[]>("GET", `/api/courses/${c}/quizzes`),
  createQuiz: (c: string, title: string, questions: number) => call<{ id: string }>("POST", `/api/courses/${c}/quizzes`, { title, questions }),
  quiz: (q: string) => call<Quiz>("GET", `/api/quizzes/${q}`),
  attempt: (q: string, answers: number[]) => call<AttemptResult>("POST", `/api/quizzes/${q}/attempts`, { answers }),
  compare: (c: string, doc_a: string, doc_b: string) => call<Comparison>("POST", `/api/courses/${c}/compare`, { doc_a, doc_b }),
  analytics: (c: string) => call<Record<string, unknown>>("GET", `/api/courses/${c}/analytics`),
  adminUsers: () => call<(User & { disabled: boolean })[]>("GET", "/api/admin/users"),
  adminUpdate: (u: string, patch: Record<string, unknown>) => call<void>("PATCH", `/api/admin/users/${u}`, patch),
};
