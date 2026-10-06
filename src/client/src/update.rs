use std::ffi::{OsStr, OsString};
use std::path::{Path, PathBuf};

use crate::signing::{BUILD_ID_OFFSET, FLAG_DOWNGRADE_PERMITTED};
use crate::verify::{Keys, VerifyError};

// What became of a `.pending` binary, as `.upgrade-result` reports it (spec/layout.md).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Outcome {
    Applied,
    Disabled,
    NoRecord,
    WrongApplication,
    BadSignature,
    NotNewer,
    SwapFailed,
}

impl Outcome {
    pub fn word(self) -> &'static str {
        match self {
            Outcome::Applied          => "applied",
            Outcome::Disabled         => "disabled",
            Outcome::NoRecord         => "no-record",
            Outcome::WrongApplication => "wrong-application",
            Outcome::BadSignature     => "bad-signature",
            Outcome::NotNewer         => "not-newer",
            Outcome::SwapFailed       => "swap-failed",
        }
    }
}

// `argv0` is what the launcher was invoked as, which the re-exec passes on unchanged so that
// `invoked-as` (spec/launcher.md) survives an upgrade; the executable run is `script`.
pub fn check_updates(script: &Path, argv0: Option<&OsStr>, args: &[OsString], name: &str) {
    // `script` is renamed below. `main` has already refused to run if it is not a regular
    // file; repeat the check here so the rename can never reach a directory or a stray
    // same-named neighbour, whatever a future caller does.
    if !script.is_file() { return; }

    let data_dir = crate::state::data_home().join(name);
    let running = crate::config::read_config().build_id;
    if process(script, &data_dir, name, running, &crate::config::keys()) != Some(Outcome::Applied) { return; }

    crate::debug!("update: swap complete; re-execing");

    // The arguments are re-execed as the bytes they arrived as, so a re-exec is invisible to
    // the application even for an argument that is not UTF-8.
    #[cfg(unix)]
    unsafe {
        use std::ffi::CString;
        use std::os::unix::ffi::OsStrExt;
        let script_c = CString::new(script.as_os_str().as_bytes()).unwrap();
        let argv0_c = CString::new(argv0.unwrap_or(script.as_os_str()).as_bytes()).unwrap();
        let mut argv: Vec<*const libc::c_char> = Vec::with_capacity(args.len() + 2);
        argv.push(argv0_c.as_ptr());
        let arg_cstrs: Vec<CString> = args.iter()
            .map(|a| CString::new(a.as_bytes()).unwrap())
            .collect();
        for a in &arg_cstrs { argv.push(a.as_ptr()); }
        argv.push(std::ptr::null());
        libc::execv(script_c.as_ptr(), argv.as_ptr());
    }

    #[cfg(windows)]
    {
        use std::process::Command;
        let _ = argv0; // `Command` cannot set argv[0]; the re-exec runs under the executable's path.
        // Same handle-leak prevention as in `launch.rs::launch` — without
        // this, the re-exec'd new binary inherits the launcher's
        // stdin/stdout/stderr pipe handles and keeps them open even after
        // the launcher exits, so any caller reading our output (e.g.
        // PowerShell's `Process.StandardOutput.ReadToEndAsync`) blocks
        // indefinitely.
        crate::launch::mark_stdio_non_inheritable();
        let status = Command::new(script).args(args).status();
        match status {
            Ok(s) => std::process::exit(s.code().unwrap_or(1)),
            Err(_) => std::process::exit(1),
        }
    }
}

// Handle `<data_dir>/.pending`, if there is one: verify it against the running binary's keys
// and build id, swap it in on success, and in every case delete it and record the outcome in
// `<data_dir>/.upgrade-result`. Returns `None`, having written nothing, when there is nothing
// pending. Verification is authoritative: the bytes installed are the ones verified, read
// once into memory, so nothing that happens to `.pending` afterwards can reach the executable.
fn process(script: &Path, data_dir: &Path, name: &str, running: u64, keys: &Keys) -> Option<Outcome> {
    let pending = data_dir.join(".pending");
    crate::debug!("update: pending={}", pending.display());
    let bytes = match std::fs::read(&pending) {
        Ok(bytes) => bytes,
        Err(e) => { crate::debug!("update: read(pending): {}", e); return None; },
    };
    if bytes.is_empty() { return None; }

    let candidate = crate::config::find_record(&bytes)
        .map(|offset| {
            let at = offset + BUILD_ID_OFFSET;
            u64::from_le_bytes(bytes[at..at + 8].try_into().unwrap())
        })
        .unwrap_or(0);

    let outcome = match crate::verify::verify_pending(&bytes, keys) {
        Err(VerifyError::PublicKeyUnset | VerifyError::ApplicationUnset) => Outcome::Disabled,
        Err(VerifyError::MagicMissing)      => Outcome::NoRecord,
        Err(VerifyError::WrongApplication)  => Outcome::WrongApplication,
        Err(VerifyError::SignatureMismatch) => Outcome::BadSignature,
        Ok(verified) => {
            let downgrade = verified.flags & FLAG_DOWNGRADE_PERMITTED != 0;
            if verified.build_id <= running && !downgrade { Outcome::NotNewer } else {
                crate::xek::step("Updating…");
                let outcome = swap(script, name, &bytes, &pending);
                crate::xek::clear();
                outcome
            }
        },
    };
    crate::debug!("update: {} (candidate={} running={})", outcome.word(), candidate, running);

    let _ = std::fs::remove_file(&pending);
    record(data_dir, outcome, candidate, running);
    Some(outcome)
}

// Install `bytes` as `script`, by way of two siblings in the executable's own directory, so
// that every rename stays on one filesystem: `.<name>.new`, written and synced first, and
// `.<name>.old`, which the running executable becomes. On any failure the executable is left
// as it was and nothing new is left behind.
fn swap(script: &Path, name: &str, bytes: &[u8], pending: &Path) -> Outcome {
    let Some(dir) = script.parent() else { return Outcome::SwapFailed };
    let new = dir.join(format!(".{name}.new"));
    let old = dir.join(format!(".{name}.old"));

    if let Err(e) = write_executable(&new, bytes) {
        crate::debug!("update: write({}): {}", new.display(), e);
        let _ = std::fs::remove_file(&new);
        return Outcome::SwapFailed;
    }
    let _ = std::fs::remove_file(pending);

    let _ = std::fs::remove_file(&old);
    if let Err(e) = std::fs::rename(script, &old) {
        crate::debug!("update: rename(script -> {}): {}", old.display(), e);
        let _ = std::fs::remove_file(&new);
        return Outcome::SwapFailed;
    }
    if let Err(e) = std::fs::rename(&new, script) {
        crate::debug!("update: rename({} -> script): {}", new.display(), e);
        let _ = std::fs::rename(&old, script);
        let _ = std::fs::remove_file(&new);
        return Outcome::SwapFailed;
    }
    Outcome::Applied
}

fn write_executable(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o755);
    }
    let mut file = options.open(path)?;
    file.write_all(bytes)?;
    file.sync_all()
}

// `<outcome> <candidate-build-id> <running-build-id> <time-ms>`, replacing any earlier result.
// Written beside the file and renamed over it, so a reader never sees half a line.
fn record(data_dir: &Path, outcome: Outcome, candidate: u64, running: u64) {
    let line = format!("{} {} {} {}\n", outcome.word(), candidate, running, crate::now_ms());
    let path = data_dir.join(".upgrade-result");
    let temporary: PathBuf = data_dir.join(format!(".upgrade-result.{}", std::process::id()));
    let written = std::fs::write(&temporary, line).and_then(|()| std::fs::rename(&temporary, &path));
    if let Err(e) = written {
        crate::debug!("update: writing {}: {}", path.display(), e);
        let _ = std::fs::remove_file(&temporary);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::verify::fixtures::{binary, keypair, keys, sign};

    const APP: &str = "propensive/fume";
    const NAME: &str = "tool";

    struct Scene { root: PathBuf, script: PathBuf, data: PathBuf }

    impl Scene {
        fn new(label: &str, v1: &[u8]) -> Scene {
            let root = std::env::temp_dir().join(format!("xek-update-{}-{}", std::process::id(), label));
            let _ = std::fs::remove_dir_all(&root);
            let bin = root.join("bin");
            let data = root.join("data").join(NAME);
            std::fs::create_dir_all(&bin).unwrap();
            std::fs::create_dir_all(&data).unwrap();
            let script = bin.join(NAME);
            std::fs::write(&script, v1).unwrap();
            Scene { root, script, data }
        }

        fn stage(&self, candidate: &[u8]) { std::fs::write(self.data.join(".pending"), candidate).unwrap(); }

        fn result(&self) -> Vec<String> {
            let text = std::fs::read_to_string(self.data.join(".upgrade-result")).unwrap();
            assert!(text.ends_with('\n') && text.lines().count() == 1, "one line: {text:?}");
            text.split_whitespace().map(str::to_owned).collect()
        }

        fn check(&self, running: u64, keys: &Keys, outcome: Outcome, candidate: u64) {
            assert_eq!(process(&self.script, &self.data, NAME, running, keys), Some(outcome));
            let result = self.result();
            assert_eq!(&result[..3], &[outcome.word().to_owned(), candidate.to_string(), running.to_string()]);
            assert!(result[3].parse::<u64>().unwrap() > 0);
            assert!(!self.data.join(".pending").exists());
            assert!(!self.script.with_file_name(format!(".{NAME}.new")).exists());
        }
    }

    impl Drop for Scene {
        fn drop(&mut self) {
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                let _ = std::fs::set_permissions(self.root.join("bin"), std::fs::Permissions::from_mode(0o755));
            }
            let _ = std::fs::remove_dir_all(&self.root);
        }
    }

    fn signed(build_id: u64, flags: u8, app: &str, seed: u8, carried: &[u8; 1312]) -> Vec<u8> {
        let (sk, _) = keypair(seed);
        let mut bin = binary(0x80, build_id, flags, &keys(app, carried, None));
        sign(&mut bin, &sk, 0x80);
        bin
    }

    #[test]
    fn nothing_pending_writes_nothing() {
        let scene = Scene::new("nothing", b"v1");
        let (_, pk) = keypair(1);
        assert_eq!(process(&scene.script, &scene.data, NAME, 1, &keys(APP, &pk, None)), None);
        scene.stage(b"");
        assert_eq!(process(&scene.script, &scene.data, NAME, 1, &keys(APP, &pk, None)), None);
        assert!(!scene.data.join(".upgrade-result").exists());
    }

    #[test]
    fn applied() {
        let scene = Scene::new("applied", b"v1");
        let (_, pk) = keypair(1);
        let v2 = signed(2, 0, APP, 1, &pk);
        scene.stage(&v2);
        scene.check(1, &keys(APP, &pk, None), Outcome::Applied, 2);
        assert_eq!(std::fs::read(&scene.script).unwrap(), v2);
        assert_eq!(std::fs::read(scene.script.with_file_name(format!(".{NAME}.old"))).unwrap(), b"v1");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(std::fs::metadata(&scene.script).unwrap().permissions().mode() & 0o777, 0o755);
        }
    }

    #[test]
    fn applied_downgrade_when_permitted() {
        let scene = Scene::new("downgrade", b"v5");
        let (_, pk) = keypair(1);
        scene.stage(&signed(3, FLAG_DOWNGRADE_PERMITTED, APP, 1, &pk));
        scene.check(5, &keys(APP, &pk, None), Outcome::Applied, 3);
    }

    #[test]
    fn disabled() {
        let scene = Scene::new("disabled", b"v1");
        let (_, pk) = keypair(1);
        scene.stage(&signed(2, 0, APP, 1, &pk));
        scene.check(1, &Keys::NONE, Outcome::Disabled, 2);
        assert_eq!(std::fs::read(&scene.script).unwrap(), b"v1");
    }

    #[test]
    fn no_record() {
        let scene = Scene::new("norecord", b"v1");
        let (_, pk) = keypair(1);
        scene.stage(&[0x55u8; 9000]);
        scene.check(1, &keys(APP, &pk, None), Outcome::NoRecord, 0);
        assert_eq!(std::fs::read(&scene.script).unwrap(), b"v1");
    }

    #[test]
    fn wrong_application() {
        let scene = Scene::new("wrongapp", b"v1");
        let (_, pk) = keypair(1);
        scene.stage(&signed(2, 0, "propensive/flame", 1, &pk));
        scene.check(1, &keys(APP, &pk, None), Outcome::WrongApplication, 2);
    }

    #[test]
    fn bad_signature() {
        let scene = Scene::new("badsig", b"v1");
        let (_, pk) = keypair(1);
        let mut v2 = signed(2, 0, APP, 1, &pk);
        v2[3] ^= 1;
        scene.stage(&v2);
        scene.check(1, &keys(APP, &pk, None), Outcome::BadSignature, 2);
        assert_eq!(std::fs::read(&scene.script).unwrap(), b"v1");
    }

    #[test]
    fn not_newer() {
        let scene = Scene::new("notnewer", b"v1");
        let (_, pk) = keypair(1);
        scene.stage(&signed(2, 0, APP, 1, &pk));
        scene.check(2, &keys(APP, &pk, None), Outcome::NotNewer, 2);
        assert_eq!(std::fs::read(&scene.script).unwrap(), b"v1");
    }

    #[cfg(unix)]
    #[test]
    fn swap_failed_when_the_directory_is_read_only() {
        use std::os::unix::fs::PermissionsExt;
        if unsafe { libc::geteuid() } == 0 { return; }   // root writes anywhere
        let scene = Scene::new("readonly", b"v1");
        let (_, pk) = keypair(1);
        scene.stage(&signed(2, 0, APP, 1, &pk));
        std::fs::set_permissions(scene.root.join("bin"), std::fs::Permissions::from_mode(0o555)).unwrap();
        scene.check(1, &keys(APP, &pk, None), Outcome::SwapFailed, 2);
        assert_eq!(std::fs::read(&scene.script).unwrap(), b"v1");
    }
}
