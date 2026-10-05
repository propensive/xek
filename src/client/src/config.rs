// The ETHRCFG v4 configuration record. Specified in spec/ethrcfg.md, which is what a builder
// outside this repository is written against.
//
// The record is NOT part of the stub. A builder turns a bare stub into an application's
// launcher by concatenation — `stub ‖ record ‖ jar` — and the client finds the record at
// startup by scanning its own executable forwards for the first occurrence of the magic. The
// stub therefore must contain the magic nowhere, which is why `magic()` reassembles it at run
// time from an obfuscated constant rather than holding it as a literal the compiler could
// place in `.rodata` or an instruction immediate. (In v2, where the record was a static inside
// the stub, exactly that happened on x86-64: the verifier's comparand was materialised as a
// `movabs` immediate ahead of the real record, and every builder patched the code instead.)
//
// Layout (5108 bytes total; the offsets are in `signing.rs`, shared with the signer):
//   [0..8]        magic: "ETHRCFG" + format version (4)
//   [8..16]       build_id     (u64 little-endian)
//   [16..18]      java_min     (u16 little-endian)
//   [18..20]      java_pref    (u16 little-endian)
//   [20]          bundle       (0 = jre, 1 = jdk)
//   [21]          flags        (bit 0 = downgrade_permitted, others reserved)
//   [22..32]      reserved     (0)
//   [32..64]      app_id       (SHA3-256 of the application's identifier; zero = unset)
//   [64..1376]    release_key  (ML-DSA-44 public key; zero = unset)
//   [1376..2688]  recovery_key (ML-DSA-44 public key; zero = none)
//   [2688..5108]  signature    (ML-DSA-44, over the statement in `signing.rs`; zero in an
//                 unsigned file)
//
// A stub run bare — no record appended — sees the compiled-in defaults below, which are also
// what a zero field means: Java 21 minimum, 24 preferred, a JRE, build id 0, and no keys,
// which disables self-upgrade.

use std::path::Path;
use std::sync::OnceLock;

pub use crate::signing::{BUILD_ID_OFFSET, MAGIC_LEN, RECORD_LEN};

use crate::verify::Keys;

pub const DEFAULT_JAVA_MIN: u16  = 21;
pub const DEFAULT_JAVA_PREF: u16 = 24;

// How far into its own file the client will look for a record before giving up and using the
// defaults. A bare stub is well under a megabyte, and the record immediately follows it; the
// bound only limits the cost of a mis-built file that carries no record at all.
const SCAN_LIMIT: u64 = 16 * 1024 * 1024;
const CHUNK: usize = 64 * 1024;

// `ETHRCFG\x04`, each byte XORed with `OBFUSCATION_KEY`. See the module comment.
const OBFUSCATION_KEY: u8 = 0x5A;
const MAGIC_OBFUSCATED: [u8; MAGIC_LEN] = [
    b'E' ^ OBFUSCATION_KEY, b'T' ^ OBFUSCATION_KEY, b'H' ^ OBFUSCATION_KEY, b'R' ^ OBFUSCATION_KEY,
    b'C' ^ OBFUSCATION_KEY, b'F' ^ OBFUSCATION_KEY, b'G' ^ OBFUSCATION_KEY, 4 ^ OBFUSCATION_KEY,
];

// The magic, reassembled at run time. `black_box` stops the optimiser from folding the XOR
// back into the plaintext constant.
#[inline(never)]
pub fn magic() -> [u8; MAGIC_LEN] {
    let key = core::hint::black_box(OBFUSCATION_KEY);
    let mut out = MAGIC_OBFUSCATED;
    for b in &mut out { *b ^= key; }
    out
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct BuildConfig {
    pub build_id:  u64,
    pub java_min:  u16,
    pub java_pref: u16,
    pub bundle:    &'static str,
    pub flags:     u8,
}

impl BuildConfig {
    pub const DEFAULT: BuildConfig = BuildConfig {
        build_id:  0,
        java_min:  DEFAULT_JAVA_MIN,
        java_pref: DEFAULT_JAVA_PREF,
        bundle:    "jre",
        flags:     0,
    };
}

// The record loaded by `load`, or absent when `load` was never called or found nothing.
static RECORD: OnceLock<Option<[u8; RECORD_LEN]>> = OnceLock::new();

// The offset of the first complete record in `bytes`: the first occurrence of the magic that
// has `RECORD_LEN` bytes from its start. A magic too close to the end is not a record.
pub fn find_record(bytes: &[u8]) -> Option<usize> {
    if bytes.len() < RECORD_LEN { return None; }
    let magic = magic();
    // The last start a record can have is `len - RECORD_LEN`, so the windows run to that plus
    // the magic's length.
    bytes[..bytes.len() - RECORD_LEN + MAGIC_LEN]
        .windows(MAGIC_LEN)
        .position(|window| window == magic)
}

pub fn parse(record: &[u8; RECORD_LEN]) -> BuildConfig {
    let build_id      = u64::from_le_bytes(record[BUILD_ID_OFFSET..BUILD_ID_OFFSET + 8].try_into().unwrap());
    let java_min_raw  = u16::from_le_bytes(record[16..18].try_into().unwrap());
    let java_pref_raw = u16::from_le_bytes(record[18..20].try_into().unwrap());
    BuildConfig {
        build_id,
        java_min:  if java_min_raw  == 0 { DEFAULT_JAVA_MIN }  else { java_min_raw },
        java_pref: if java_pref_raw == 0 { DEFAULT_JAVA_PREF } else { java_pref_raw },
        bundle:    if record[20] == 0 { "jre" } else { "jdk" },
        flags:     record[21],
    }
}

// Scan a file forwards for the first record, reading it in chunks that overlap by one byte
// less than the magic so that a magic straddling a chunk boundary is still seen. Returns the
// record, or `None` when the file has none within `SCAN_LIMIT`.
fn scan_file(path: &Path) -> Option<[u8; RECORD_LEN]> {
    use std::io::{Read, Seek, SeekFrom};

    let mut file = std::fs::File::open(path).ok()?;
    let magic = magic();
    let mut buffer = vec![0u8; CHUNK + MAGIC_LEN - 1];
    let mut carry = 0usize;    // bytes retained from the previous chunk, at the buffer's start
    let mut position: u64 = 0; // file offset of buffer[0]

    while position < SCAN_LIMIT {
        let read = file.read(&mut buffer[carry..]).ok()?;
        if read == 0 { return None; }
        let filled = carry + read;

        if let Some(hit) = buffer[..filled].windows(MAGIC_LEN).position(|w| w == magic) {
            let offset = position + hit as u64;
            let mut record = [0u8; RECORD_LEN];
            file.seek(SeekFrom::Start(offset)).ok()?;
            file.read_exact(&mut record).ok()?;
            return Some(record);
        }

        carry = MAGIC_LEN - 1;
        let keep_from = filled - carry;
        buffer.copy_within(keep_from..filled, 0);
        position += keep_from as u64;
    }

    None
}

// Load the record from the running executable. Called once at startup with the path the
// client was invoked as (which is also what the JVM is given as the JAR); later calls are
// no-ops. The functions below read whatever was loaded, or the defaults.
pub fn load(path: &Path) -> BuildConfig {
    let record = RECORD.get_or_init(|| {
        let found = scan_file(path);
        crate::debug!("config: record {} in {}", if found.is_some() { "found" } else { "absent" }, path.display());
        found
    });
    record.as_ref().map(parse).unwrap_or(BuildConfig::DEFAULT)
}

pub fn read_config() -> BuildConfig {
    RECORD.get().and_then(|r| r.as_ref()).map(parse).unwrap_or(BuildConfig::DEFAULT)
}

// The running binary's keys and application id, or zeros when it has no record. Owned, since
// they are only 2.6 kB and the caller holds them across the whole of an upgrade check.
pub fn keys() -> Keys {
    RECORD.get().and_then(|r| r.as_ref()).map(|record| Keys::of(record)).unwrap_or(Keys::NONE)
}

// Whether this binary can upgrade itself at all: it has both a release key and an application
// id. Rule 1 of the verification rule in spec/ethrcfg.md, and `ethereal.upgradable` to the daemon.
pub fn upgradable() -> bool {
    keys().upgradable()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::signing::{APP_ID_LEN, APP_ID_OFFSET, PUBKEY_LEN, RECOVERY_KEY_OFFSET, RELEASE_KEY_OFFSET};
    use std::io::Write;

    fn record(build_id: u64, java_min: u16, java_pref: u16, jdk: bool, flags: u8) -> [u8; RECORD_LEN] {
        let mut r = [0u8; RECORD_LEN];
        r[..MAGIC_LEN].copy_from_slice(&magic());
        r[8..16].copy_from_slice(&build_id.to_le_bytes());
        r[16..18].copy_from_slice(&java_min.to_le_bytes());
        r[18..20].copy_from_slice(&java_pref.to_le_bytes());
        r[20] = if jdk { 1 } else { 0 };
        r[21] = flags;
        r
    }

    fn temp_file(name: &str, bytes: &[u8]) -> std::path::PathBuf {
        let path = std::env::temp_dir().join(format!("xek-config-test-{}-{}", std::process::id(), name));
        std::fs::File::create(&path).unwrap().write_all(bytes).unwrap();
        path
    }

    #[test]
    fn magic_is_ethrcfg_v4() {
        assert_eq!(&magic(), b"ETHRCFG\x04");
    }

    #[test]
    fn finds_record_after_prefix_and_ignores_decoy_in_jar() {
        let mut bytes = vec![0xAAu8; 1000];
        bytes.extend_from_slice(&record(42, 17, 21, true, 1));
        let mut jar = vec![0x55u8; 500];
        jar.extend_from_slice(&magic());          // a decoy inside the "jar"
        jar.extend_from_slice(&[0u8; RECORD_LEN]);
        bytes.extend_from_slice(&jar);
        assert_eq!(find_record(&bytes), Some(1000));
    }

    #[test]
    fn record_at_the_very_end_is_found() {
        let mut bytes = vec![0u8; 100];
        bytes.extend_from_slice(&record(1, 0, 0, false, 0));
        assert_eq!(find_record(&bytes), Some(100));
        assert_eq!(find_record(&bytes[..bytes.len() - 1]), None);
    }

    #[test]
    fn magic_too_close_to_end_is_not_a_record() {
        let mut bytes = vec![0u8; 100];
        bytes.extend_from_slice(&magic());
        bytes.extend_from_slice(&[0u8; 100]);
        assert_eq!(find_record(&bytes), None);
    }

    #[test]
    fn fields_round_trip_and_zero_means_default() {
        let parsed = parse(&record(7, 17, 21, true, 1));
        assert_eq!(parsed, BuildConfig { build_id: 7, java_min: 17, java_pref: 21, bundle: "jdk", flags: 1 });
        let defaults = parse(&record(0, 0, 0, false, 0));
        assert_eq!(defaults, BuildConfig::DEFAULT);
    }

    #[test]
    fn scan_finds_record_straddling_a_chunk_boundary() {
        // Place the magic three bytes before the first chunk ends.
        let mut bytes = vec![0x11u8; CHUNK - 3];
        bytes.extend_from_slice(&record(99, 0, 0, false, 0));
        bytes.extend_from_slice(&[0x22u8; 4096]);
        let path = temp_file("straddle", &bytes);
        let found = scan_file(&path).expect("record");
        assert_eq!(parse(&found).build_id, 99);
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn scan_without_record_gives_nothing() {
        let path = temp_file("bare", &vec![0x33u8; 3 * CHUNK + 17]);
        assert!(scan_file(&path).is_none());
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn scan_beyond_limit_gives_nothing() {
        let mut bytes = vec![0u8; SCAN_LIMIT as usize + 10];
        let start = SCAN_LIMIT as usize + 1;
        bytes.extend_from_slice(&record(5, 0, 0, false, 0));
        bytes[start..start + MAGIC_LEN].copy_from_slice(&magic());
        let path = temp_file("beyond", &bytes);
        assert!(scan_file(&path).is_none());
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn scan_reads_the_keys_and_application() {
        let mut r = record(1, 0, 0, false, 0);
        r[APP_ID_OFFSET..APP_ID_OFFSET + APP_ID_LEN].fill(9);
        for (i, b) in r[RELEASE_KEY_OFFSET..RELEASE_KEY_OFFSET + PUBKEY_LEN].iter_mut().enumerate() {
            *b = (i % 251) as u8;
        }
        r[RECOVERY_KEY_OFFSET..RECOVERY_KEY_OFFSET + PUBKEY_LEN].fill(3);
        let mut bytes = vec![0x44u8; 777];
        bytes.extend_from_slice(&r);
        let path = temp_file("keys", &bytes);
        let keys = Keys::of(&scan_file(&path).expect("record"));
        assert_eq!(keys.app_id, [9u8; APP_ID_LEN]);
        assert_eq!(&keys.release_key[..8], &[0, 1, 2, 3, 4, 5, 6, 7]);
        assert_eq!(keys.recovery_key, [3u8; PUBKEY_LEN]);
        assert!(keys.upgradable());
        let _ = std::fs::remove_file(path);
    }

    // The record `xek.Record` writes for the fields below, committed in spec/fixtures and
    // checked byte for byte by the Scala suite: the client must read back what the builder wrote.
    #[test]
    fn reads_the_record_the_builder_writes() {
        let hex: String = include_str!("../../../spec/fixtures/ethrcfg-v4.hex")
            .chars().filter(|c| !c.is_whitespace()).collect();
        assert_eq!(hex.len(), 2 * RECORD_LEN);
        let mut r = [0u8; RECORD_LEN];
        for (i, b) in r.iter_mut().enumerate() {
            *b = u8::from_str_radix(&hex[2 * i..2 * i + 2], 16).unwrap();
        }
        assert_eq!(find_record(&r), Some(0));
        assert_eq!(parse(&r), BuildConfig {
            build_id: 0x0102030405060708, java_min: 21, java_pref: 25, bundle: "jdk", flags: 1
        });
        let keys = Keys::of(&r);
        assert_eq!(keys.app_id, crate::signing::app_id("propensive/fume"));
        for i in 0..PUBKEY_LEN {
            assert_eq!(keys.release_key[i], (i % 251) as u8);
            assert_eq!(keys.recovery_key[i], ((i * 7) % 251) as u8);
        }
        assert!(crate::signing::is_zero(&r[22..32]));
        assert!(crate::signing::is_zero(&r[crate::signing::SIGNATURE_OFFSET..]));
    }

    #[test]
    fn unloaded_client_reports_defaults_and_no_key() {
        // `RECORD` may or may not have been initialised by another test in this process; only
        // assert what holds either way for a record-free process.
        if RECORD.get().is_none() {
            assert_eq!(read_config(), BuildConfig::DEFAULT);
            assert!(!upgradable());
        }
    }
}
