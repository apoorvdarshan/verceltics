"""Wait for the tagged Xcode Cloud run and its own processed iOS artifact.

This module only reads ASC. It never starts, retries, or enables a Cloud build.
"""
from __future__ import annotations

import re
import time
from typing import Any
from urllib.parse import urlencode


class BuildWaitError(RuntimeError):
    pass


def validate_identity(tag: str, commit: str, version: str) -> None:
    if not re.fullmatch(r"ios-v\d+\.\d+(?:\.\d+)?", tag) or tag != f"ios-v{version}":
        raise BuildWaitError("review requires an ios-v tag matching the marketing version")
    if not re.fullmatch(r"[0-9a-fA-F]{40}", commit):
        raise BuildWaitError("review requires the full tagged source commit SHA")


def related_id(resource: dict[str, Any], relationship: str) -> str | None:
    data = resource.get("relationships", {}).get(relationship, {}).get("data")
    return data.get("id") if isinstance(data, dict) else None


def wait_for_build(
    client: Any, *, app_id: str, workflow_id: str, tag: str, commit: str,
    version: str, timeout: int = 7200, interval: int = 30,
) -> str:
    validate_identity(tag, commit, version)
    if not workflow_id or timeout <= 0 or interval <= 0:
        raise BuildWaitError("workflow ID and positive wait limits are required")
    deadline = time.monotonic() + timeout

    def check_deadline() -> None:
        if time.monotonic() >= deadline:
            raise BuildWaitError(f"timed out waiting for {tag}; no review submitted")

    def pages(path: str):
        seen: set[str] = set()
        while path:
            check_deadline()
            if path in seen:
                raise BuildWaitError("ASC returned a pagination loop")
            seen.add(path)
            payload = client.get(path)
            yield payload
            path = payload.get("links", {}).get("next")

    def pause(message: str) -> None:
        check_deadline()
        print(message, flush=True)
        time.sleep(min(interval, max(0, deadline - time.monotonic())))

    product = client.get(f"/apps/{app_id}/ciProduct")["data"]
    # ciWorkflows does not expose a product relationship. Verify ownership
    # through the app's CI product workflow collection instead.
    workflows = {
        workflow["id"]
        for payload in pages(f"/ciProducts/{product['id']}/workflows?limit=200")
        for workflow in payload["data"]
    }
    if workflow_id not in workflows:
        raise BuildWaitError("Xcode Cloud workflow belongs to a different app")

    run_id: str | None = None
    while run_id is None:
        matches: set[str] = set()
        query = urlencode({"limit": 200, "sort": "-number", "include": "sourceBranchOrTag"})
        for payload in pages(f"/ciWorkflows/{workflow_id}/buildRuns?{query}"):
            refs = {item["id"]: item for item in payload.get("included", [])
                    if item["type"] == "scmGitReferences"}
            for run in payload["data"]:
                attrs = run.get("attributes", {})
                if attrs.get("isPullRequestBuild") or (
                    attrs.get("sourceCommit") or {}
                ).get("commitSha", "").lower() != commit.lower():
                    continue
                ref_id = related_id(run, "sourceBranchOrTag")
                if not ref_id:
                    continue
                ref = refs.get(ref_id)
                if ref is None:
                    ref = client.get(f"/scmGitReferences/{ref_id}")["data"]
                ref_attrs = ref.get("attributes", {})
                if ref_attrs.get("kind") == "TAG" and ref_attrs.get("name") == tag:
                    matches.add(run["id"])
        if len(matches) > 1:
            raise BuildWaitError("multiple Cloud runs match this tag and commit; select the intended run manually")
        if matches:
            run_id = matches.pop()
        else:
            pause(f"Waiting for Xcode Cloud run: {tag} at {commit}…")

    while True:
        check_deadline()
        run = client.get(f"/ciBuildRuns/{run_id}")["data"]
        attrs = run.get("attributes", {})
        status = attrs.get("completionStatus")
        progress = attrs.get("executionProgress")
        if status in {"FAILED", "ERRORED", "CANCELED", "SKIPPED"}:
            raise BuildWaitError(f"Xcode Cloud run {run_id} ended with {status}")
        if progress == "COMPLETE":
            if status != "SUCCEEDED":
                raise BuildWaitError(f"Cloud run completed without success: {status}")
            break
        if progress not in {"PENDING", "RUNNING"}:
            raise BuildWaitError(f"unknown Cloud execution progress: {progress}")
        pause(f"Xcode Cloud run {run_id}: {progress}…")

    while True:
        builds: dict[str, dict[str, Any]] = {}
        for payload in pages(f"/ciBuildRuns/{run_id}/builds?limit=200&include=app,preReleaseVersion"):
            included = {(item["type"], item["id"]): item for item in payload.get("included", [])}
            for build in payload["data"]:
                if related_id(build, "app") != app_id:
                    raise BuildWaitError("Cloud run produced an artifact for a different app")
                prerelease_id = related_id(build, "preReleaseVersion")
                if not prerelease_id:
                    raise BuildWaitError("uploaded build has no marketing version relationship")
                prerelease = included.get(("preReleaseVersions", prerelease_id))
                if prerelease is None:
                    prerelease = client.get(f"/preReleaseVersions/{prerelease_id}")["data"]
                prerelease_attrs = prerelease.get("attributes", {})
                if prerelease_attrs.get("platform") != "IOS":
                    continue
                if prerelease_attrs.get("version") != version:
                    raise BuildWaitError("Cloud artifact marketing version does not match the tag")
                builds[build["id"]] = build
        if len(builds) > 1:
            raise BuildWaitError("Cloud run has multiple iOS artifacts; refusing an ambiguous selection")
        if builds:
            build_id, build = next(iter(builds.items()))
            attrs = build.get("attributes", {})
            processing = attrs.get("processingState")
            if attrs.get("expired") or processing in {"FAILED", "INVALID"}:
                raise BuildWaitError(f"Cloud artifact is expired or processing failed: {processing}")
            if processing == "VALID":
                check_deadline()
                print(f"Xcode Cloud run {run_id}: processed iOS build {build_id}", flush=True)
                return build_id
            if processing != "PROCESSING":
                raise BuildWaitError(f"unknown build processing state: {processing}")
            pause(f"Waiting for App Store processing of build {build_id}…")
        else:
            pause(f"Waiting for App Store artifact from Cloud run {run_id}…")


def select_build(client: Any, version: dict[str, Any], build_id: str, editable_states: set[str]) -> None:
    """Attach only the verified artifact; a retry with the same selection is safe."""
    path = f"/appStoreVersions/{version['id']}/relationships/build"
    selected = client.get(path).get("data")
    if selected and selected.get("id") == build_id:
        return
    state = version.get("attributes", {}).get("appStoreState")
    if state not in editable_states:
        raise BuildWaitError(f"cannot select the verified build while App Store version is {state}")
    # Apple returns HTTP 204 here, unlike resource PATCH endpoints.
    client.request("PATCH", path, body={"data": {"type": "builds", "id": build_id}})
    if (client.get(path).get("data") or {}).get("id") != build_id:
        raise BuildWaitError("App Store Connect did not retain the verified build selection")
