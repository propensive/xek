#!/usr/bin/env bash
#
# Record a published xek release in this repository, as a draft pull request: its stub hashes in
# `etc/client/<version>.tsv`, every asset's in `etc/client/<version>.SHA256SUMS`, and the three
# resources the builder reads, `res/core/xek/client.{tsv,version,url}` — so that adopting the
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

mkdir -p etc/client
for f in "$RELEASE_ASSETS"/client-*; do
  label=$(basename "$f"); label=${label#client-}; label=${label%.exe}
  printf '%s\t%s\n' "$label" "$(sha256 "$f")"
done | sort > "etc/client/$V.tsv"
cp "$RELEASE_ASSETS/$V.SHA256SUMS" "etc/client/$V.SHA256SUMS"

cp "etc/client/$V.tsv" res/core/xek/client.tsv
printf '%s\n' "$V" > res/core/xek/client.version
printf '%s\n' "https://github.com/$REPO/releases/download/$RELEASE_TAG" > res/core/xek/client.url

# The commit is made through the GraphQL `createCommitOnBranch` mutation rather than with `git`:
# GitHub signs a commit made that way, and `main` here accepts only verified commits, which a
# `git commit` by the release job's bot cannot be. The branch is created first, at the released
# commit, and deleted again if the commit fails.
export GH_TOKEN=${PROPAGATE_TOKEN:-${GH_TOKEN:-${GITHUB_TOKEN:-}}}
FILES=("etc/client/$V.tsv" "etc/client/$V.SHA256SUMS" res/core/xek/client.tsv
  res/core/xek/client.version res/core/xek/client.url)
HEAD_SHA=$(git rev-parse HEAD)

ADDITIONS=$(for f in "${FILES[@]}"; do
  jq -n --arg path "$f" --arg contents "$(base64 < "$f" | tr -d '\n')" \
    '{path: $path, contents: $contents}'
done | jq -s .)

HEADLINE="Record the $RELEASE_TAG client release"
MESSAGE="Writes the hashes published in $RELEASE_TAG into etc/client/$V.tsv and
etc/client/$V.SHA256SUMS, and points the builder's resources at the release."

gh api -X POST "repos/$REPO/git/refs" -f ref="refs/heads/$BRANCH" -f sha="$HEAD_SHA" >/dev/null
jq -n --arg repo "$REPO" --arg branch "$BRANCH" --arg head "$HEAD_SHA" \
  --arg headline "$HEADLINE" --arg body "$MESSAGE" --argjson additions "$ADDITIONS" \
  '{query: "mutation($input: CreateCommitOnBranchInput!) { createCommitOnBranch(input: $input) { commit { oid } } }",
    variables: {input: {branch: {repositoryNameWithOwner: $repo, branchName: $branch},
                        expectedHeadOid: $head, message: {headline: $headline, body: $body},
                        fileChanges: {additions: $additions}}}}' |
  gh api graphql --input - --jq '.data.createCommitOnBranch.commit.oid' ||
  { gh api -X DELETE "repos/$REPO/git/refs/heads/$BRANCH" >/dev/null 2>&1
    echo "client-record: committing to $BRANCH failed" >&2; exit 1; }

BASE=$(gh repo view "$REPO" --json defaultBranchRef --jq .defaultBranchRef.name)
BODY=$(cat <<EOF
Records the $RELEASE_TAG client release: the stub hashes in \`etc/client/$V.tsv\`, every asset's in \`etc/client/$V.SHA256SUMS\`, and the builder's resources pointed at the release, so \`xek\` and \`Client.standard\` build with the new stubs.

<!-- Opened as a draft by the release of $RELEASE_TAG. Before marking it ready, add the release's row to spec/COMPATIBILITY.md — the protocol, the base signature it speaks, and the Soundness daemon release that speaks it — and rewrite the paragraph above if the release changes anything a user of the packager would notice. -->
EOF
)
gh pr create --repo "$REPO" --draft --base "$BASE" --head "$BRANCH" \
    --title "Record the $RELEASE_TAG client release" --body "$BODY"
