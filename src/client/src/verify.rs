use crate::config::find_record;
use crate::signing::{APP_ID_LEN, APP_ID_OFFSET, BUILD_ID_OFFSET, FLAGS_OFFSET, PUBKEY_LEN, RECORD_LEN,
                     RECOVERY_KEY_OFFSET, RELEASE_KEY_OFFSET, SIGNATURE_LEN, SIGNATURE_OFFSET, is_zero,
                     statement, verifies};

#[derive(Debug, PartialEq, Eq)]
pub enum VerifyError {
    PublicKeyUnset,
    ApplicationUnset,
    MagicMissing,
    WrongApplication,
    SignatureMismatch,
}

#[derive(Debug)]
pub struct VerifiedBinary {
    pub build_id: u64,
    pub flags:    u8,
}

// What a binary's record says about who may upgrade it: the application it is, and the keys
// an upgrade must be signed with.
#[derive(Clone)]
pub struct Keys {
    pub app_id:       [u8; APP_ID_LEN],
    pub release_key:  [u8; PUBKEY_LEN],
    pub recovery_key: [u8; PUBKEY_LEN],
}

impl Keys {
    pub const NONE: Keys = Keys {
        app_id:       [0; APP_ID_LEN],
        release_key:  [0; PUBKEY_LEN],
        recovery_key: [0; PUBKEY_LEN],
    };

    pub fn of(record: &[u8]) -> Keys {
        let mut keys = Keys::NONE;
        keys.app_id.copy_from_slice(&record[APP_ID_OFFSET..APP_ID_OFFSET + APP_ID_LEN]);
        keys.release_key.copy_from_slice(&record[RELEASE_KEY_OFFSET..RELEASE_KEY_OFFSET + PUBKEY_LEN]);
        keys.recovery_key.copy_from_slice(&record[RECOVERY_KEY_OFFSET..RECOVERY_KEY_OFFSET + PUBKEY_LEN]);
        keys
    }

    // Rule 1: without both a release key and an application id, nothing is ever accepted.
    pub fn upgradable(&self) -> bool {
        !is_zero(&self.release_key) && !is_zero(&self.app_id)
    }
}

// Verify a candidate upgrade binary against the *running* binary's keys, by the rule in
// spec/ethrcfg.md, checked in this order:
//
//   1. the running binary has a release key and an application id;
//   2. the candidate has a v4 record — the first magic, which is the record a builder
//      appended after the stub (a stub itself contains no magic);
//   3. the candidate is for the same application;
//   4. its signature verifies over its statement under the running release key or, failing
//      that, under the running recovery key, if there is one.
//
// The keys the candidate itself carries are never consulted: they are what *its* successors
// will be checked against. Rule 5, that the build id advances, is the caller's, which is given
// the candidate's `build_id` and its (signed) `flags`.
pub fn verify_pending(pending: &[u8], running: &Keys) -> Result<VerifiedBinary, VerifyError> {
    if is_zero(&running.release_key) { return Err(VerifyError::PublicKeyUnset); }
    if is_zero(&running.app_id) { return Err(VerifyError::ApplicationUnset); }

    // `find_record` only reports a magic with a whole record behind it.
    let offset = find_record(pending).ok_or(VerifyError::MagicMissing)?;
    let record = &pending[offset..offset + RECORD_LEN];

    if record[APP_ID_OFFSET..APP_ID_OFFSET + APP_ID_LEN] != running.app_id {
        return Err(VerifyError::WrongApplication);
    }

    let signed = statement(pending, offset);
    let signature = &record[SIGNATURE_OFFSET..SIGNATURE_OFFSET + SIGNATURE_LEN];
    let accepted = verifies(&signed, signature, &running.release_key)
        || (!is_zero(&running.recovery_key) && verifies(&signed, signature, &running.recovery_key));

    if !accepted { return Err(VerifyError::SignatureMismatch); }

    Ok(VerifiedBinary {
        build_id: u64::from_le_bytes(record[BUILD_ID_OFFSET..BUILD_ID_OFFSET + 8].try_into().unwrap()),
        flags:    record[FLAGS_OFFSET],
    })
}

// Test helpers, shared with update.rs's tests: deterministic keys and signed fake binaries.
#[cfg(test)]
pub mod fixtures {
    use super::*;
    use crate::config::{MAGIC_LEN, magic};
    use ml_dsa::{B32, Keypair, MlDsa44, SigningKey, signature::Signer};

    pub fn keypair(seed: u8) -> (SigningKey<MlDsa44>, [u8; PUBKEY_LEN]) {
        let seed: B32 = [seed; 32].into();
        let sk: SigningKey<MlDsa44> = SigningKey::from_seed(&seed);
        let pk: [u8; PUBKEY_LEN] = sk.verifying_key().encode().into();
        (sk, pk)
    }

    pub fn keys(app: &str, release: &[u8; PUBKEY_LEN], recovery: Option<&[u8; PUBKEY_LEN]>) -> Keys {
        Keys {
            app_id:       crate::signing::app_id(app),
            release_key:  *release,
            recovery_key: recovery.copied().unwrap_or([0; PUBKEY_LEN]),
        }
    }

    // A fake `stub ‖ record ‖ jar`, with the record at `offset`, carrying `carried` as its keys.
    pub fn binary(offset: usize, build_id: u64, flags: u8, carried: &Keys) -> Vec<u8> {
        let mut bin = vec![0xAAu8; offset + RECORD_LEN + 1024];
        bin[offset..offset + MAGIC_LEN].copy_from_slice(&magic());
        bin[offset + BUILD_ID_OFFSET..offset + BUILD_ID_OFFSET + 8].copy_from_slice(&build_id.to_le_bytes());
        bin[offset + FLAGS_OFFSET] = flags;
        bin[offset + 22..offset + 32].fill(0);
        bin[offset + APP_ID_OFFSET..offset + APP_ID_OFFSET + APP_ID_LEN].copy_from_slice(&carried.app_id);
        bin[offset + RELEASE_KEY_OFFSET..offset + RELEASE_KEY_OFFSET + PUBKEY_LEN]
            .copy_from_slice(&carried.release_key);
        bin[offset + RECOVERY_KEY_OFFSET..offset + RECOVERY_KEY_OFFSET + PUBKEY_LEN]
            .copy_from_slice(&carried.recovery_key);
        bin[offset + SIGNATURE_OFFSET..offset + RECORD_LEN].fill(0);
        bin
    }

    pub fn sign(bin: &mut [u8], sk: &SigningKey<MlDsa44>, offset: usize) {
        let signature: [u8; SIGNATURE_LEN] = sk.sign(&statement(bin, offset)).encode().into();
        bin[offset + SIGNATURE_OFFSET..offset + RECORD_LEN].copy_from_slice(&signature);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use super::fixtures::{binary, keypair, keys, sign};
    use crate::config::magic;

    const APP: &str = "propensive/fume";

    #[test]
    fn round_trip() {
        let (sk, pk) = keypair(1);
        let running = keys(APP, &pk, None);
        let mut bin = binary(0x100, 42, 0, &running);
        sign(&mut bin, &sk, 0x100);

        let v = verify_pending(&bin, &running).expect("verify");
        assert_eq!(v.build_id, 42);
        assert_eq!(v.flags, 0);
    }

    #[test]
    fn signed_with_downgrade_flag_round_trips() {
        let (sk, pk) = keypair(1);
        let running = keys(APP, &pk, None);
        let mut bin = binary(0x100, 7, 0x01, &running);
        sign(&mut bin, &sk, 0x100);

        let v = verify_pending(&bin, &running).expect("verify");
        assert_eq!(v.build_id, 7);
        assert_eq!(v.flags, 0x01);
    }

    #[test]
    fn tampered_byte_outside_sig_rejected() {
        let (sk, pk) = keypair(1);
        let running = keys(APP, &pk, None);
        let mut bin = binary(0x100, 42, 0, &running);
        sign(&mut bin, &sk, 0x100);

        bin[0] ^= 0x01;       // flip a bit in the stub
        assert_eq!(verify_pending(&bin, &running).unwrap_err(), VerifyError::SignatureMismatch);

        let last = bin.len() - 1;
        bin[0] ^= 0x01;
        bin[last] ^= 0x01;    // and one in the jar
        assert_eq!(verify_pending(&bin, &running).unwrap_err(), VerifyError::SignatureMismatch);
    }

    #[test]
    fn tampered_sig_rejected() {
        let (sk, pk) = keypair(1);
        let running = keys(APP, &pk, None);
        let mut bin = binary(0x100, 42, 0, &running);
        sign(&mut bin, &sk, 0x100);

        bin[0x100 + SIGNATURE_OFFSET] ^= 0x01;
        assert_eq!(verify_pending(&bin, &running).unwrap_err(), VerifyError::SignatureMismatch);
    }

    #[test]
    fn tampered_flags_byte_rejected() {
        // The flags byte is inside the hashed file. Flipping it after signing must invalidate
        // the signature, so an attacker cannot turn on DOWNGRADE_PERMITTED by editing the binary.
        let (sk, pk) = keypair(1);
        let running = keys(APP, &pk, None);
        let mut bin = binary(0x100, 42, 0, &running);
        sign(&mut bin, &sk, 0x100);

        bin[0x100 + FLAGS_OFFSET] = 0x01;
        assert_eq!(verify_pending(&bin, &running).unwrap_err(), VerifyError::SignatureMismatch);
    }

    #[test]
    fn tampered_build_id_rejected() {
        // Likewise the build id, so a low build id cannot be lifted past the downgrade gate.
        let (sk, pk) = keypair(1);
        let running = keys(APP, &pk, None);
        let mut bin = binary(0x100, 1, 0, &running);
        sign(&mut bin, &sk, 0x100);

        bin[0x100 + 8..0x100 + 16].copy_from_slice(&999u64.to_le_bytes());
        assert_eq!(verify_pending(&bin, &running).unwrap_err(), VerifyError::SignatureMismatch);
    }

    #[test]
    fn tampered_carried_key_rejected() {
        // The keys a candidate carries are what its successors are checked against; swapping
        // them after signing must invalidate the signature, or one upgrade could hand the
        // channel to anyone.
        let (sk, pk) = keypair(1);
        let (_, other) = keypair(2);
        let running = keys(APP, &pk, None);
        let mut bin = binary(0x100, 42, 0, &running);
        sign(&mut bin, &sk, 0x100);

        bin[0x100 + RELEASE_KEY_OFFSET..0x100 + RELEASE_KEY_OFFSET + PUBKEY_LEN].copy_from_slice(&other);
        assert_eq!(verify_pending(&bin, &running).unwrap_err(), VerifyError::SignatureMismatch);
    }

    #[test]
    fn wrong_key_rejected() {
        let (sk, pk) = keypair(1);
        let (_, other) = keypair(2);
        let mut bin = binary(0x100, 42, 0, &keys(APP, &pk, None));
        sign(&mut bin, &sk, 0x100);

        assert_eq!(verify_pending(&bin, &keys(APP, &other, None)).unwrap_err(), VerifyError::SignatureMismatch);
    }

    #[test]
    fn key_carried_only_by_the_candidate_rejected() {
        // A candidate signed with the key in its *own* record, which the running binary does not
        // hold, must not verify: only the running binary's keys count.
        let (_, running_pk) = keypair(1);
        let (sk, carried_pk) = keypair(2);
        let mut bin = binary(0x100, 42, 0, &keys(APP, &carried_pk, Some(&carried_pk)));
        sign(&mut bin, &sk, 0x100);

        assert_eq!(verify_pending(&bin, &keys(APP, &running_pk, None)).unwrap_err(),
                   VerifyError::SignatureMismatch);
    }

    #[test]
    fn recovery_signed_candidate_accepted() {
        let (_, release) = keypair(1);
        let (recovery_sk, recovery) = keypair(3);
        let (_, next) = keypair(4);
        let running = keys(APP, &release, Some(&recovery));
        // The recovery key replaces the release key: the candidate carries a new one.
        let mut bin = binary(0x100, 43, 0, &keys(APP, &next, Some(&recovery)));
        sign(&mut bin, &recovery_sk, 0x100);

        assert_eq!(verify_pending(&bin, &running).expect("verify").build_id, 43);
    }

    #[test]
    fn recovery_signed_candidate_rejected_without_a_recovery_key() {
        let (_, release) = keypair(1);
        let (recovery_sk, recovery) = keypair(3);
        let mut bin = binary(0x100, 43, 0, &keys(APP, &release, Some(&recovery)));
        sign(&mut bin, &recovery_sk, 0x100);

        assert_eq!(verify_pending(&bin, &keys(APP, &release, None)).unwrap_err(),
                   VerifyError::SignatureMismatch);
    }

    #[test]
    fn wrong_application_rejected() {
        // Signed with the right key, but for another application.
        let (sk, pk) = keypair(1);
        let mut bin = binary(0x100, 42, 0, &keys("propensive/flame", &pk, None));
        sign(&mut bin, &sk, 0x100);

        assert_eq!(verify_pending(&bin, &keys(APP, &pk, None)).unwrap_err(), VerifyError::WrongApplication);
    }

    #[test]
    fn record_after_stub_with_decoy_magic_in_jar() {
        // A magic-free stub, the record, then a JAR that happens to contain the magic bytes. The
        // first magic is the record, and the statement covers everything.
        let (sk, pk) = keypair(1);
        let running = keys(APP, &pk, None);
        let mut bin = binary(0x400, 11, 0, &running);
        bin.extend_from_slice(&magic());
        bin.extend_from_slice(&[0x77u8; RECORD_LEN]);
        sign(&mut bin, &sk, 0x400);

        assert_eq!(verify_pending(&bin, &running).expect("verify").build_id, 11);
    }

    #[test]
    fn missing_magic_rejected() {
        let (_, pk) = keypair(1);
        let junk = vec![0u8; 8 * 1024];
        assert_eq!(verify_pending(&junk, &keys(APP, &pk, None)).unwrap_err(), VerifyError::MagicMissing);
    }

    #[test]
    fn v3_record_is_no_record() {
        let (_, pk) = keypair(1);
        let running = keys(APP, &pk, None);
        let mut bin = binary(0x100, 42, 0, &running);
        bin[0x100 + 7] = 3;
        assert_eq!(verify_pending(&bin, &running).unwrap_err(), VerifyError::MagicMissing);
    }

    #[test]
    fn unsigned_binary_rejected() {
        let (_, pk) = keypair(1);
        let running = keys(APP, &pk, None);
        let bin = binary(0x100, 42, 0, &running);
        assert_eq!(verify_pending(&bin, &running).unwrap_err(), VerifyError::SignatureMismatch);
    }

    #[test]
    fn unset_release_key_rejects_everything() {
        let (sk, pk) = keypair(1);
        let mut bin = binary(0x100, 42, 0, &keys(APP, &pk, None));
        sign(&mut bin, &sk, 0x100);

        let mut running = keys(APP, &pk, None);
        running.release_key = [0; PUBKEY_LEN];
        assert_eq!(verify_pending(&bin, &running).unwrap_err(), VerifyError::PublicKeyUnset);
    }

    #[test]
    fn unset_application_rejects_everything() {
        let (sk, pk) = keypair(1);
        let mut bin = binary(0x100, 42, 0, &keys(APP, &pk, None));
        sign(&mut bin, &sk, 0x100);

        let mut running = keys(APP, &pk, None);
        running.app_id = [0; APP_ID_LEN];
        assert!(!running.upgradable());
        assert_eq!(verify_pending(&bin, &running).unwrap_err(), VerifyError::ApplicationUnset);
    }
}
