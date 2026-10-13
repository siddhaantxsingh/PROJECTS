import { expect, test, type Page } from "@playwright/test";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));

const PW = "correct horse battery";

async function register(page: Page, email: string, name: string) {
  await page.goto("/");
  await page.getByRole("button", { name: "New here? Create an account" }).click();
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Name").fill(name);
  await page.getByLabel("Password").fill(PW);
  await page.getByRole("button", { name: "Register" }).click();
  await expect(page.getByText(`${name} (student)`)).toBeVisible();
}

async function signIn(page: Page, email: string, pw = PW) {
  await page.goto("/");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(pw);
  await page.getByRole("button", { name: "Sign in" }).click();
}

test("faculty publishes material; student gets a cited answer, studies, takes a quiz; faculty sees analytics", async ({ browser }) => {
  // Faculty registers; the admin promotes them in the admin UI.
  const prof = await (await browser.newContext()).newPage();
  await register(prof, "prof@uni.edu", "Prof Ada");
  const admin = await (await browser.newContext()).newPage();
  await signIn(admin, "admin@uni.edu", "admin-password-123");
  await admin.getByRole("button", { name: "Admin" }).click();
  await admin.getByLabel("Role of prof@uni.edu").selectOption("faculty");
  await expect(admin.getByLabel("Role of prof@uni.edu")).toHaveValue("faculty");

  // Faculty creates a course and uploads a document.
  await prof.reload();
  await expect(prof.getByText("Prof Ada (faculty)")).toBeVisible();
  await prof.getByLabel("Course code").fill("CS201");
  await prof.getByLabel("Course title").fill("Data Structures");
  await prof.getByRole("button", { name: "Create course" }).click();
  await prof.getByRole("button", { name: /CS201/ }).click();
  await prof.getByRole("tab", { name: "Documents" }).click();
  await prof.locator("input[type=file]").setInputFiles(path.join(HERE, "lecture.md"));
  await expect(prof.getByText(/Uploaded lecture.md · \d+ flashcards/)).toBeVisible();
  const joinCode = (await prof.getByTestId("join-code").textContent())!.trim();

  // Student registers, joins, asks.
  const student = await (await browser.newContext()).newPage();
  await register(student, "alice@uni.edu", "Alice");
  await student.getByLabel("Join code").fill(joinCode);
  await student.getByRole("button", { name: "Join course" }).click();
  await student.getByRole("button", { name: /CS201/ }).click();
  await student.getByLabel("Question").fill("How are hash collisions resolved?");
  await student.getByRole("button", { name: "Ask" }).click();
  await expect(student.getByTestId("answer")).toContainText("chaining");
  await expect(student.getByText(/\[1\] CS201 Data Structures — p\. 1/)).toBeVisible();
  await student.screenshot({ path: path.join(HERE, "..", "..", "docs", "screenshots", "ask.png"), fullPage: true });
  await student.getByRole("button", { name: "Yes" }).click();
  await expect(student.getByText("Thanks for the feedback.")).toBeVisible();

  // Out-of-scope question: abstains instead of inventing.
  await student.getByLabel("Question").fill("Who painted the Mona Lisa?");
  await student.getByRole("button", { name: "Ask" }).click();
  await expect(student.getByTestId("answer")).toContainText("could not find");

  // Study: flashcards and a practice quiz.
  await student.getByRole("tab", { name: "Study" }).click();
  await expect(student.getByRole("button", { name: "hash table", exact: true })).toBeVisible();
  await student.getByRole("button", { name: "New practice quiz" }).click();
  const sets = student.locator("fieldset");
  await expect(sets).toHaveCount(5);
  for (let i = 0; i < 5; i++) await sets.nth(i).locator("input[type=radio]").first().check();
  await student.getByRole("button", { name: "Submit answers" }).click();
  await expect(student.getByTestId("score")).toContainText("/ 5");
  await student.screenshot({ path: path.join(HERE, "..", "..", "docs", "screenshots", "quiz.png"), fullPage: true });

  // Students do not see the analytics tab; faculty does.
  await expect(student.getByRole("tab", { name: "Analytics" })).toHaveCount(0);
  await prof.getByRole("tab", { name: "Analytics" }).click();
  await expect(prof.getByText("Questions asked: 2")).toBeVisible();  // k-anonymity: no breakdown for 1 student
  await prof.screenshot({ path: path.join(HERE, "..", "..", "docs", "screenshots", "analytics.png"), fullPage: true });
});
