# Contributing

Thanks for helping. This page says how to get a change into BlockHunt.

## Before you start

- Bugs and ideas go through the [issue forms](https://github.com/oodinhoo/BlockHunt/issues/new/choose).
  For a sizeable change, open an issue first so we agree on the approach before you write code.
- Security problems never go in a public issue: see [SECURITY.md](SECURITY.md).

## Build and test

Requires **JDK 25**.

```powershell
.\gradlew.bat build      # Windows
./gradlew build          # Linux, macOS
```

The build must pass with no compiler warning, and runs the unit tests (JUnit and MockBukkit). A test
that reaches a method MockBukkit does not implement is reported as skipped: the build fails on any
skipped test, since it proves nothing.

`.\gradlew.bat runServer` starts a local **Paper 26.2** server with the plugin. Try your change in
game, with at least two players for a game, and say in the pull request how you checked it.

## Code

- Match the surrounding code: Java 25, one flat package `fr.blockhunt`, same naming and comment style.
- A deliberate shortcut with a known limit gets a `ponytail:` comment that names the limit and the
  way out.
- When behavior changes, update the [README](README.md) in the same pull request.

## Commits and pull requests

- Branch from `main`, one topic per pull request.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/): `feat:`,
  `fix:`, `refactor:`, `perf:`, `test:`, `docs:`, `build:`, `chore:`, then a short summary in the
  imperative.
- The CI must pass. A pull request that touches the plugin code or its build (`src/`, Gradle files)
  also needs every box of its checklist ticked: an item that does not apply is ticked with a word
  on why. Pull requests are squash-merged: the title becomes the commit on `main`. What each check
  does, and what to do when one fails: [continuous integration](docs/ci.md).

By contributing, you agree that your contribution is licensed under the [MIT License](LICENSE) and
that you follow the [Code of Conduct](CODE_OF_CONDUCT.md).
