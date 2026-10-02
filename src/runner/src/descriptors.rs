//! The client's file descriptors, as the `init` document advertises them (`spec/launcher.md`,
//! *Descriptors*). An argument such as `/dev/fd/63` — what `mytool <(cat foo)` produces — or
//! `/dev/stdin` names a descriptor of the *launcher's* process, which the daemon cannot open
//! for itself. The launcher therefore tells the daemon which descriptors it holds, and carries
//! the ones the daemon asks for as streams of the session (`session.rs`).
//!
//! The descriptors are enumerated, not guessed from the arguments: a path can reach the
//! application through `--input=/dev/fd/63`, a configuration file or the environment, and the
//! daemon matches on the path the application actually opens. A descriptor the launcher
//! opened for itself is marked close-on-exec, as everything Rust's standard library opens is,
//! and is left out; what remains is what the shell handed over, which is typically 0, 1 and 2
//! and nothing else.

use std::io;

/// One inherited descriptor: its number, `r`/`w`/`rw` as it was opened, its kind, and for a
/// regular file its real path, which the daemon can open directly.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Descriptor {
    pub fd: i32,
    pub direction: &'static str,
    pub kind: &'static str,
    pub path: Option<String>,
}

#[cfg(unix)]
pub fn inherited() -> Vec<Descriptor> {
    let mut fds: Vec<i32> = match std::fs::read_dir("/dev/fd") {
        Ok(entries) => entries
            .filter_map(|entry| entry.ok()?.file_name().to_str()?.parse().ok())
            .collect(),
        // No `/dev/fd`: the standard three are all that can be described.
        Err(_) => vec![0, 1, 2],
    };
    fds.sort_unstable();
    fds.dedup();
    fds.into_iter().filter_map(describe).collect()
}

#[cfg(unix)]
fn describe(fd: i32) -> Option<Descriptor> {
    let fd_flags = unsafe { libc::fcntl(fd, libc::F_GETFD) };
    if fd_flags < 0 { return None; }
    // The standard streams are the client's whatever their flags; anything else marked
    // close-on-exec is the launcher's own (a directory handle, a log file) and not inherited.
    if fd > 2 && fd_flags & libc::FD_CLOEXEC != 0 { return None; }

    let status = unsafe { libc::fcntl(fd, libc::F_GETFL) };
    if status < 0 { return None; }
    let direction = match status & libc::O_ACCMODE {
        libc::O_RDONLY => "r",
        libc::O_WRONLY => "w",
        _ => "rw",
    };

    let mut stat: libc::stat = unsafe { std::mem::zeroed() };
    if unsafe { libc::fstat(fd, &mut stat) } != 0 { return None; }
    let kind = match stat.st_mode & libc::S_IFMT {
        libc::S_IFREG => "file",
        libc::S_IFIFO => "pipe",
        libc::S_IFSOCK => "socket",
        libc::S_IFCHR => if unsafe { libc::isatty(fd) } == 1 { "tty" } else { "other" },
        // A directory is not a stream; nothing an application does with `/dev/fd/N` applies.
        libc::S_IFDIR => return None,
        _ => "other",
    };
    let path = if kind == "file" { real_path(fd) } else { None };
    Some(Descriptor { fd, direction, kind, path })
}

// The path a regular file is open under, which the daemon opens in its own process instead of
// streaming the bytes: it keeps the file seekable and its size known. Linux names it through
// /proc, with ` (deleted)` appended once it is unlinked, in which case there is no path to
// give; macOS has `F_GETPATH`.
#[cfg(target_os = "linux")]
fn real_path(fd: i32) -> Option<String> {
    let link = std::fs::read_link(format!("/proc/self/fd/{fd}")).ok()?;
    let text = link.to_str()?;
    if text.ends_with(" (deleted)") || !text.starts_with('/') { return None; }
    Some(text.to_owned())
}

#[cfg(target_os = "macos")]
fn real_path(fd: i32) -> Option<String> {
    let mut buffer = vec![0u8; libc::PATH_MAX as usize];
    if unsafe { libc::fcntl(fd, libc::F_GETPATH, buffer.as_mut_ptr()) } != 0 { return None; }
    let end = buffer.iter().position(|&b| b == 0)?;
    String::from_utf8(buffer[..end].to_vec()).ok()
}

#[cfg(all(unix, not(any(target_os = "linux", target_os = "macos"))))]
fn real_path(_fd: i32) -> Option<String> { None }

/// Reads from a raw descriptor; a signal's interruption is retried.
#[cfg(unix)]
pub fn read(fd: i32, buffer: &mut [u8]) -> io::Result<usize> {
    loop {
        let n = unsafe { libc::read(fd, buffer.as_mut_ptr() as *mut libc::c_void, buffer.len()) };
        if n >= 0 { return Ok(n as usize); }
        let error = io::Error::last_os_error();
        if error.kind() != io::ErrorKind::Interrupted { return Err(error); }
    }
}

/// Writes the whole of `bytes` to a raw descriptor.
#[cfg(unix)]
pub fn write_all(fd: i32, mut bytes: &[u8]) -> io::Result<()> {
    while !bytes.is_empty() {
        let n = unsafe { libc::write(fd, bytes.as_ptr() as *const libc::c_void, bytes.len()) };
        if n < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted { continue; }
            return Err(error);
        }
        bytes = &bytes[n as usize..];
    }
    Ok(())
}

#[cfg(unix)]
pub fn close(fd: i32) { unsafe { libc::close(fd); } }

// Windows hands a process handles, not numbered descriptors, and has no `/dev/fd`; the
// standard three are the only ones an application can name, and those are the session's own
// streams. Nothing is advertised beyond them.
#[cfg(windows)]
pub fn inherited() -> Vec<Descriptor> {
    use std::io::IsTerminal;
    let kind = |tty: bool| if tty { "tty" } else { "other" };
    vec![
        Descriptor { fd: 0, direction: "r", kind: kind(io::stdin().is_terminal()), path: None },
        Descriptor { fd: 1, direction: "w", kind: kind(io::stdout().is_terminal()), path: None },
        Descriptor { fd: 2, direction: "w", kind: kind(io::stderr().is_terminal()), path: None },
    ]
}

#[cfg(windows)]
pub fn read(_fd: i32, _buffer: &mut [u8]) -> io::Result<usize> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "no descriptors beyond the standard streams"))
}

#[cfg(windows)]
pub fn write_all(_fd: i32, _bytes: &[u8]) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "no descriptors beyond the standard streams"))
}

#[cfg(windows)]
pub fn close(_fd: i32) {}

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    // The standard three are always advertised, whatever cargo attached them to.
    #[test]
    fn the_standard_streams_are_always_described() {
        let described = inherited();
        for fd in 0..3 {
            assert!(described.iter().any(|d| d.fd == fd), "fd {fd} missing from {described:?}");
        }
    }

    // A pipe the test holds is advertised as one, with the direction it was opened for; a
    // regular file is advertised with its real path; and the launcher's own close-on-exec
    // handles never are.
    #[test]
    fn pipes_files_and_private_handles_are_told_apart() {
        let mut pipe = [0i32; 2];
        assert_eq!(unsafe { libc::pipe(pipe.as_mut_ptr()) }, 0);
        // Rust-opened: close-on-exec, so it must be absent.
        let private = std::fs::File::open("Cargo.toml").unwrap();
        // Shell-style: a regular file without close-on-exec.
        let shared = unsafe { libc::open(b"Cargo.toml\0".as_ptr() as *const _, libc::O_RDONLY) };
        assert!(shared >= 0);

        let described = inherited();
        let find = |fd: i32| described.iter().find(|d| d.fd == fd).cloned();

        let reader = find(pipe[0]).expect("the pipe's read end");
        assert_eq!((reader.direction, reader.kind, reader.path), ("r", "pipe", None));
        let writer = find(pipe[1]).expect("the pipe's write end");
        assert_eq!((writer.direction, writer.kind), ("w", "pipe"));
        let file = find(shared).expect("the shared file");
        assert_eq!((file.direction, file.kind), ("r", "file"));
        assert!(file.path.as_deref().is_some_and(|p| p.ends_with("/Cargo.toml")), "{:?}", file.path);
        use std::os::unix::io::AsRawFd;
        assert_eq!(find(private.as_raw_fd()), None);

        close(pipe[0]);
        close(pipe[1]);
        close(shared);
    }

    // The arguments are never consulted: a literal `/dev/fd/38` is text to the application,
    // and only the descriptors the process really holds are advertised.
    #[test]
    fn enumeration_does_not_read_the_arguments() {
        let described = inherited();
        assert!(!described.iter().any(|d| d.fd == 38 && d.kind == "pipe") || unsafe { libc::fcntl(38, libc::F_GETFD) } >= 0);
    }
}
