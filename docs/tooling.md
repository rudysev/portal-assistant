# Quality tooling — Portal-Assistant

## Android Lint

`check` runs Android Lint on the debug and release variants with `warningsAsErrors` and `abortOnError`, so any
warning fails it (and so pre-push and CI). There is no `lint-baseline.xml`: suppress inline only (`@SuppressLint`,
`tools:ignore`, `//noinspection`), each with a reason comment. The dependency-freshness checks
(`AndroidGradlePluginVersion`, `GradleDependency`, `NewerVersionAvailable`) are disabled, because they query the
network and fail whenever upstream ships a release. Report: `app/build/reports/lint-results-debug.html`.

## detekt

`check` also runs detekt 2.0.0-alpha.6 (`dev.detekt`; still alpha, because detekt 1.x doesn't support AGP 9;
quality-tooling ticket 08 moves it to stable) with type resolution: `detektMain` and `detektTest`, every variant. It
also runs compose-rules 0.6.8 (`io.nlopez.compose.rules:detekt`), which is built against one detekt alpha, so bump the
two together (`gradle/libs.versions.toml`). The config is detekt's defaults plus `config/detekt/detekt.yml`, identical
in portal-commons, portal-wake and portal-assistant (change all three together): the coroutine opt-ins
(`GlobalCoroutineUsage`, `InjectDispatcher`, `SleepInsteadOfDelay`, `SuspendFunSwallowedCancellation`), any
TODO/FIXME/STOPSHIP comment forbidden, `@Composable` exempt from `FunctionNaming`, and warnings as errors. There is no
baseline: suppress inline only (`@Suppress("RuleId")`), each with a reason comment. Lines are capped at 120: detekt's
default `MaxLineLength`, and `max_line_length = 120` in `.editorconfig` for ktlint. Reports:
`app/build/reports/detekt/`.

With KGP 2.4, a compiler warning (`allWarningsAsErrors`) fails the build with only "warnings found and -Werror
specified"; run the build with `--info` to see the warnings themselves.

## Hooks

`pre-commit` runs the `ktlint` and `detekt-cli` CLIs on
staged `.kt`/`.kts` files and `shellcheck` on staged shell scripts (`*.sh`, `*.command`, `.githooks/*`), and rejects on
findings (never auto-fixes; `brew install ktlint shellcheck`). For ktlint it refuses a CLI other than ktlint 1.8.0, the
version kotlinter 5.7.0 bundles (`gradle/libs.versions.toml`), so bump both together. detekt runs there without type
resolution (about a second); the type-resolved tasks and compose-rules run in `check`. The hook refuses a `detekt-cli`
other than the pinned `DETEKT_VERSION` (the Gradle plugin's version, so bump both together). Homebrew only ships detekt
1.x: install the CLI by unzipping
`https://github.com/detekt/detekt/releases/download/v2.0.0-alpha.6/detekt-cli-2.0.0-alpha.6.zip` and putting its `bin/`
on your `PATH`. `pre-push` runs `./gradlew check`. `--no-verify` is the deliberate escape hatch. ktlint config is
`.editorconfig` only (including the `@Composable` function-naming exemption).

## CI

`.github/workflows/ci.yml` runs every check on a pull request to `main`: the `build` job runs `./gradlew check
assembleDebug`, plus the `shellcheck` and `build-health` jobs below. A push to `main` (a merge), `workflow_dispatch`,
and a green `portal-commons` run (which dispatches `portal-commons-green`) run only the `build` job, as
`./gradlew assembleDebug test`. Branch protection on `main` requires a pull request with `shellcheck`,
`build-health` and `build` passing, so keep those job names. It checks out `portal-assistant` and
`portal-commons@main` side by side so that `includeBuild("../portal-commons")` resolves.

A `shellcheck` job lints every tracked shell script (the `provisioning/` installer that ships to end users,
`setup.sh`, `scripts/`, the hooks) with the runner's preinstalled shellcheck. shellcheck isn't part of
`./gradlew check`: the hook and this job cover it. `.shellcheckrc` makes it follow sourced files
(`config.env`, `keyprov.sh`) relative to each script, so a variable set in one file and read in another isn't
reported as unused. `.githooks/pre-commit` and `.shellcheckrc` are identical copies in portal-commons,
portal-wake and portal-assistant; change all of them together.

A separate `build-health` job runs `./gradlew buildHealth` (the `com.autonomousapps.dependency-analysis` plugin)
and fails on any dependency advice: unused, used-but-undeclared or wrongly-configured dependencies. It is not part
of `check` or the hooks, because the advice changes rarely and the analysis is slow. It analyses only this build's
`:app`; the included `../portal-commons` build has its own job. Run `./gradlew buildHealth` yourself after changing
dependencies; the report is at `build/reports/dependency-analysis/build-health-report.txt`.

