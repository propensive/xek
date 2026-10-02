# XEK — see README.md
#
# The Scala modules are built by Mill; the client stubs are built by Cargo. The two are
# deliberately separate: Mill never compiles Rust, and the stubs are released on their own
# cadence, by tagging (`git tag -s X.Y.Z && git push --tags`), rather than with the jars.

MILL = ./mill

.PHONY: check build test cargo-test client-build client-fetch client-release xek publishLocal sync-deps tools e2e clean

# Everything published from this repository. `example` is deliberately excluded: it is the
# end-to-end fixture, and the only module that depends on a daemon implementation.
build:
	$(MILL) xek.all

# Suites carry no `main`; a host client discovers them from the `META-INF/services/probably.Suite`
# index and drives them over the test-event protocol. `$(TESTS)` are fume selection terms.
test:
	$(MILL) xek.test.assembly
	fume run -c out/xek/test/assembly.dest/out.jar $(TESTS)

# The client's own unit tests — the BinTEL codec, the ETHRCFG verifier, the state machine.
cargo-test:
	cargo test

# Cross-compile the five reusable stubs into dist/client. Needs cargo with the zigbuild
# subcommand (`cargo install cargo-zigbuild`) and zig on the path.
client-build:
	./etc/ci/client-build.sh

# Download the stubs of a published release into dist/client, verified against the committed
# manifest — the path to take when the Rust toolchain isn't available.
client-fetch:
	@if [ -z "$(RUNNERS_VERSION)" ]; then echo "Usage: make client-fetch RUNNERS_VERSION=X [REPO=owner/repo]" >&2; exit 1; fi
	./etc/ci/client-fetch.sh "$(RUNNERS_VERSION)" "$(REPO)"

# Releases are cut by tagging, not by make. The tag fires .github/workflows/release.yml, which
# runs the shared release.sh in propensive/.github: it gates on a signed tag and on CI already
# being green on that commit, builds the stubs and the `xek` command (etc/ci/client-assemble.sh),
# publishes them, and then opens pull requests recording the hashes here and pinning the release
# in Soundness. If anything fails, the release and the tag are both deleted. See etc/release. To
# rehearse without publishing: RELEASE_DRY_RUN=1 ./etc/shared release.sh X.Y.Z
client-release:
	@echo "Releases are triggered by tags, not by make:" >&2
	@echo "" >&2
	@echo "    git tag -s X.Y.Z && git push --tags" >&2
	@echo "" >&2
	@echo "See propensive/.github." >&2
	@exit 1

# Install the Soundness release pinned in etc/refs into ~/.ivy2/local, as CI does.
sync-deps:
	./etc/shared sync-deps.sh

# Check every source against Consequent Style and the project's own rules with flair (the
# release pinned in etc/tools; `make tools` installs it), as configured in
# .pyrocosm/flair/config.tel. Findings are warnings and the count is not yet zero, so CI does
# not run this; PATHS restricts the check to files beneath them.
check:
	flair check $(PATHS)

# Install the commands pinned in etc/tools (fume) through their releases' installers.
tools:
	./etc/shared tools.sh

# Build the `xek` command, as dist/xek (and dist/xek.cmd, the same bytes): a polyglot file for
# every platform, built by `xek` itself from its own JAR, around the stubs in dist/client if
# they are there and the published release's otherwise. `make e2e` depends on this.
xek:
	$(MILL) xek.cli.executable

# Install the jars into ~/.ivy2/local, where coursier finds them with no repository
# configuration — how a downstream build consumes XEK before it has a published home.
publishLocal:
	$(MILL) xek.core.publishLocal
	$(MILL) xek.packager.publishLocal
	$(MILL) xek.toolchain.publishLocal
	$(MILL) xek.cli.publishLocal

# The end-to-end check: package the example application around a real client stub and run it.
# Needs dist/client (from `client-build` or `client-fetch`), and resolves a daemon
# implementation — the one place anything here does.
#
# E2E_DAEMON=legacy says the daemon the pinned Soundness release provides predates this
# client's protocol base, so the only thing to check is that the pair refuses each other
# legibly. Drop it when etc/refs moves to a Soundness release whose daemon speaks the base
# in spec/ethereal-launcher.tel.
e2e: xek
	E2E_DAEMON=legacy ./etc/ci/e2e.sh

clean:
	$(MILL) clean
	rm -rf dist target
