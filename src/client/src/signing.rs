// What the signer and the verifier must agree on byte for byte: where the ETHRCFG v4 record's
// fields lie, and the statement an upgrade is signed over. Specified in spec/ethrcfg.md.
//
// This module is shared: the client compiles it as `crate::signing`, and `xek-sign`
// (src/sign/src/main.rs) includes the same file by path, so the two cannot drift apart. It
// therefore uses nothing from either crate, and in particular does not contain the magic, which
// a stub may not hold as a literal (see config.rs).

use ml_dsa::{EncodedSignature, EncodedVerifyingKey, MlDsa44, Signature, VerifyingKey,
             signature::Verifier};
use sha3::{Digest, Sha3_256};

pub const RECORD_LEN: usize          = 5108;
pub const MAGIC_LEN: usize           = 8;
pub const BUILD_ID_OFFSET: usize     = 8;
pub const FLAGS_OFFSET: usize        = 21;
pub const APP_ID_OFFSET: usize       = 32;
pub const APP_ID_LEN: usize          = 32;          // SHA3-256
pub const RELEASE_KEY_OFFSET: usize  = 64;
pub const RECOVERY_KEY_OFFSET: usize = 1376;
pub const PUBKEY_LEN: usize          = 1312;        // ML-DSA-44 |pk|
pub const SIGNATURE_OFFSET: usize    = 2688;
pub const SIGNATURE_LEN: usize       = 2420;        // ML-DSA-44 |sig|

pub const FLAG_DOWNGRADE_PERMITTED: u8 = 0x01;

pub const STATEMENT_PREFIX: [u8; 8] = *b"XEKSIGN\x04";
pub const STATEMENT_LEN: usize      = STATEMENT_PREFIX.len() + 32;

// The 40 bytes an upgrade is signed over: the prefix, then SHA3-256 of the whole file with the
// record's signature slot read as zeros. The file is hashed in place, in three runs, rather
// than copied with the slot cleared. `record` is the offset of the record within `file`, which
// must hold a whole record from there.
pub fn statement(file: &[u8], record: usize) -> [u8; STATEMENT_LEN] {
    let start = record + SIGNATURE_OFFSET;
    let end   = start + SIGNATURE_LEN;
    let mut hasher = Sha3_256::new();
    hasher.update(&file[..start]);
    hasher.update([0u8; SIGNATURE_LEN]);
    hasher.update(&file[end..]);
    let digest = hasher.finalize();

    let mut out = [0u8; STATEMENT_LEN];
    out[..STATEMENT_PREFIX.len()].copy_from_slice(&STATEMENT_PREFIX);
    out[STATEMENT_PREFIX.len()..].copy_from_slice(&digest);
    out
}

// SHA3-256 of an application's identifier, as the record's `app_id` holds it.
pub fn app_id(identifier: &str) -> [u8; APP_ID_LEN] {
    let mut out = [0u8; APP_ID_LEN];
    out.copy_from_slice(&Sha3_256::digest(identifier.as_bytes()));
    out
}

// Whether `signature` is a valid ML-DSA-44 signature (pure, with an empty context) over
// `statement` under `key`. A key or signature that does not decode does not verify.
pub fn verifies(statement: &[u8], signature: &[u8], key: &[u8; PUBKEY_LEN]) -> bool {
    let Ok(encoded_key) = EncodedVerifyingKey::<MlDsa44>::try_from(&key[..]) else { return false };
    let Ok(encoded_signature) = EncodedSignature::<MlDsa44>::try_from(signature) else { return false };
    let Some(signature) = Signature::<MlDsa44>::decode(&encoded_signature) else { return false };
    VerifyingKey::<MlDsa44>::decode(&encoded_key).verify(statement, &signature).is_ok()
}

pub fn is_zero(bytes: &[u8]) -> bool { bytes.iter().all(|&b| b == 0) }
