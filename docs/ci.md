# Continuous integration

What runs on this repository, when, and what to do when it fails. The workflows live in
[`.github/workflows`](../.github/workflows); the rules and CodeQL are set in the repository
settings.

| What | Runs on | Does |
|---|---|---|
| [CI](../.github/workflows/ci.yml) | every pull request, pushes to `main` | builds the plugin and runs the tests |
| [Checklist](../.github/workflows/pr-checklist.yml) | pull requests: opened, edited, new push | checks the boxes of the description |
| [Release](../.github/workflows/release.yml) | a pushed `v*` tag | builds and publishes a GitHub release |
| CodeQL (default setup) | pull requests, pushes to `main`, weekly | scans the Java code and the workflows |
| [Dependabot](../.github/dependabot.yml) | weekly | opens pull requests for Gradle and action updates |

## Merging into `main`

The *Protect main* ruleset blocks deleting `main` and force-pushing to it, and requires two checks
to pass on the exact commit:

- **`build`**, from CI.
- **`checklist`**, from Checklist.

Pull requests are squash-merged: the title becomes the commit on `main`, so it follows
[Conventional Commits](https://www.conventionalcommits.org/).

## CI: `build`

Two jobs:

1. **`changes`** decides whether the change is documentation only: Markdown files, `docs/`,
   `LICENSE` and `.github/ISSUE_TEMPLATE/`. Every pull request runs CI, docs included, because
   `build` is required: a workflow that never starts would leave the check pending forever.
2. **`build`** runs `gradlew build` on JDK 25 (Temurin, Linux), unless `changes` found docs only.
   A skipped job counts as passed for a required check.

Pushes to `main` that only touch documentation do not run CI at all (`paths-ignore`, same list).

- **Skipped tests fail the build.** MockBukkit skips a test that reaches a method it does not
  implement. A skipped test proves nothing, so `build.gradle.kts` turns it into a failure.
- **Warnings fail the build**: the compiler runs with `-Xlint:all -Werror`.
- **15 minute limit.** A build takes 1 to 3 minutes: past 15, a test is hung.
- **Artifacts**, downloadable from the run summary:
  - `BlockHunt-jar`: the plugin jar of that commit.
  - `test-reports`, only when the build fails: the HTML report and the XML results.
- A new push to a pull request cancels its build still running. Each build of `main` runs to the end.

## Checklist: `checklist`

When a pull request touches the plugin code or its build, every box of its description must be
ticked. The boxes come from the [pull request template](../.github/pull_request_template.md). An
item that does not apply is ticked with a word on why.

- **Code or build** means: `src/`, `gradle/`, `build.gradle.kts`, `settings.gradle.kts`,
  `gradle.properties`, `gradlew`, `gradlew.bat`. Anything else (docs, workflows) has nothing to
  tick: delete the Checklist section of the description.
- The changed files come from the GitHub API, not the title: renaming a pull request does not
  skip the check. A failed API call fails the check.
- Editing the description reruns the check, without rebuilding. Ticking a box is enough.
- Dependabot pull requests have no boxes: they pass.

## Release

To publish version `X.Y.Z`:

1. Set `version = "X.Y.Z"` in `build.gradle.kts`, in a pull request merged into `main`.
2. Tag that commit of `main` and push the tag:

   ```powershell
   git switch main; git pull
   git tag vX.Y.Z
   git push origin vX.Y.Z
   ```

The workflow refuses the release, with the reason in the run log, when:

- the tag is not on `main`;
- only documentation changed since the previous release (the version bump does not count);
- the tag does not match the version in `build.gradle.kts`.

It then builds the jar, as CI does, and publishes a release named after the tag and the targeted
Paper version, with:

- the jar and its SHA-256 checksum;
- a build provenance attestation, checked with
  `gh attestation verify BlockHunt-X.Y.Z.jar --repo oodinhoo/BlockHunt`;
- release notes generated from the merged pull requests.

Release tags cannot be moved or deleted (*Immutable release tags* ruleset, `refs/tags/v*`). A
mistake is fixed with a new version, never by retagging.

## CodeQL

Default setup, configured in *Settings → Code security*: no workflow file. It analyzes two
languages: `java-kotlin` for the plugin, and `actions` for the workflows themselves (script
injection, broad permissions, unpinned actions). Results show as the `CodeQL` check and in the
*Security* tab. Its `CodeQL` check reads *skipping* until both analyses finish.

## Dependabot

Once a week:

- **Gradle**: minor and patch versions in one grouped pull request, majors one by one.
  `paper-api` is never bumped: the Paper version is the Minecraft version the plugin targets,
  changed on purpose together with `api-version` in `paper-plugin.yml` and the README.
- **GitHub Actions**: new versions of the actions used in the workflows.

## Security choices

- Every action is pinned to a full commit SHA, with its version in a comment. Dependabot keeps
  them up to date: never replace a SHA with a tag.
- The default token is read-only. Only the release `publish` job can write, and it runs no build
  code: the Gradle build, which runs third-party plugins, happens in `jar` with a read-only token.
- The release checkout does not keep its credentials (`persist-credentials: false`).
- Checklist reads the pull request description from the event file, never pasted into the script:
  a description is user input.

## When it fails

| Symptom | Where to look |
|---|---|
| `build` fails on a test | the `test-reports` artifact of the run |
| `build` fails: "N test(s) skipped" | `test-reports`, XML results: the message names the MockBukkit method that is not implemented |
| `build` cancelled after 15 minutes | a hung test: run `.\gradlew.bat test` locally |
| `checklist` fails | the run log lists the unticked boxes: tick them in the description |
| `checklist` or `build` never start | the pull request has merge conflicts: GitHub runs no workflow until they are resolved |
| Release refused | the first failed step says why: tag not on `main`, docs only, or version mismatch |

## Keeping it consistent

Some settings are written in several places. Change one, change them all.

### The docs-only file list

These files do not change the plugin: `*.md`, `docs/`, `LICENSE` and `.github/ISSUE_TEMPLATE/`.
When a change touches nothing else, the workflows skip the work. The list is written three
times, once per case:

| Where | Syntax | When only these files changed |
|---|---|---|
| `ci.yml`, `paths-ignore` | `"**.md", "docs/**", ...` | a push to `main` does not start CI |
| `ci.yml`, job `changes` | `':!*.md' ':!docs' ...` | a pull request skips `build` |
| `release.yml`, step "Check code changed since the previous release" | `':!*.md' ':!docs' ...` | a release is refused: nothing to publish |

If one copy is forgotten, the workflows disagree. Say a `screenshots/` folder is added to
`paths-ignore` only: a push of screenshots to `main` no longer builds, but a pull request of
screenshots still runs the full build, and screenshots alone are enough to publish a release.

### The code file list

`pr-checklist.yml` lists the files that need the checklist ticked: `src/`, Gradle files. It is
the other way round from the list above: a new kind of code or build file must be added there,
or its pull requests merge without the checklist.

### The JDK version

JDK 25 is set in `build.gradle.kts` (toolchain), `ci.yml` and `release.yml` (`setup-java`).
