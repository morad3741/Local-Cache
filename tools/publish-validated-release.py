#!/usr/bin/env python3
"""Publish an existing tested APK, without rebuilding or changing its signing identity."""

import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def command(*args):
    return subprocess.check_output(list(args), text=True)


repo = os.environ["GITHUB_REPOSITORY"]
tag = os.environ["RELEASE_TAG"]
run_id = os.environ["VALIDATED_RUN_ID"]
target = os.environ["RELEASE_TARGET"]
expected_sha = os.environ["EXPECTED_APK_SHA256"]
require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo), "Invalid repository")
require(re.fullmatch(r"v\d+\.\d+\.\d+", tag), "Invalid release tag")
require(run_id.isdigit(), "Invalid validation run ID")
require(re.fullmatch(r"[a-f0-9]{40}", target), "Expected a full commit SHA")
require(re.fullmatch(r"[a-f0-9]{64}", expected_sha), "Expected an APK SHA256")
require(sys.argv[1:] in (["verify"], ["publish"]), "Use verify or publish")


def api(path):
    return json.loads(command("gh", "api", f"repos/{repo}/{path}"))


def provenance():
    run = api(f"actions/runs/{run_id}")
    require(run["status"] == "completed" and run["conclusion"] == "success", "Validation has not passed")
    require(run["name"] == "Torrent validation", "Expected the complete torrent validation workflow")
    require(run["head_repository"]["full_name"].lower() == repo.lower(), "APK came from a different repository")
    tested = api(f"git/commits/{run['head_sha']}")
    release = api(f"git/commits/{target}")
    require(tested["tree"]["sha"] == release["tree"]["sha"], "Release differs from the tested source tree")
    drafts = [r for r in api("releases?per_page=100") if r["tag_name"] == tag]
    require(len(drafts) == 1, "Expected one existing draft release for this tag")
    draft = drafts[0]
    require(draft["draft"], "Refusing to replace assets of an already published release")
    require(draft["target_commitish"] == target, "Draft targets a different commit")
    return run, draft


run, draft = provenance()
print(f"Validated run {run_id}, tested source {run['head_sha']}, release target {target}")
if sys.argv[1] == "verify":
    raise SystemExit(0)

apks = list(Path("downloaded-apk").rglob("*.apk"))
require(len(apks) == 1, "Expected exactly one standard debug APK")
require(hashlib.sha256(apks[0].read_bytes()).hexdigest() == expected_sha, "APK checksum differs from the validated binary")
sdk = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "35.0.0"
signature = command(str(sdk / "apksigner"), "verify", "--verbose", "--print-certs", str(apks[0]))
badging = command(str(sdk / "aapt"), "dump", "badging", str(apks[0]))
identity = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
require(identity is not None, "APK has no package/version identity")
package, code, version = identity.groups()
source = api(f"contents/android/app/build.gradle.kts?ref={target}")
gradle = base64.b64decode(source["content"]).decode()
expected_code = re.search(r"versionCode\s*=\s*(\d+)", gradle).group(1)
expected_version = re.search(r'versionName\s*=\s*"([^"]+)"', gradle).group(1)
require(package == "app.localcache.release", "Unexpected APK application ID")
require(version == tag[1:] == expected_version and code == expected_code, "APK version differs from tag/source")
require("application-debuggable" in badging, "This workflow labels its APK as a debug build")
print(f"Signature verified; package={package} version={version} versionCode={code}")

dist = Path("release-assets")
dist.mkdir(exist_ok=False)
apk = dist / f"LocalCache-{tag}-standard-debug.apk"
shutil.copyfile(apks[0], apk)
info = dist / "BUILD-INFO.txt"
info.write_text(
    f"Local Cache {tag} (versionCode {code})\n"
    f"Variant: standardDebug; package: {package}\n"
    f"Tested source: {run['head_sha']}\nMain merge/tag target: {target}\n"
    f"CI run: {run['html_url']}\nAPK SHA256: {expected_sha}\n\n{signature}"
)
sums = dist / "SHA256SUMS"
sums.write_text("".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n" for p in (apk, info)))
assets = (apk, info, sums)
subprocess.run(["gh", "release", "upload", tag, *(str(p) for p in assets), "--repo", repo, "--clobber"], check=True)
uploaded = {a["name"]: a for a in api(f"releases/{draft['id']}")["assets"]}
for asset in assets:
    digest = "sha256:" + hashlib.sha256(asset.read_bytes()).hexdigest()
    require(uploaded.get(asset.name, {}).get("digest") == digest, f"Uploaded checksum mismatch: {asset.name}")
subprocess.run(["gh", "release", "edit", tag, "--repo", repo, "--draft=false", "--latest"], check=True)
published = api(f"releases/{draft['id']}")
require(not published["draft"], "Release was not published")
print(published["html_url"])
summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a") as stream:
        stream.write(f"Published [{tag}]({published['html_url']}) from verified CI APK `{expected_sha}`.\n")
