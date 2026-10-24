"""Packages the project into Fullstack_Java_Angular_Enterprise_Project.zip at the project root.

Internal top folder: Fullstack_Java_Angular_Enterprise_Project/.
Excludes dependency and build output (node_modules, target, dist, .angular), local runtime state (.run), the internal work
area (.work, which holds downloaded tools), agent/editor settings (.claude, .vscode, .idea), VCS data (.git), Terraform local
state/cache (.terraform, *.tfstate), environment files (.env, but .env.example IS included), logs and the archive itself.
Usage: python -I scripts/make-zip.py
"""
import os
import sys
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
NAME = "Fullstack_Java_Angular_Enterprise_Project"
OUT = os.path.join(ROOT, NAME + ".zip")
EXCLUDE_DIRS = {"node_modules", "target", "dist", ".angular", ".run", ".work", ".claude", ".git", ".vscode",
                ".idea", "__pycache__", ".terraform", ".gradle", "playwright-report", "test-results", "coverage"}
EXCLUDE_SUFFIXES = (".log", ".class", ".zip.tmp", ".tfstate", ".tfstate.backup", ".pem", ".key", ".p12", ".jks", ".tfvars")
EXCLUDE_FILES = {NAME + ".zip", ".env", "pg-tmp.mjs"}

count = 0
with zipfile.ZipFile(OUT + ".tmp", "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = sorted(d for d in dirnames if d not in EXCLUDE_DIRS)
        for f in sorted(filenames):
            if f in EXCLUDE_FILES or f.endswith(EXCLUDE_SUFFIXES):
                continue
            if f.startswith(".env") and f != ".env.example":
                continue
            if "full" in f and "samples" in dirpath.replace(os.sep, "/"):
                continue
            full = os.path.join(dirpath, f)
            rel = os.path.relpath(full, ROOT).replace(os.sep, "/")
            z.write(full, NAME + "/" + rel)
            count += 1
os.replace(OUT + ".tmp", OUT)
print("wrote", OUT, "files:", count, "size MB:", round(os.path.getsize(OUT) / 1024 / 1024, 2))
sys.exit(0)
