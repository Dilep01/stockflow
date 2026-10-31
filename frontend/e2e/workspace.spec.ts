import { test, expect } from "@playwright/test";
const password = process.env.DEMO_PASSWORD || "StockflowDemo2026!";
test("admin can navigate the operational workspace and adjust inventory", async ({
  page,
}) => {
  await page.goto("/");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Operations overview" }),
  ).toBeVisible();
  await page.screenshot({
    path: "../artifacts/overview-desktop.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "Inventory", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Inventory", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Adjust stock" }).click();
  await page
    .getByRole("combobox", { name: "Product", exact: true })
    .selectOption("SKU-KEY");
  await page.getByLabel("Quantity change").fill("2");
  await page.getByLabel("Reason", { exact: true }).fill("Browser test receipt");
  await page.getByRole("button", { name: "Save changes" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  for (const title of [
    "Catalog",
    "Orders",
    "Shipments",
    "Returns",
    "Transfers",
    "Activity",
    "System",
  ]) {
    await page.getByRole("button", { name: title, exact: true }).click();
    await expect(
      page.getByRole("heading", { name: title, exact: true }),
    ).toBeVisible();
  }
  await expect(page.getByRole("alert")).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "Overview", exact: true }).click();
  await page.screenshot({
    path: "../artifacts/overview-mobile.png",
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBeTruthy();
});
test("customer can submit a demo order and view its progress", async ({
  page,
}) => {
  await page.goto("/");
  await page.getByLabel("Email address").fill("customer@stockflow.local");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Catalog", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Add to order" }).first().click();
  await page.getByRole("button", { name: "Review order" }).click();
  await page.getByLabel("Delivery address").fill("Demo customer, Bengaluru");
  await page.getByRole("button", { name: "Place order" }).click();
  await expect(
    page.getByRole("heading", { name: "Orders", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "View details" }).first(),
  ).toBeVisible();
  await page.getByRole("button", { name: "View details" }).first().click();
  await expect(
    page.getByRole("heading", { name: "Order timeline" }),
  ).toBeVisible();
});
test("invalid login shows a useful error", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("Password", { exact: true }).fill("incorrect-password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText(
    "Invalid email or password",
  );
});
