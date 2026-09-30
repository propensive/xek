#!/usr/bin/env bash
#
# Record a published xek release in this repository, as a draft pull request: its stub hashes in
# `etc/runners/<version>.tsv`, every asset's in `etc/runners/<version>.SHA256SUMS`, and the three
# resources the builder reads, `res/core/xek/runners.{tsv,version,url}` — so that adopting the
# release as the stubs `xek` and the packager build with is a data change, reviewed like any other.
#
# This is the `after` step named in etc/release, run by propensive/.github's release.sh once the
# release is public; it cannot fail the release. The hashes are those of the files just uploaded,
# in $RELEASE_ASSETS, whose digests release.sh has already checked against GitHub's.
#
# What is left to the maintainer is said in the pull request: the release's row in
# spec/COMPATIBILITY.md, which names the protocol and the daemon it speaks to.
#
# The pull request is opened with $PROPAGATE_TOKEN when there is one, since a pull request opened
# with a workflow's own GITHUB_TOKEN starts no workflows, so CI would not run on it; without it,
# the job's token opens it and CI starts at the first push to the branch.
#
# Environment (from release.sh): RELEASE_VERSION, RELEASE_TAG, RELEASE_ASSETS, RELEASE_REPO_NAME.

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

: "${RELEASE_VERSION:?set by release.sh}" "${RELEASE_TAG:?}" "${RELEASE_ASSETS:?}"
REPO=${RELEASE_REPO_NAME:-propensive/xek}
V=$RELEASE_VERSION
BRANCH="record/$RELEASE_TAG"

sha256() { { sha256sum "$1" 2>/dev/null || shasum -a 256 "$1"; } | cut -d' ' -f1; }

mkdir -p etc/runners
for f in "$RELEASE_ASSETS"/runner-*; do
  label=$(basename "$f"); label=${label#runner-}; label=${label%.exe}
  printf '%s\t%s\n' "$label" "$(sha256 "$f")"
done | sort > "etc/runners/$V.tsv"
cp "$RELEASE_ASSETS/$V.SHA256SUMS" "etc/runners/$V.SHA256SUMS"

cp "etc/runners/$V.tsv" res/core/xek/runners.tsv
printf '%s\n' "$V" > res/core/xek/runners.version
printf '%s\n' "https://github.com/$REPO/releases/download/$RELEASE_TAG" > res/core/xek/runners.url

git switch -q -c "$BRANCH"
git add "etc/runners/$V.tsv" "etc/runners/$V.SHA256SUMS" res/core/xek/runners.tsv \
  res/core/xek/runners.version res/core/xek/runners.url
BOT_EMAIL="41898282+github-actions[bot]@users.noreply.github.com"
git -c user.name="${GIT_AUTHOR_NAME:-github-actions[bot]}" \
    -c user.email="${GIT_AUTHOR_EMAIL:-$BOT_EMAIL}" \
    commit -q -m "Record the $RELEASE_TAG runner release" -m \
"Writes the hashes published in $RELEASE_TAG into etc/runners/$V.tsv and
etc/runners/$V.SHA256SUMS, and points the builder's resources at the release."
git push -q origin "$BRANCH"

BASE=$(gh repo view "$REPO" --json defaultBranchRef --jq .defaultBranchRef.name)
BODY=$(cat <<EOF
Records the $RELEASE_TAG runner release: the stub hashes in \`etc/runners/$V.tsv\`, every asset's in \`etc/runners/$V.SHA256SUMS\`, and the builder's resources pointed at the release, so \`xek\` and \`Runners.standard\` build with the new stubs.

<!-- Opened as a draft by the release of $RELEASE_TAG. Before marking it ready, add the release's row to spec/COMPATIBILITY.md — the protocol, the base signature it speaks, and the Soundness daemon release that speaks it — and rewrite the paragraph above if the release changes anything a user of the packager would notice. -->
EOF
)
GH_TOKEN=${PROPAGATE_TOKEN:-${GH_TOKEN:-${GITHUB_TOKEN:-}}} \
  gh pr create --repo "$REPO" --draft --base "$BASE" --head "$BRANCH" \
    --title "Record the $RELEASE_TAG runner release" --body "$BODY"
