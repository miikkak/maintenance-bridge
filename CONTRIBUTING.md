# Contributing to maintenance-bridge

Thank you for considering contributing to this project! We welcome contributions from the
community.

## Getting Started

1. Fork the repository
2. Clone your fork locally
3. Create a new branch for your changes
4. Make your changes
5. Test your changes
6. Submit a pull request

## Forking this Repository

If you're creating your own fork of this project, you'll need to update repository-specific
references:

### Files to Update

1. **`renovate.json`**
   - Update the `extends` entries pointing at `github>miikkak/renovate-config` to your own
     Renovate config, or drop them if you don't use Renovate

2. **`SECURITY.md`**
   - Update the GitHub Security Advisories URL to point to your fork

3. **`README.md`**
   - Update the `gh release download vX.Y.Z -R miikkak/maintenance-bridge ...` command under
     "Testing a release build" to point to your fork

This list isn't exhaustive - grep for `miikkak` across the repo to catch anything else hardcoded
to the canonical repository, then update it to point to your fork's location.

## Development Requirements

- A supported bootstrap JVM to launch the Gradle wrapper (`./gradlew`) - JDK 25 itself is
  provisioned automatically via the Gradle toolchain (Foojay resolver) and doesn't need to be
  installed separately, but Gradle needs *some* JVM already present to start up in the first
  place
- Git
- Pre-commit hooks

## Code Quality Standards

This project maintains high code quality standards using automated tooling:

### Pre-commit Hooks

All commits must pass pre-commit hooks. Install the `pre-commit` tool itself first (see
[pre-commit.com's installation guide](https://pre-commit.com/#installation) for other methods,
e.g. your OS package manager):

```bash
pip install pre-commit
```

Then register the hooks for this repo:

```bash
pre-commit install
```

The hooks cover formatting and basic file hygiene (trailing whitespace, line endings, valid
YAML/JSON, no accidentally-committed large files).

### Commit Message Format

We use [Conventional Commits](https://www.conventionalcommits.org/) — this isn't enforced by a
bot, but `semantic-release` (triggered by the PR's release label, not commit message parsing)
relies on the convention for consistency. Your commit messages should follow this format:

```text
<type>(<scope>): <description>

[optional body]

[optional footer]
```

Types: `feat`, `fix`, `docs`, `chore`, `refactor`, `test`, `ci`

Examples:

- `feat: add per-server breakdown to the output file`
- `fix: correct a race in the write-if-changed path`
- `docs: update README with the output file schema`
- `chore(deps): update velocity-api to latest`

### Code Style

- Standard Java conventions, enforced structurally rather than by a formatter: package-private
  where possible, `final` fields/classes by default, and comments that explain *why* a decision
  was made (not what the code already says)
- Keep classes focused - this project favors several small, single-purpose classes over one large
  one
- Match the existing Javadoc style: a short description plus a `<p>` paragraph for anything
  non-obvious

## Branch Workflow

- **Never commit directly to `main`**
- Create feature branches from `main`
- Name branches descriptively (e.g., `feat/per-server-breakdown`, `fix/write-race`)
- All work must be submitted via Pull Request
- PRs require passing CI/CD checks

## Testing

Before submitting a PR:

```bash
./gradlew test
```

Tests use JUnit 6 (and Mockito where a Velocity API object needs mocking). New behavior should
come with a test; bug fixes should come with a test that fails before the fix and passes after.

## CI/CD Pipeline

All PRs trigger automated CI/CD that includes:

- Pre-commit checks (file hygiene, formatting)
- Gradle build and test suite
- Dependency-lockfile-aware vulnerability scanning (Trivy)
- AI code review

Your PR must pass all checks before it can be merged.

## Pull Request Process

1. Ensure your branch is up-to-date with `main`
2. Push your branch to your fork
3. Open a Pull Request against `main`
4. Fill in the PR template with:
   - Clear description of changes
   - Link to any related issues
   - Test plan
5. Wait for CI/CD checks to complete
6. Address any review feedback
7. Once approved and checks pass, a maintainer will merge your PR

## What to Contribute

We welcome contributions in these areas:

- Bug fixes
- Feature enhancements
- Documentation improvements
- Test coverage improvements
- Performance optimizations
- Security improvements

Please open an issue first if you're planning a major change to discuss the approach.

## Getting Help

- Open an issue for bug reports, feature requests, or questions
- Check existing issues and PRs to avoid duplicates

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
