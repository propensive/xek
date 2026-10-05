# The `ETHRCFG` configuration record

An XEK executable is three files joined end to end:

```
stub ‖ record ‖ jar
```

The *stub* is a bare, generic client for one platform, published as-is and never modified. The
*record* is a fixed 5108-byte block, written by the builder, that configures the client for
one application: the build id that orders upgrades, the Java version policy, the application's
identity, and the keys upgrades are verified against. The *jar* is the application, unmodified.

Building an executable is therefore concatenation. Nothing inside the stub is patched, so the
stub's own code signature (macOS) stays valid, and a macOS executable can be built on any host
without a signing tool.

## Finding the record

The client reads its own executable — the path it was invoked as, which is also the path it
hands the JVM as the JAR — forwards from byte 0, and takes the first occurrence of the 8-byte
magic `ETHRCFG\x04` as the start of the record, provided 5108 bytes remain from there. A
verifier checking an upgrade does exactly the same on the candidate file.

Because the record precedes the JAR, "first occurrence" is the record whatever the JAR
contains. That rests on one invariant: **a stub contains the magic nowhere**. The client
reassembles the magic at run time from an obfuscated constant so that the compiler cannot
place a literal copy in the binary, and the stub build (`etc/ci/client-build.sh`) refuses to
publish a stub in which the bytes `ETHRCFG` occur at all.

A client that finds no record — a bare stub run directly, or a mis-built file — uses the
defaults in the table below, under which self-upgrade is disabled.

## Layout

All integers are little-endian.

| Offset | Length | Field | Meaning |
|---|---|---|---|
| 0 | 8 | magic | `ETHRCFG` followed by the format version byte, currently `4` |
| 8 | 8 | `build_id` | `u64`; orders upgrades — a `.pending` binary is accepted only if its build id is higher (unless downgrades are permitted). Default `0` |
| 16 | 2 | `java_min` | `u16`; the minimum acceptable JVM major version. `0` means "unset", read as 21 |
| 18 | 2 | `java_pref` | `u16`; the preferred JVM major version to download when none is found. `0` means "unset", read as 24 |
| 20 | 1 | `bundle` | `0` = JRE, `1` = JDK — which runtime to fetch if one must be fetched |
| 21 | 1 | `flags` | bit 0 = downgrade permitted; all other bits reserved and zero |
| 22 | 10 | reserved | zero |
| 32 | 32 | `app_id` | SHA3-256 of the application's identifier, as UTF-8 — for example of `propensive/fume`. All-zero is "unset" |
| 64 | 1312 | `release_key` | The ML-DSA-44 public key ordinary releases are signed with. All-zero is "unset" |
| 1376 | 1312 | `recovery_key` | A second ML-DSA-44 public key, whose seed is kept offline. All-zero is "none" |
| 2688 | 2420 | `signature` | One ML-DSA-44 signature over the file's statement (below); zero in an unsigned file |

Total: 5108 bytes. Without both a `release_key` and an `app_id` a client accepts no upgrade, which
is the safe default.

## Building an executable

1. Obtain the bare stub for the target platform.
2. Write a record: the magic, the fields above, the application id and keys (or zeros), and
   2420 zero bytes of signature.
3. Concatenate stub, record and JAR, in that order, and mark the result executable (not on
   Windows, where the name's `.exe` suffix does that).
4. If the JAR has a ZIP64 end-of-central-directory locator — the 20-byte block starting
   `PK\x06\x07` immediately before the end-of-central-directory record — add
   `size(stub) + 5108` to the `u64` at offset 8 within the locator. That is the one physical
   offset in a ZIP; every other offset is relative and a reader recovers the shift by itself.
   Left stale, the JVM refuses to open the JAR at all.

That is all. In a POSIX shell the whole of step 3 is `cat stub record app.jar > mytool`, and in
`cmd.exe` it is `copy /b stub+record+app.jar mytool.exe`. The reference builder is the `xek`
command published with every client release (`src/core`, behind `src/cli`), which does steps 1–4
and generates the polyglot launcher scripts.

### Why nothing is signed

A Mach-O's ad-hoc signature covers the pages of the Mach-O image up to the signature blob.
Bytes appended after the image are neither mapped nor hashed, so appending leaves the
signature valid — which is why the stubs are signed once, when published, and never again.
Windows PE loaders likewise ignore trailing data. Authenticode is not used; if it ever were,
it would have to be applied to the finished executable rather than the stub.

The JAR is found at run time by scanning back from the end of the file for the ZIP end-of-
central-directory record, so nothing records where the record ends and the JAR begins.

## Signing a release

### What is signed

The signature is over a 40-byte *statement*:

```
"XEKSIGN\x04" ‖ SHA3-256(the whole file, with the 2420-byte signature slot zeroed)
```

It is a pure ML-DSA-44 signature (FIPS 204, with an empty context string) over those 40 bytes.
The hash covers every byte of the file — the stub, the JAR, and every field of the record
including `build_id`, `flags`, `app_id` and both keys — so a change to any of them invalidates
the signature. Signing a short statement rather than the file means a signer that never sees
the file — a KMS, a hardware token, an offline machine — can produce the signature, and the
client can hash a candidate where it lies rather than copy it.

The downgrade flag is set by the signer, as part of what is signed, so that permission to
downgrade is granted per release, by whoever holds the key.

### The verification rule

A `.pending` candidate is accepted only if all of these hold, checked in this order:

1. the running executable has a non-zero `release_key` **and** a non-zero `app_id` (otherwise
   upgrades are disabled);
2. the candidate contains a v4 record;
3. the candidate's `app_id` equals the running executable's;
4. the signature verifies over the candidate's statement under the running executable's
   `release_key`, or, if that fails and the running executable's `recovery_key` is non-zero,
   under its `recovery_key`;
5. the candidate's `build_id` is higher than the running one, unless the candidate's (signed)
   downgrade flag is set.

What the launcher does with the outcome, and how it reports it, is in
[`layout.md`](layout.md).

### Rotating keys

The keys used are always those of the *running* executable; the keys a candidate carries are
consulted only once it is running, to check *its* successor. Rotation therefore works as a
chain:

- A release signed with release key A may carry key B as its `release_key`. Once it is
  installed, releases are signed with B, and A is retired.
- A release signed with the recovery key may carry a new `release_key`, a new `recovery_key`,
  or both. This is the way back from a release key that is lost or leaked: the recovery seed,
  kept offline, signs one release carrying a new release key.
- An executable that skipped the release which introduced a key cannot verify a release signed
  with it, so a release that rotates a key should stay available until its users have moved on.

### Signing with `xek`

The `xek` command signs releases, with the subcommands below. ML-DSA is the JDK's, so these need
Java 24 or later; `xek build` does not. A key is kept as its 32-byte FIPS 204 key generation seed
and its 1312-byte raw public key, the form the record holds. Where a subcommand takes the seed, it
is `--key <seed-file>`, or `--key-env <variable>` naming an environment variable holding it as 64
hexadecimal digits, so that a CI secret need never be written to disk. The seed itself is never
accepted as an argument.

- `xek keygen --out <prefix>` writes `<prefix>.seed`, mode `0600`, and `<prefix>.pub`, the public
  key a builder writes into the record. It refuses to overwrite either.
- `xek public-key (--key <file> | --key-env <variable>) --out <file>` derives the public key from
  a seed, so that one committed to a repository can be regenerated and checked.
- `xek sign (--key <file> | --key-env <variable>) --in <executable> --out <signed>
  [--allow-downgrade] [--foreign-key]` sets the flags byte, signs the statement, and writes the
  signature into the slot. It says whether the key is the record's `release_key` or its
  `recovery_key`, and refuses a key that is neither unless `--foreign-key` is given — which is
  what the release after a rotation needs, and otherwise almost always a mistake.
- `xek statement --in <executable> [--allow-downgrade]` sets the flags byte as `sign` would and
  prints the 40-byte statement in hexadecimal: what an external signer is asked to sign.
- `xek attach --in <executable> --signature <file> --out <signed> [--allow-downgrade]` writes a
  signature made elsewhere, over that statement, into the slot.
- `xek verify --public-key <file> [--app-id <identifier>] --in <executable>` exits with status 0
  only if the signature verifies under that key (and the `app_id` matches, if given), and prints
  the build id: the gate a release script runs on every executable before publishing it. A
  signature or application that does not match exits with status 4.

## Changing the layout

Any change to the field layout or to the placement rule increments the version byte in the
magic (`ETHRCFG\x05`, next), which makes every existing builder, client and verifier fail to find
its magic in a file of the other version. Adding a meaning to a reserved byte, where zero
keeps the old behaviour, does not.

Version 2, used by releases up to `runners-0.5`, held the record as a static inside the stub
that a builder byte-patched in place. A v2 client never finds a v3 record and a v3 client
never finds a v2 record, so the two cannot upgrade into each other; see
[`COMPATIBILITY.md`](COMPATIBILITY.md).

Version 3, used by releases up to `1.0.0`, was 3764 bytes, with a single `public_key` at offset
32 and the signature at 1344, made over the whole file rather than over a statement. Version 4
adds `app_id`, without which an executable signed for one application was a valid upgrade for
any other signed with the same key, and `recovery_key`, without which a lost or leaked key could
never be replaced. No v3 executable was published with a key, so no upgrade channel was lost.

## Implementations

- `src/client/src/config.rs` — the reader, in the stub.
- `src/client/src/signing.rs` — the offsets and the statement, as the verifier reads them.
- `src/client/src/verify.rs` — the verifier, and `src/client/src/update.rs`, which applies it.
- `src/core/xek.Signer.scala` — the signer, behind `xek keygen`, `sign` and the rest. The client's
  tests and the `xek` suite check the two against the same vectors, in `spec/fixtures`.
- `src/core` — the builder: `xek.Record` writes the record and `xek.Assembler` the executable,
  for the `xek` command (`src/cli`) published with each release, and for `xek.Packager`.
