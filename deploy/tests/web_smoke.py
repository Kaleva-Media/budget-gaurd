"""Headless checks of built assets; synthetic/demo data only, no server writes."""
import argparse
import subprocess
import time
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

parser = argparse.ArgumentParser()
parser.add_argument("--mode", choices=["demo", "auth"], required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
server = subprocess.Popen(["bunx", "--bun", "vite", "preview", "--host", "127.0.0.1", "--port", "4173", "--strictPort"], cwd=root / "apps/web", stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        for attempt in range(30):
            try:
                page.goto("http://127.0.0.1:4173", timeout=2000)
                break
            except Exception:
                if server.poll() is not None or attempt == 29:
                    raise
                time.sleep(0.2)
        page.wait_for_load_state("networkidle")
        if args.mode == "demo":
            expect(page.get_by_role("heading", name="Your money, today.")).to_be_visible()
            nav = page.get_by_role("navigation", name="Main navigation")
            for button, title in [("Monthly plan", "Build the month before it happens."), ("Transactions", "Every movement."), ("Accounts", "Every account has a job.")]:
                nav.get_by_role("button", name=button).click()
                expect(page.get_by_role("heading", name=title)).to_be_visible()
            page.set_viewport_size({"width": 390, "height": 844})
            page.get_by_role("button", name="Open navigation").click()
            nav.get_by_role("button", name="Overview").click()
            expect(page.get_by_role("heading", name="Your money, today.")).to_be_visible()
        else:
            # Block any accidental POST so these tests cannot create real users.
            page.route("**/auth/v1/**", lambda route: route.abort() if route.request.method == "POST" else route.continue_())
            expect(page.get_by_role("heading", name="Welcome back")).to_be_visible()
            page.get_by_role("tab", name="Create account").click()
            expect(page.get_by_role("heading", name="Create your vault")).to_be_visible()
            page.get_by_label("Email address").fill("smoke@example.invalid")
            page.get_by_label("Password", exact=True).fill("synthetic-password-a")
            page.get_by_label("Confirm password").fill("synthetic-password-b")
            page.get_by_role("button", name="Create account", exact=True).click()
            expect(page.get_by_role("alert")).to_have_text("The passwords do not match.")
            page.get_by_role("button", name="Forgot your password?").click()
            expect(page.get_by_role("heading", name="Reset your password")).to_be_visible()
            page.get_by_role("button", name="Back to sign in").click()
            expect(page.get_by_role("heading", name="Welcome back")).to_be_visible()
        assert not errors, f"Uncaught browser errors: {errors}"
        browser.close()
    print(f"Built web {args.mode} browser smoke checks passed")
finally:
    server.terminate()
    server.wait(timeout=10)
