use std::ffi::{OsStr, OsString};
use std::path::{Path, PathBuf};
use std::process::Command;

// The process state a daemon starts with, fixed by convention rather than inherited from
// whichever invocation happened to start it (spec/launcher.md, *The daemon process*). A daemon
// outlives the invocation that launched it and serves every later one, so anything it inherited
// — a working directory, a umask, an open descriptor, an environment — would be one client's
// state silently imposed on all the others, and would differ according to who came first. Each
// invocation's own directory, umask and environment reach the daemon in its `init` document.

// The value of `-Dethereal.environment` saying that the launcher's environment arrives on the
// daemon's standard input, which is otherwise the null device.
pub const ENVIRONMENT_ON_STDIN: &str = "stdin";

// The variables the daemon's own environment keeps: who the user is, where their files and the
// state directory are, the locale and time zone, the search path for the commands the daemon
// itself runs, and the options the JVM reads as it starts, which are how a daemon's heap is
// sized. Everything else — the terminal's variables, the shell's, every application's — is
// removed.
#[cfg(unix)]
const KEPT: [&str; 17] = [
    "PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "TZ", "TMPDIR",
    "XDG_RUNTIME_DIR", "XDG_STATE_HOME", "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "XDG_DATA_HOME",
    "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "ETHEREAL_DEBUG",
];

#[cfg(windows)]
const KEPT: [&str; 18] = [
    "SystemRoot", "SystemDrive", "windir", "ComSpec", "PATH", "PATHEXT", "TEMP", "TMP",
    "USERPROFILE", "APPDATA", "LOCALAPPDATA", "USERNAME", "HOMEDRIVE", "HOMEPATH",
    "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "ETHEREAL_DEBUG",
];

// Whether a variable survives into the daemon's environment: one of `KEPT`, or a locale
// category (`LC_ALL`, `LC_CTYPE`, …). Windows variable names are case-insensitive.
pub fn kept(name: &OsStr) -> bool {
    let name = name.to_string_lossy();
    #[cfg(unix)]
    { KEPT.contains(&name.as_ref()) || name.starts_with("LC_") }
    #[cfg(windows)]
    { KEPT.iter().any(|kept| kept.eq_ignore_ascii_case(&name)) }
}

// Gives the command the daemon's environment: cleared, then refilled with the kept variables.
pub fn sanitize(command: &mut Command) {
    command.env_clear();
    for (name, value) in std::env::vars_os() {
        if kept(&name) { command.env(name, value); }
    }
}

// The launcher's whole environment as the file holds it: `NAME=value` entries, each ended by a
// NUL, as the platform's bytes on Unix and as UTF-8 on Windows (where a name or value that is not
// valid UTF-16 has U+FFFD substituted, as on the wire).
pub fn encode(variables: impl Iterator<Item = (OsString, OsString)>) -> Vec<u8> {
    let mut bytes = Vec::new();
    for (name, value) in variables {
        bytes.extend_from_slice(&raw(&name));
        bytes.push(b'=');
        bytes.extend_from_slice(&raw(&value));
        bytes.push(0);
    }
    bytes
}

#[cfg(unix)]
fn raw(text: &OsStr) -> Vec<u8> {
    use std::os::unix::ffi::OsStrExt;
    text.as_bytes().to_vec()
}

#[cfg(windows)]
fn raw(text: &OsStr) -> Vec<u8> { text.to_string_lossy().into_owned().into_bytes() }

// Writes the launcher's whole environment to the daemon's standard input, and closes it, so the
// daemon can read it once as it starts: never on disk, and never in an argument vector, where
// `ps` would show it. On a thread of its own, since a pipe holds only so much and the write
// completes only as the daemon reads; a daemon too old to read it leaves the thread blocked
// until the launcher exits, and is otherwise unaffected.
pub fn send_environment(stdin: std::process::ChildStdin) {
    let bytes = encode(std::env::vars_os());
    std::thread::spawn(move || {
        use std::io::Write;
        let mut stdin = stdin;
        if let Err(error) = stdin.write_all(&bytes) {
            crate::debug!("daemon: environment not sent: {}", error);
        }
    });
}

// The directory the daemon runs in: the root of the filesystem, which always exists, is never
// unmounted while the system runs, and belongs to no invocation. On Windows, the root of the
// system drive.
pub fn working_directory() -> PathBuf {
    #[cfg(unix)]
    { PathBuf::from("/") }
    #[cfg(windows)]
    {
        let drive = std::env::var_os("SystemDrive").unwrap_or_else(|| OsString::from("C:"));
        let mut root = PathBuf::from(drive);
        root.push("\\");
        root
    }
}

// A path made absolute against the launcher's own working directory, since the daemon will not
// share it. A path that cannot be made absolute is returned as it was.
pub fn absolute(path: &Path) -> PathBuf {
    std::path::absolute(path).unwrap_or_else(|_| path.to_path_buf())
}

// Run in the child between `fork` and `exec`, so only async-signal-safe calls: a fixed umask, and
// every descriptor above the standard three marked close-on-exec. The descriptors are marked
// rather than closed so that the one Rust's `spawn` uses to report a failed `exec` keeps working;
// marked, they close at `exec` all the same.
#[cfg(unix)]
pub fn conventional_process_state() {
    unsafe {
        libc::umask(0o077);
        mark_descriptors_cloexec();
    }
}

#[cfg(target_os = "linux")]
unsafe fn mark_descriptors_cloexec() {
    // `close_range` with `CLOSE_RANGE_CLOEXEC` (Linux 5.11) marks every descriptor at once; on an
    // older kernel it fails, and the loop below does the same thing one descriptor at a time.
    const CLOSE_RANGE_CLOEXEC: libc::c_uint = 1 << 2;
    let marked = unsafe { libc::syscall(libc::SYS_close_range, 3 as libc::c_uint, libc::c_uint::MAX, CLOSE_RANGE_CLOEXEC) };
    if marked != 0 { unsafe { mark_each_descriptor_cloexec(); } }
}

#[cfg(all(unix, not(target_os = "linux")))]
unsafe fn mark_descriptors_cloexec() { unsafe { mark_each_descriptor_cloexec(); } }

// Every descriptor from 3 up to the process's limit, capped so that an unlimited or enormous
// limit does not make the loop the slow part of starting a daemon.
#[cfg(unix)]
unsafe fn mark_each_descriptor_cloexec() {
    const CAP: libc::c_long = 65_536;
    let limit = unsafe { libc::sysconf(libc::_SC_OPEN_MAX) };
    let limit = if limit < 0 || limit > CAP { CAP } else { limit };
    for fd in 3..limit as libc::c_int {
        let flags = unsafe { libc::fcntl(fd, libc::F_GETFD) };
        if flags >= 0 && flags & libc::FD_CLOEXEC == 0 {
            unsafe { libc::fcntl(fd, libc::F_SETFD, flags | libc::FD_CLOEXEC); }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_identity_paths_and_locale() {
        for name in ["PATH", "HOME", "TMPDIR", "XDG_RUNTIME_DIR", "LANG", "LC_ALL", "LC_CTYPE",
                     "JAVA_TOOL_OPTIONS"] {
            assert!(kept(OsStr::new(name)), "{name} should be kept");
        }
    }

    #[test]
    fn removes_terminal_shell_and_application_variables() {
        for name in ["TERM", "COLUMNS", "LINES", "PWD", "OLDPWD", "SHLVL", "TMUX", "SSH_AUTH_SOCK",
                     "DISPLAY", "CLAUDECODE", "CODEX_SANDBOX", "XEK", "JAVA_HOME", "XEK_WRAP_JAVA"] {
            assert!(!kept(OsStr::new(name)), "{name} should be removed");
        }
    }

    #[test]
    fn encodes_entries_ended_by_nul() {
        let variables = vec![
            (OsString::from("A"), OsString::from("1")),
            (OsString::from("B"), OsString::from("x=y")),
            (OsString::from("C"), OsString::from("")),
        ];
        assert_eq!(encode(variables.into_iter()), b"A=1\0B=x=y\0C=\0".to_vec());
    }

    #[cfg(unix)]
    #[test]
    fn encodes_bytes_that_are_not_utf8_unchanged() {
        use std::os::unix::ffi::OsStringExt;
        let value = OsString::from_vec(vec![0x66, 0xff, 0x6f]);
        assert_eq!(encode(std::iter::once((OsString::from("N"), value))), vec![b'N', b'=', 0x66, 0xff, 0x6f, 0]);
    }

    #[cfg(unix)]
    #[test]
    fn works_in_the_root() {
        assert_eq!(working_directory(), PathBuf::from("/"));
    }

}
