use std::sync::{Arc, OnceLock};
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU32, Ordering};
use std::time::Duration;

use crate::protocol::{SignalAck, SignalDetail};
use crate::session::Signaler;
use crate::tty::TtyState;

static SIGNALER: OnceLock<Arc<Signaler>> = OnceLock::new();
static TERMINATION: OnceLock<Arc<AtomicI32>> = OnceLock::new();
static SAVED_TTY: OnceLock<TtyState> = OnceLock::new();
// Whether this launcher put the terminal into raw mode, and so must put it back after a
// stop and re-apply it after a continue. False for a pipe, and for a terminal the launcher
// was not entitled to reconfigure (a background job).
static RAW_MODE_OWNED: AtomicBool = AtomicBool::new(false);
static TIMEOUT_MS: AtomicU32 = AtomicU32::new(250);
// Set while a command the daemon asked for owns the terminal: the terminal is in the user's
// cooked mode, so Ctrl-C reaches the whole foreground group, and the launcher — like a shell
// waiting on a foreground child — leaves INT and QUIT to the child.
static CHILD_RUNNING: AtomicBool = AtomicBool::new(false);

pub fn child_running(running: bool) { CHILD_RUNNING.store(running, Ordering::SeqCst); }

const DEFAULT_TIMEOUT_MS: u32 = 250;

// Sends the signal over the session and waits for the daemon's answer. On Unix this runs on
// the courier thread, never in a handler; see below.
fn forward(name: &str, detail: SignalDetail) -> SignalAck {
    match SIGNALER.get() {
        Some(signaler) => signaler.signal(name, detail, Duration::from_millis(TIMEOUT_MS.load(Ordering::SeqCst) as u64)),
        None => SignalAck::Timeout,
    }
}

// Records that the launcher must die once the invocation's streams are drained: by the
// signal numbered `code` on Unix, or with `code` as the exit status on Windows. The main
// path polls for this; see `die`.
fn flag_termination(code: i32) {
    if let Some(flag) = TERMINATION.get() {
        flag.store(code, Ordering::SeqCst);
    }
}

fn install_state(signaler: Arc<Signaler>, termination: Arc<AtomicI32>, saved_tty: TtyState, raw_mode_owned: bool) {
    let _ = SIGNALER.set(signaler);
    let _ = TERMINATION.set(termination);
    let _ = SAVED_TTY.set(saved_tty);
    RAW_MODE_OWNED.store(raw_mode_owned, Ordering::SeqCst);
    let timeout = std::env::var("ETHEREAL_SIGNAL_TIMEOUT_MS")
        .ok()
        .and_then(|s| s.parse::<u32>().ok())
        .unwrap_or(DEFAULT_TIMEOUT_MS);
    TIMEOUT_MS.store(timeout, Ordering::SeqCst);
}

// ── Unix ──────────────────────────────────────────────────────────────────────
//
// A handler may only do what is async-signal-safe, and sending a document on the session is
// not: it takes the writer queue's lock, which the thread the signal interrupted may hold. So
// the handler and the session meet through two pipes. The handler writes the signal to the
// request pipe and waits, with `poll`, for one byte on the answer pipe; a courier thread
// reads the request, sends the `signal` document and waits for `signal-ack`, and writes the
// answer back. `write`, `poll` and `read` are all async-signal-safe, and nothing allocates.

#[cfg(unix)]
static REQUEST_WRITE: AtomicI32 = AtomicI32::new(-1);
#[cfg(unix)]
static ANSWER_READ: AtomicI32 = AtomicI32::new(-1);

#[cfg(unix)]
const ACCEPTED: u8 = 1;
#[cfg(unix)]
const REJECTED: u8 = 0;

#[cfg(unix)]
fn signal_name(signal: libc::c_int) -> Option<&'static str> {
    Some(match signal {
        libc::SIGINT   => "INT",
        libc::SIGQUIT  => "QUIT",
        libc::SIGWINCH => "WINCH",
        libc::SIGTERM  => "TERM",
        libc::SIGHUP   => "HUP",
        libc::SIGUSR1  => "USR1",
        libc::SIGUSR2  => "USR2",
        libc::SIGTSTP  => "TSTP",
        libc::SIGCONT  => "CONT",
        _ => return None,
    })
}

#[cfg(unix)]
const FORWARDED: [libc::c_int; 9] = [
    libc::SIGINT, libc::SIGQUIT, libc::SIGWINCH, libc::SIGTERM,
    libc::SIGHUP, libc::SIGUSR1, libc::SIGUSR2, libc::SIGTSTP, libc::SIGCONT,
];

// The terminal's size travels with the signals that say it may have changed: WINCH, and
// CONT, since the window may have been resized while the job was stopped. A POSIX signal
// carries no payload and the daemon holds no terminal to ask, so this is the only way it
// can learn the new size. TIOCGWINSZ is async-signal-safe, so it is read in the handler.
#[cfg(unix)]
fn carries_size(signal: libc::c_int) -> bool { signal == libc::SIGWINCH || signal == libc::SIGCONT }

// The handler's half: a five-byte request (the signal, then the columns and rows, big-endian,
// zero when not measured), then the answer byte, or none within the timeout.
#[cfg(unix)]
fn request_from_handler(signal: libc::c_int) -> SignalAck {
    let request_fd = REQUEST_WRITE.load(Ordering::SeqCst);
    let answer_fd = ANSWER_READ.load(Ordering::SeqCst);
    if request_fd < 0 || answer_fd < 0 { return SignalAck::Timeout; }

    // An answer that arrived after an earlier request timed out would be read as this one's.
    let mut stale = [0u8; 16];
    while poll_readable(answer_fd, 0) {
        if unsafe { libc::read(answer_fd, stale.as_mut_ptr() as *mut libc::c_void, stale.len()) } <= 0 { break; }
    }

    let (columns, rows) = if carries_size(signal) { crate::tty::terminal_size().unwrap_or((0, 0)) } else { (0, 0) };
    let request = [signal as u8, (columns >> 8) as u8, columns as u8, (rows >> 8) as u8, rows as u8];
    if unsafe { libc::write(request_fd, request.as_ptr() as *const libc::c_void, request.len()) } != request.len() as isize {
        return SignalAck::Timeout;
    }
    if !poll_readable(answer_fd, TIMEOUT_MS.load(Ordering::SeqCst) as libc::c_int) { return SignalAck::Timeout; }
    let mut answer = [0u8; 1];
    if unsafe { libc::read(answer_fd, answer.as_mut_ptr() as *mut libc::c_void, 1) } != 1 { return SignalAck::Timeout; }
    if answer[0] == ACCEPTED { SignalAck::Accept } else { SignalAck::Reject }
}

#[cfg(unix)]
fn poll_readable(fd: libc::c_int, timeout_ms: libc::c_int) -> bool {
    let mut descriptor = libc::pollfd { fd, events: libc::POLLIN, revents: 0 };
    loop {
        let ready = unsafe { libc::poll(&mut descriptor, 1, timeout_ms) };
        if ready > 0 { return descriptor.revents & libc::POLLIN != 0; }
        if ready == 0 { return false; }
        if std::io::Error::last_os_error().kind() != std::io::ErrorKind::Interrupted { return false; }
    }
}

// The courier's half: for each request, the document, the wait, the answer byte.
#[cfg(unix)]
fn courier(request_read: libc::c_int, answer_write: libc::c_int) {
    let mut request = [0u8; 5];
    loop {
        if !matches!(crate::descriptors::read(request_read, &mut request), Ok(5)) { return; }
        let signal = request[0] as libc::c_int;
        let columns = u16::from_be_bytes([request[1], request[2]]);
        let rows = u16::from_be_bytes([request[3], request[4]]);
        let Some(name) = signal_name(signal) else { continue };
        let size = if carries_size(signal) && columns > 0 && rows > 0 { Some((columns, rows)) } else { None };
        let ack = forward(name, SignalDetail { size, deadline_ms: None });
        if signal == libc::SIGTERM && ack == SignalAck::Accept { flag_termination(signal); }
        let answer = [if ack == SignalAck::Accept { ACCEPTED } else { REJECTED }];
        let _ = crate::descriptors::write_all(answer_write, &answer);
    }
}

// Restores the OS default action for `signal` and raises it on this process. For a
// terminating signal that is the end: the parent sees a genuine signal death, and a shell
// reports the conventional 128+signum status. Falls through only if the signal is blocked
// or otherwise does not terminate, in which case the caller decides what to do.
#[cfg(unix)]
pub(crate) fn raise_default(signal: libc::c_int) {
    unsafe {
        libc::signal(signal, libc::SIG_DFL);
        libc::raise(signal);
    }
}

#[cfg(unix)]
fn fallback(signal: libc::c_int) {
    match signal {
        // The daemon declined, or did not answer: take the signal's default action, which
        // for these is to terminate (QUIT with a core dump), as if it had never been caught.
        libc::SIGINT | libc::SIGTERM | libc::SIGHUP | libc::SIGQUIT => raise_default(signal),
        // WINCH, USR1, USR2: the default action is to ignore, so the signal is dropped.
        _ => {}
    }
}

// Installs `handler` for `signal` unless the signal is inherited ignored. A disposition of
// `SIG_IGN` survives exec by design — it is how `nohup` and `trap '' INT` ask that a command
// never see the signal — and a launcher that overrode it would then, on a rejected forward,
// restore the default action and die of a signal its caller had asked it to ignore.
// `sigaction` rather than `signal`, so restart semantics are explicit rather than
// implementation-defined: the session's blocking reads must resume after a handler runs.
// Every forwarded signal is masked while a handler runs, so one request and its answer are
// never interleaved with another's.
#[cfg(unix)]
pub(crate) fn install_handler(signal: libc::c_int, handler: extern "C" fn(libc::c_int)) {
    unsafe {
        let mut current: libc::sigaction = std::mem::zeroed();
        if libc::sigaction(signal, std::ptr::null(), &mut current) != 0 { return; }
        if current.sa_sigaction == libc::SIG_IGN {
            crate::debug!("signals: {} is inherited ignored; leaving it", signal);
            return;
        }
        let mut action: libc::sigaction = std::mem::zeroed();
        action.sa_sigaction = handler as *const () as libc::sighandler_t;
        action.sa_flags = libc::SA_RESTART;
        libc::sigemptyset(&mut action.sa_mask);
        for forwarded in FORWARDED { libc::sigaddset(&mut action.sa_mask, forwarded); }
        libc::sigaction(signal, &action, std::ptr::null_mut());
    }
}

#[cfg(unix)]
fn pipe() -> Option<(libc::c_int, libc::c_int)> {
    let mut fds = [0 as libc::c_int; 2];
    if unsafe { libc::pipe(fds.as_mut_ptr()) } != 0 { return None; }
    for fd in fds {
        unsafe { libc::fcntl(fd, libc::F_SETFD, libc::FD_CLOEXEC); }
    }
    Some((fds[0], fds[1]))
}

#[cfg(unix)]
pub fn install(signaler: Arc<Signaler>, termination: Arc<AtomicI32>, saved_tty: TtyState, raw_mode_owned: bool) {
    install_state(signaler, termination, saved_tty, raw_mode_owned);

    let (Some((request_read, request_write)), Some((answer_read, answer_write))) = (pipe(), pipe()) else {
        crate::debug!("signals: no pipes; signals will not be forwarded");
        return;
    };
    REQUEST_WRITE.store(request_write, Ordering::SeqCst);
    ANSWER_READ.store(answer_read, Ordering::SeqCst);
    std::thread::spawn(move || courier(request_read, answer_write));

    for signal in FORWARDED { install_handler(signal, handler); }
}

#[cfg(unix)]
extern "C" fn handler(signal: libc::c_int) {
    match signal {
        libc::SIGTSTP => suspend(),
        libc::SIGCONT => resume(),
        libc::SIGINT | libc::SIGQUIT if CHILD_RUNNING.load(Ordering::SeqCst) => {}
        _ => match request_from_handler(signal) {
            SignalAck::Accept                      => {}
            SignalAck::Reject | SignalAck::Timeout => fallback(signal),
        },
    }
}

// A stop request: tell the daemon, put the terminal back the way the shell expects to
// find it, and then actually stop, by taking the default action for TSTP. The signal is
// blocked while its own handler runs, so it is unblocked explicitly; the stop then happens
// here, and execution resumes here on SIGCONT, when the handler is put back.
#[cfg(unix)]
fn suspend() {
    let _ = request_from_handler(libc::SIGTSTP);
    if RAW_MODE_OWNED.load(Ordering::SeqCst) {
        if let Some(saved) = SAVED_TTY.get() { crate::tty::restore_tty_state(saved); }
    }
    unsafe {
        libc::signal(libc::SIGTSTP, libc::SIG_DFL);
        libc::raise(libc::SIGTSTP);
        let mut set: libc::sigset_t = std::mem::zeroed();
        libc::sigemptyset(&mut set);
        libc::sigaddset(&mut set, libc::SIGTSTP);
        libc::sigprocmask(libc::SIG_UNBLOCK, &set, std::ptr::null_mut());
    }
    // Stopped above; continuing here.
    install_handler(libc::SIGTSTP, handler);
}

// Continued after a stop: re-apply raw mode if this launcher owns the terminal's mode and
// is (still, or again) in the foreground — from the background, tcsetattr would stop the
// job once more — and tell the daemon, so an application can redraw. The window may have
// been resized while the job was stopped; the daemon learns the new size the same way it
// does for WINCH.
#[cfg(unix)]
fn resume() {
    if RAW_MODE_OWNED.load(Ordering::SeqCst) && crate::tty::in_foreground() {
        crate::tty::set_raw_mode();
    }
    let _ = request_from_handler(libc::SIGCONT);
}

// The end of a launcher that was told to terminate: the invocation's streams are drained,
// the terminal restored, and the process now dies of the signal it was sent, so that its
// parent sees a signal death rather than an exit status that merely imitates one.
#[cfg(unix)]
pub fn die(signal: i32) -> ! {
    raise_default(signal);
    std::process::exit(128 + signal)
}

// ── Windows ───────────────────────────────────────────────────────────────────

// The exit status of a process that died of a console control event, by Windows's own
// convention (`STATUS_CONTROL_C_EXIT`, the status the system assigns when no handler runs).
#[cfg(windows)]
pub const CONTROL_EXIT_STATUS: i32 = 0xC000_013Au32 as i32;

#[cfg(windows)]
fn fallback(name: &str) {
    match name {
        "CTRL_C" | "CTRL_BREAK" | "CTRL_CLOSE" | "CTRL_LOGOFF" | "CTRL_SHUTDOWN" => {
            std::process::exit(CONTROL_EXIT_STATUS);
        }
        _ => {}
    }
}

// The console handler runs on a thread of its own, with no async-signal-safety rule, so it
// sends on the session directly.
#[cfg(windows)]
pub fn install(signaler: Arc<Signaler>, termination: Arc<AtomicI32>, saved_tty: TtyState, raw_mode_owned: bool) {
    use windows_sys::Win32::System::Console::SetConsoleCtrlHandler;
    install_state(signaler, termination, saved_tty, raw_mode_owned);
    unsafe { SetConsoleCtrlHandler(Some(console_handler), 1); }
}

#[cfg(windows)]
unsafe extern "system" fn console_handler(ctrl_type: u32) -> windows_sys::Win32::Foundation::BOOL {
    use windows_sys::Win32::System::Console::{
        CTRL_C_EVENT, CTRL_BREAK_EVENT, CTRL_CLOSE_EVENT, CTRL_LOGOFF_EVENT, CTRL_SHUTDOWN_EVENT,
    };
    let name = match ctrl_type {
        CTRL_C_EVENT        => "CTRL_C",
        CTRL_BREAK_EVENT    => "CTRL_BREAK",
        CTRL_CLOSE_EVENT    => "CTRL_CLOSE",
        CTRL_LOGOFF_EVENT   => "CTRL_LOGOFF",
        CTRL_SHUTDOWN_EVENT => "CTRL_SHUTDOWN",
        _ => return 0,
    };
    let ending = matches!(ctrl_type, CTRL_CLOSE_EVENT | CTRL_LOGOFF_EVENT | CTRL_SHUTDOWN_EVENT);
    // The system ends the process about five seconds after these, whatever it is doing;
    // the daemon is told, so the application can choose what to finish.
    let detail = SignalDetail {
        size: None,
        deadline_ms: if ending { Some(CONTROL_EVENT_DEADLINE_MS) } else { None },
    };
    let ack = forward(name, detail);
    if ending && ack == SignalAck::Accept { flag_termination(CONTROL_EXIT_STATUS); }
    match ack {
        SignalAck::Accept                      => {}
        SignalAck::Reject | SignalAck::Timeout => fallback(name),
    }
    1
}

#[cfg(windows)]
pub fn die(code: i32) -> ! {
    std::process::exit(code)
}

#[cfg(windows)]
const CONTROL_EVENT_DEADLINE_MS: u64 = 5000;

// Windows has no SIGWINCH. A resize is delivered as a console input record, but reading
// those would take keystrokes away from stdin, so the screen-buffer size is polled instead
// and a `WINCH` document sent, with the new size, when it changes.
#[cfg(windows)]
pub fn watch_for_resize() {
    const POLL: std::time::Duration = std::time::Duration::from_millis(200);
    std::thread::spawn(move || {
        let mut last = crate::tty::terminal_size();
        loop {
            std::thread::sleep(POLL);
            let current = crate::tty::terminal_size();
            if current.is_some() && current != last {
                last = current;
                let _ = forward("WINCH", SignalDetail { size: current, deadline_ms: None });
            }
        }
    });
}
