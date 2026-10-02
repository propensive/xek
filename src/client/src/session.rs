//! One invocation, one connection. After `init` the connection carries every stream of the
//! invocation — the client's stdin to the daemon, the invocation's stdout and stderr back,
//! and any descriptor the daemon asks to open — as `data` documents, with the control
//! traffic (`signal`/`signal-ack`, `mode`, `closed`, `end`, `credit`, `open`) in between, until
//! the daemon's `exit-status` ends it. The rules are in `spec/launcher.md`, *Flow control*;
//! this is their launcher half.
//!
//! The shape that keeps one connection from blocking itself: on each side one thread reads
//! the socket and one writes it, and nothing else touches it. Every stream has a window of
//! credit, granted by its receiver as it consumes, and a producer waits for credit *before* it
//! enqueues a chunk — outside any lock — so the reader thread can always deposit what arrives
//! without blocking, and the writer thread's `write` always completes because the far reader
//! never blocks either. Control documents go ahead of data in the writer's queue. A mutex
//! around `write` would not do: both directions saturated, each side's reader waiting for its
//! writer lock to send credit while the holder waits for the other side to drain, is a
//! four-party deadlock.

use std::collections::{HashMap, HashSet, VecDeque};
use std::io::{self, Read, Write};
use std::net::Shutdown;
use std::process::Command;
use std::sync::{Arc, Condvar, Mutex};
use std::thread::JoinHandle;
use std::time::Duration;

use crate::bintel::{self, Composition, Message, MAXIMUM_CHUNK};
use crate::descriptors::{self, Descriptor};
use crate::protocol::{self, ClientInfo, SignalAck, SignalDetail};
use crate::tty::TtyState;
use crate::uds::UnixStream;

/// The credit each stream opens with, in each direction (`spec/launcher.md`).
pub const WINDOW: u64 = 65536;

/// How a session ended.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Outcome {
    /// The daemon reported the invocation's exit status.
    Exited(i32),
    /// The daemon closed the connection before sending anything: it did not understand
    /// `init`, which is to say it speaks another protocol.
    Refused,
    /// The connection ended after the session had begun but before `exit-status`.
    Dropped,
}

// ── The writer thread's queue ─────────────────────────────────────────────────

struct OutboxState {
    control: VecDeque<Vec<u8>>,
    data: VecDeque<Vec<u8>>,
    closed: bool,
}

/// Frames waiting for the writer thread, in two lanes: control documents go first.
pub struct Outbox {
    state: Mutex<OutboxState>,
    ready: Condvar,
}

impl Outbox {
    fn new() -> Arc<Outbox> {
        Arc::new(Outbox {
            state: Mutex::new(OutboxState { control: VecDeque::new(), data: VecDeque::new(), closed: false }),
            ready: Condvar::new(),
        })
    }

    pub fn send(&self, frame: Vec<u8>) {
        let mut state = self.state.lock().unwrap();
        if state.closed { return; }
        state.control.push_back(frame);
        self.ready.notify_one();
    }

    fn send_data(&self, frame: Vec<u8>) {
        let mut state = self.state.lock().unwrap();
        if state.closed { return; }
        state.data.push_back(frame);
        self.ready.notify_one();
    }

    pub fn closed(&self) -> bool { self.state.lock().unwrap().closed }

    fn close(&self) {
        self.state.lock().unwrap().closed = true;
        self.ready.notify_all();
    }

    // The writer thread: the only caller of `write` on the socket.
    fn run(&self, mut socket: UnixStream) {
        loop {
            let frame = {
                let mut state = self.state.lock().unwrap();
                loop {
                    if let Some(frame) = state.control.pop_front().or_else(|| state.data.pop_front()) { break frame; }
                    if state.closed { return; }
                    state = self.ready.wait(state).unwrap();
                }
            };
            if socket.write_all(&frame).is_err() {
                crate::debug!("session: the connection cannot be written; closing the outbox");
                self.close();
                return;
            }
        }
    }
}

// ── Credit for the streams the launcher sends ─────────────────────────────────

struct CreditState {
    // A stream absent here has ended, or was never open: its producer stops.
    streams: HashMap<String, u64>,
    closed: bool,
}

struct Credits {
    state: Mutex<CreditState>,
    changed: Condvar,
}

impl Credits {
    fn new() -> Arc<Credits> {
        Arc::new(Credits { state: Mutex::new(CreditState { streams: HashMap::new(), closed: false }), changed: Condvar::new() })
    }

    fn open(&self, stream: &str) {
        self.state.lock().unwrap().streams.insert(stream.to_owned(), WINDOW);
    }

    fn grant(&self, stream: &str, bytes: u64) {
        let mut state = self.state.lock().unwrap();
        if let Some(credit) = state.streams.get_mut(stream) {
            *credit = credit.saturating_add(bytes);
            self.changed.notify_all();
        }
    }

    // Ends a stream: its producer's next `take` returns `None`.
    fn revoke(&self, stream: &str) {
        self.state.lock().unwrap().streams.remove(stream);
        self.changed.notify_all();
    }

    // Waits until the stream has credit and says how much may be sent, up to `want`. `None`
    // once the stream, or the session, is over. Nothing is charged until `spend`: a read may
    // deliver less than it was allowed.
    fn allowance(&self, stream: &str, want: usize) -> Option<usize> {
        let mut state = self.state.lock().unwrap();
        loop {
            if state.closed { return None; }
            let credit = *state.streams.get(stream)?;
            if credit > 0 { return Some(want.min(credit as usize).min(MAXIMUM_CHUNK)); }
            state = self.changed.wait(state).unwrap();
        }
    }

    // Whether the stream is still the launcher's to carry: not revoked by `closed`, in a
    // session still running.
    fn active(&self, stream: &str) -> bool {
        let state = self.state.lock().unwrap();
        !state.closed && state.streams.contains_key(stream)
    }

    fn spend(&self, stream: &str, bytes: usize) {
        if let Some(credit) = self.state.lock().unwrap().streams.get_mut(stream) {
            *credit = credit.saturating_sub(bytes as u64);
        }
    }

    fn close(&self) {
        self.state.lock().unwrap().closed = true;
        self.changed.notify_all();
    }
}

// ── A stream the daemon sends ─────────────────────────────────────────────────

struct InletState {
    queue: VecDeque<Vec<u8>>,
    ended: bool,
    // Set once the client's side cannot be written: chunks are consumed and dropped, so the
    // daemon is never blocked on a stream nobody reads.
    discard: bool,
    // Set while a command the daemon asked for owns the terminal: chunks wait, so the
    // invocation's output does not interleave with the command's.
    held: bool,
}

/// The chunks of one inbound stream, between the reader thread and the thread that writes
/// them to the client's descriptor. Bounded by the credit the launcher has granted, which is
/// only ever as much as it has consumed.
struct Inlet {
    stream: String,
    state: Mutex<InletState>,
    ready: Condvar,
}

impl Inlet {
    fn new(stream: &str) -> Arc<Inlet> {
        Arc::new(Inlet {
            stream: stream.to_owned(),
            state: Mutex::new(InletState { queue: VecDeque::new(), ended: false, discard: false, held: false }),
            ready: Condvar::new(),
        })
    }

    fn push(&self, bytes: Vec<u8>) {
        let mut state = self.state.lock().unwrap();
        if state.ended { return; }
        state.queue.push_back(bytes);
        self.ready.notify_one();
    }

    fn end(&self) {
        self.state.lock().unwrap().ended = true;
        self.ready.notify_all();
    }

    fn discard(&self) {
        self.state.lock().unwrap().discard = true;
    }

    fn hold(&self, held: bool) {
        self.state.lock().unwrap().held = held;
        self.ready.notify_all();
    }

    // Writes the stream to `sink` as chunks arrive, granting credit as it goes, until the
    // stream ends. On the first failed write the daemon is told the stream is closed and the
    // rest is drained and dropped.
    fn drain(&self, mut sink: impl Write, outbox: &Outbox, composition: &Composition) {
        let mut consumed: u64 = 0;
        loop {
            let chunk = {
                let mut state = self.state.lock().unwrap();
                loop {
                    if !state.held {
                        if let Some(chunk) = state.queue.pop_front() { break Some((chunk, state.discard)); }
                        if state.ended { break None; }
                    }
                    state = self.ready.wait(state).unwrap();
                }
            };
            let Some((chunk, discard)) = chunk else { break };
            if !discard {
                if sink.write_all(&chunk).and_then(|_| sink.flush()).is_err() {
                    crate::debug!("session: {} has no reader; telling the daemon", self.stream);
                    self.discard();
                    outbox.send(protocol::closed_document(&self.stream, composition));
                }
            }
            consumed += chunk.len() as u64;
            // Credit is granted in halves of the window, not per chunk, to halve the chatter.
            if consumed >= WINDOW / 2 {
                outbox.send(protocol::credit_document(&self.stream, consumed, composition));
                consumed = 0;
            }
        }
        let _ = sink.flush();
    }
}

// A client descriptor as a `Write`, for a descriptor the daemon opened to write.
struct FdSink(i32);

impl Write for FdSink {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        descriptors::write_all(self.0, bytes).map(|_| bytes.len())
    }
    fn flush(&mut self) -> io::Result<()> { Ok(()) }
}

// ── Pausing the terminal's pump ───────────────────────────────────────────────

// The terminal's pump reads stdin; a command the daemon asks to run on the terminal must
// read it instead, uncontested. The pump never sits inside `read` without input to take:
// it waits for input in short polls, and between polls it parks here when asked, so `pause`
// returns only once the pump is out of the way.
struct Gate {
    state: Mutex<(bool, bool)>, // (pause requested, pump parked)
    changed: Condvar,
}

impl Gate {
    fn new() -> Gate { Gate { state: Mutex::new((false, false)), changed: Condvar::new() } }

    fn pause(&self) {
        let mut state = self.state.lock().unwrap();
        state.0 = true;
        self.changed.notify_all();
        while !state.1 { state = self.changed.wait(state).unwrap(); }
    }

    fn resume(&self) {
        let mut state = self.state.lock().unwrap();
        state.0 = false;
        self.changed.notify_all();
    }

    // Parks if a pause is requested, until resumed; otherwise returns at once.
    fn park(&self) {
        let mut state = self.state.lock().unwrap();
        if state.0 {
            state.1 = true;
            self.changed.notify_all();
            while state.0 { state = self.changed.wait(state).unwrap(); }
            state.1 = false;
        }
    }

    // Lets `pause` through when there is no pump to wait for.
    fn vacate(&self) {
        let mut state = self.state.lock().unwrap();
        state.1 = true;
        self.changed.notify_all();
    }
}

// ── Signals ───────────────────────────────────────────────────────────────────

/// Sends a `signal` and waits for its `signal-ack`: what the signal handlers (`signals.rs`)
/// hold. One signal is outstanding at a time; the daemon answers in order.
pub struct Signaler {
    outbox: Arc<Outbox>,
    composition: Composition,
    serial: Mutex<()>,
    answer: Mutex<Option<bool>>,
    answered: Condvar,
}

impl Signaler {
    pub fn signal(&self, name: &str, detail: SignalDetail, timeout: Duration) -> SignalAck {
        let _one_at_a_time = self.serial.lock().unwrap();
        if self.outbox.closed() { return SignalAck::Timeout; }
        *self.answer.lock().unwrap() = None;
        self.outbox.send(protocol::signal_document(name, detail, &self.composition));
        let answer = self.answer.lock().unwrap();
        let (answer, _) = self.answered.wait_timeout_while(answer, timeout, |answer| answer.is_none()).unwrap();
        match *answer {
            Some(true) => SignalAck::Accept,
            Some(false) => SignalAck::Reject,
            None => SignalAck::Timeout,
        }
    }

    fn acknowledge(&self, accept: bool) {
        *self.answer.lock().unwrap() = Some(accept);
        self.answered.notify_all();
    }
}

// ── The session ───────────────────────────────────────────────────────────────

/// What the invocation's stdin is carried from.
pub enum Stdin {
    /// The terminal the launcher owns, read by a pump that can be paused while a command the
    /// daemon asked for owns the terminal; `leftover` is what the user typed during the
    /// handshake, sent first.
    Terminal { leftover: Vec<u8> },
    /// A pipe or a file, read until it ends.
    Reader(Box<dyn Read + Send>),
    /// Nothing: presented to the daemon as already ended.
    Ended,
}

pub struct Options {
    /// The terminal's saved state when the launcher owns the terminal's mode and so applies
    /// `mode` documents and runs commands on it; `None` for a pipe, a background job, or an
    /// internal invocation.
    pub tty: Option<TtyState>,
    pub stdin: Stdin,
}

pub struct Session {
    outbox: Arc<Outbox>,
    credits: Arc<Credits>,
    inlets: Mutex<HashMap<String, Arc<Inlet>>>,
    drains: Mutex<Vec<JoinHandle<()>>>,
    descriptors: Vec<Descriptor>,
    opened: Mutex<HashSet<i32>>,
    signaler: Arc<Signaler>,
    composition: Composition,
    tty: Option<TtyState>,
    gate: Gate,
    // One command on the terminal at a time.
    child: Mutex<()>,
    outcome: Mutex<Option<Outcome>>,
    done: Condvar,
    socket: UnixStream,
}

/// Sends `init` on `socket` and starts the session's threads: the writer, the reader, the
/// stdin pump, and a drain each for stdout and stderr.
pub fn open(socket: UnixStream, info: &ClientInfo, composition: Composition, options: Options) -> Arc<Session> {
    let mut init_socket = socket.try_clone().expect("clone the session socket");
    protocol::send_init(&mut init_socket, info, &composition);

    let outbox = Outbox::new();
    let signaler = Arc::new(Signaler {
        outbox: outbox.clone(),
        composition: composition.clone(),
        serial: Mutex::new(()),
        answer: Mutex::new(None),
        answered: Condvar::new(),
    });
    let session = Arc::new(Session {
        outbox: outbox.clone(),
        credits: Credits::new(),
        inlets: Mutex::new(HashMap::new()),
        drains: Mutex::new(Vec::new()),
        descriptors: info.descriptors.clone(),
        opened: Mutex::new(HashSet::new()),
        signaler,
        composition,
        tty: options.tty,
        gate: Gate::new(),
        child: Mutex::new(()),
        outcome: Mutex::new(None),
        done: Condvar::new(),
        socket: socket.try_clone().expect("clone the session socket"),
    });

    let writer_socket = socket.try_clone().expect("clone the session socket");
    let writer_outbox = outbox.clone();
    std::thread::spawn(move || writer_outbox.run(writer_socket));

    session.inlet("stdout", io::stdout(), None);
    session.inlet("stderr", io::stderr(), None);

    session.credits.open("stdin");
    match options.stdin {
        Stdin::Terminal { leftover } => {
            let pump = session.clone();
            std::thread::spawn(move || pump.pump_terminal(leftover));
        }
        Stdin::Reader(reader) => {
            session.gate.vacate();
            let pump = session.clone();
            std::thread::spawn(move || pump.pump("stdin", reader));
        }
        Stdin::Ended => {
            session.gate.vacate();
            outbox.send_data(protocol::end_document("stdin", &session.composition));
        }
    }

    let reader = session.clone();
    std::thread::spawn(move || reader.read(socket));
    session
}

impl Session {
    pub fn signaler(&self) -> Arc<Signaler> { self.signaler.clone() }

    // Starts an inbound stream, drained to `sink` on a thread of its own. A descriptor's `fd`
    // is closed once the stream ends, so whatever reads the other end of it sees end-of-file
    // then, not at the launcher's exit.
    fn inlet(self: &Arc<Self>, stream: &str, sink: impl Write + Send + 'static, fd: Option<i32>) {
        let inlet = Inlet::new(stream);
        self.inlets.lock().unwrap().insert(stream.to_owned(), inlet.clone());
        let session = self.clone();
        let handle = std::thread::spawn(move || {
            inlet.drain(sink, &session.outbox, &session.composition);
            if let Some(fd) = fd { descriptors::close(fd); }
        });
        self.drains.lock().unwrap().push(handle);
    }

    // Carries `reader` to the daemon as `stream`, a chunk per grant of credit, then `end`.
    fn pump(&self, stream: &str, mut reader: impl Read) {
        let mut buffer = vec![0u8; MAXIMUM_CHUNK];
        while let Some(allowed) = self.credits.allowance(stream, MAXIMUM_CHUNK) {
            match reader.read(&mut buffer[..allowed]) {
                Ok(0) => break,
                Err(error) => { crate::debug!("session: {} cannot be read: {}", stream, error); break }
                Ok(count) => {
                    self.credits.spend(stream, count);
                    self.outbox.send_data(protocol::data_document(stream, &buffer[..count], &self.composition));
                }
            }
        }
        // In the data lane, behind every chunk of the stream: `end` must not overtake them. A
        // stream the daemon closed, or a session that is over, has no reader for it.
        if self.credits.active(stream) {
            crate::debug!("session: {} exhausted", stream);
            self.outbox.send_data(protocol::end_document(stream, &self.composition));
        }
    }

    // The terminal's stdin, carried as `stdin`: what was typed during the handshake first,
    // then the terminal, waited for in short polls rather than inside `read`, so the pump can
    // be parked while a command the daemon asked for owns the terminal.
    fn pump_terminal(&self, leftover: Vec<u8>) {
        let mut pending = leftover;
        let mut buffer = vec![0u8; MAXIMUM_CHUNK];
        let mut ended = false;
        while !ended {
            let Some(allowed) = self.credits.allowance("stdin", MAXIMUM_CHUNK) else { break };
            if !pending.is_empty() {
                let take = allowed.min(pending.len());
                let chunk: Vec<u8> = pending.drain(..take).collect();
                self.credits.spend("stdin", chunk.len());
                self.outbox.send_data(protocol::data_document("stdin", &chunk, &self.composition));
                continue;
            }
            loop {
                self.gate.park();
                if crate::tty::wait_input(100) { break; }
            }
            match crate::descriptors::read(0, &mut buffer[..allowed]) {
                Ok(0) => ended = true,
                Err(error) => { crate::debug!("session: the terminal cannot be read: {}", error); ended = true }
                Ok(count) => {
                    self.credits.spend("stdin", count);
                    self.outbox.send_data(protocol::data_document("stdin", &buffer[..count], &self.composition));
                }
            }
        }
        self.gate.vacate();
        if self.credits.active("stdin") {
            crate::debug!("session: the terminal's input ended");
            self.outbox.send_data(protocol::end_document("stdin", &self.composition));
        }
    }

    // The reader thread: the only caller of `read` on the socket. Deposits each document where
    // it belongs and never waits on anything but the socket.
    fn read(self: Arc<Self>, mut socket: UnixStream) {
        let mut received = false;
        let outcome = loop {
            let document = match bintel::read_document(&mut socket) {
                Ok(document) => document,
                Err(_) => break if received { Outcome::Dropped } else { Outcome::Refused },
            };
            received = true;
            match bintel::parse(&document, &self.composition) {
                Some(Message::Data { stream, bytes }) => {
                    if let Some(inlet) = self.inlets.lock().unwrap().get(&stream) { inlet.push(bytes); }
                }
                Some(Message::End { stream }) => {
                    if let Some(inlet) = self.inlets.lock().unwrap().remove(&stream) { inlet.end(); }
                }
                Some(Message::Credit { stream, bytes }) => self.credits.grant(&stream, bytes),
                Some(Message::Open { stream }) => self.open_descriptor(&stream),
                Some(Message::Closed { stream }) => self.credits.revoke(&stream),
                Some(Message::Mode { canonical, echo }) => if let Some(saved) = self.tty {
                    if canonical { crate::tty::set_cooked_mode(&saved) } else { crate::tty::set_raw_mode() }
                    crate::tty::set_echo(echo);
                },
                Some(Message::Run { command, arguments, pwd }) => {
                    let session = self.clone();
                    std::thread::spawn(move || session.run_command(command, arguments, pwd));
                }
                Some(Message::SignalAck { accept }) => self.signaler.acknowledge(accept),
                Some(Message::ExitStatus { code }) => break Outcome::Exited(code),
                other => crate::debug!("session: unexpected document {:?}", other),
            }
        };
        crate::debug!("session: over: {:?}", outcome);
        // Everything waiting on the session is released: producers stop, drains finish what
        // they hold, a signal in flight times out.
        self.credits.close();
        self.outbox.close();
        for inlet in self.inlets.lock().unwrap().drain().map(|(_, inlet)| inlet) { inlet.end(); }
        *self.outcome.lock().unwrap() = Some(outcome);
        self.done.notify_all();
    }

    // The daemon asks for a descriptor the client advertised. The standard three are the
    // session's own streams and are never opened this way; an unknown or already open one is
    // ignored, as the schema says.
    fn open_descriptor(self: &Arc<Self>, stream: &str) {
        let Some(descriptor) = stream.parse::<i32>().ok()
            .filter(|fd| *fd > 2)
            .and_then(|fd| self.descriptors.iter().find(|d| d.fd == fd).cloned())
        else { return };
        if !self.opened.lock().unwrap().insert(descriptor.fd) { return; }
        crate::debug!("session: opening descriptor {} ({}, {})", descriptor.fd, descriptor.direction, descriptor.kind);
        if descriptor.direction.contains('r') {
            self.credits.open(stream);
            let session = self.clone();
            let name = stream.to_owned();
            std::thread::spawn(move || {
                session.pump(&name, FdSource(descriptor.fd));
                descriptors::close(descriptor.fd);
            });
        }
        if descriptor.direction.contains('w') {
            self.inlet(stream, FdSink(descriptor.fd), Some(descriptor.fd));
        }
    }

    // Runs a command on the client's terminal for the daemon (`spec/launcher.md`, *Running a
    // command on the client's terminal*): the terminal's pump parked and the invocation's
    // output held, the terminal in its saved state, the command on the launcher's own
    // standard streams and environment; then the terminal back in raw mode, everything
    // resumed, and `exited` with the status. Not interactive: `exited` 127 at once.
    fn run_command(&self, command: String, arguments: Vec<String>, pwd: Option<String>) {
        let _one_at_a_time = self.child.lock().unwrap();
        let code = match self.tty {
            None => 127,
            Some(saved) => {
                self.gate.pause();
                for stream in ["stdout", "stderr"] {
                    if let Some(inlet) = self.inlets.lock().unwrap().get(stream) { inlet.hold(true); }
                }
                let _ = io::stdout().flush();
                crate::tty::restore_tty_state(&saved);
                crate::signals::child_running(true);
                let mut child = Command::new(&command);
                child.args(&arguments);
                if let Some(pwd) = pwd { child.current_dir(pwd); }
                let code = match child.status() {
                    Ok(status) => exit_code(status),
                    Err(error) => { crate::debug!("session: cannot run {}: {}", command, error); 127 }
                };
                crate::signals::child_running(false);
                if crate::tty::in_foreground() { crate::tty::set_raw_mode(); }
                for stream in ["stdout", "stderr"] {
                    if let Some(inlet) = self.inlets.lock().unwrap().get(stream) { inlet.hold(false); }
                }
                self.gate.resume();
                code
            }
        };
        crate::debug!("session: the command {} exited with {}", command, code);
        self.outbox.send(protocol::exited_document(code, &self.composition));
    }

    /// Stops writing the invocation's stdout to the client, dropping what still arrives:
    /// after an accepted termination, nothing more of it is wanted.
    pub fn discard_stdout(&self) {
        if let Some(inlet) = self.inlets.lock().unwrap().get("stdout") { inlet.discard(); }
    }

    /// Ends the session from this side: the reader sees the connection close.
    pub fn close(&self) {
        let _ = self.socket.shutdown(Shutdown::Both);
    }

    /// Waits for the session to end, then for stdout and stderr to be written out.
    pub fn wait(&self) -> Outcome {
        let outcome = {
            let guard = self.outcome.lock().unwrap();
            let guard = self.done.wait_while(guard, |outcome| outcome.is_none()).unwrap();
            guard.unwrap()
        };
        let drains: Vec<JoinHandle<()>> = self.drains.lock().unwrap().drain(..).collect();
        for drain in drains { let _ = drain.join(); }
        outcome
    }
}

// A command's status as a shell reports it: its exit code, or 128 plus the signal it died of.
fn exit_code(status: std::process::ExitStatus) -> i32 {
    #[cfg(unix)]
    {
        use std::os::unix::process::ExitStatusExt;
        if let Some(signal) = status.signal() { return 128 + signal; }
    }
    status.code().unwrap_or(127)
}

// A client descriptor as a `Read`, for a descriptor the daemon opened to read.
struct FdSource(i32);

impl Read for FdSource {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> { descriptors::read(self.0, buffer) }
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use crate::bintel::{document, variant, Record};
    use std::os::unix::net::UnixStream as StdUnixStream;

    fn info(descriptors: Vec<Descriptor>) -> ClientInfo {
        ClientInfo {
            pid: 1, user_id: "1".into(), user_name: "u".into(), script: "/x".into(), invoked_as: None,
            pwd: "/".into(), args: vec![], env: vec![], stdin_tty: false, stdout_tty: false, stderr_tty: false,
            umask: None, size: None, codepages: None, descriptors, raw: vec![],
        }
    }

    // A daemon stand-in on the other end of a socketpair: reads documents and answers.
    struct Peer { socket: StdUnixStream, composition: Composition }

    impl Peer {
        fn next(&mut self) -> Message {
            let document = bintel::read_document(&mut self.socket).expect("a document");
            // The peer reads what the launcher sends, which `parse` does not table; walk the
            // frame by hand for the few launcher-side variants the tests need.
            parse_outbound(&document, &self.composition)
        }
        fn send(&mut self, variant: u64, record: Record) {
            self.socket.write_all(&document(variant, record, &self.composition)).unwrap();
        }
        fn exit(&mut self, code: i32) {
            let mut record = Record::new();
            record.scalar(0, &code.to_string());
            self.send(variant::EXIT_STATUS, record);
        }
    }

    fn parse_outbound(document: &[u8], composition: &Composition) -> Message {
        // magic(4) length(varint) siglen(1) sig(33) root(1) variant(1) count(1) fields…
        let (_, n) = bintel::decode_varint(&document[4..]).unwrap();
        let mut cur = 4 + n + 1 + composition.signature.len() + 1;
        let variant = document[cur] as u64; cur += 1;
        let count = document[cur]; cur += 1;
        let mut scalars: Vec<(u64, Vec<u8>)> = Vec::new();
        let mut flags = Vec::new();
        for _ in 0..count {
            let index = document[cur] as u64; cur += 1;
            // Every launcher-side field the tests look at is a scalar except init's flags,
            // which are not exercised here.
            let (length, n) = bintel::decode_varint(&document[cur..]).unwrap(); cur += n;
            if variant == variant::INIT && matches!(index, 5 | 6 | 7) { flags.push(index); cur -= n; continue; }
            scalars.push((index, document[cur..cur + length as usize].to_vec()));
            cur += length as usize;
        }
        let text = |i: u64| String::from_utf8(scalars.iter().find(|(j, _)| *j == i).unwrap().1.clone()).unwrap();
        match variant {
            variant::DATA => Message::Data { stream: text(0), bytes: scalars.iter().find(|(j, _)| *j == 1).unwrap().1.clone() },
            variant::END => Message::End { stream: text(0) },
            variant::CREDIT => Message::Credit { stream: text(0), bytes: text(1).parse().unwrap() },
            variant::CLOSED => Message::Closed { stream: text(0) },
            variant::SIGNAL => Message::Open { stream: text(0) }, // the name, reusing a variant with one text field
            variant::EXITED => Message::ExitStatus { code: text(0).parse().unwrap() },
            variant::INIT => Message::Open { stream: "init".into() },
            other => panic!("unexpected outbound variant {other}"),
        }
    }

    fn pair() -> (UnixStream, Peer) {
        let (ours, theirs) = StdUnixStream::pair().unwrap();
        theirs.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        (ours, Peer { socket: theirs, composition: Composition::base() })
    }

    #[test]
    fn stdin_is_carried_in_credited_chunks_and_then_ended() {
        let (socket, mut peer) = pair();
        let input: Vec<u8> = (0..200_000u32).map(|i| i as u8).collect();
        let session = open(socket, &info(vec![]), Composition::base(),
                           Options { tty: None, stdin: Stdin::Reader(Box::new(io::Cursor::new(input.clone()))) });
        assert_eq!(peer.next(), Message::Open { stream: "init".into() });

        let mut received = Vec::new();
        // The opening window is 64 KiB: that much arrives unprompted, and no more until credit.
        loop {
            match peer.next() {
                Message::Data { stream, bytes } => { assert_eq!(stream, "stdin"); received.extend(bytes); }
                other => panic!("{other:?}"),
            }
            if received.len() as u64 >= WINDOW { break; }
        }
        assert_eq!(received.len() as u64, WINDOW);
        peer.socket.set_read_timeout(Some(Duration::from_millis(200))).unwrap();
        assert!(bintel::read_document(&mut peer.socket).is_err(), "sent beyond its credit");
        peer.socket.set_read_timeout(Some(Duration::from_secs(5))).unwrap();

        let mut record = Record::new();
        record.scalar(0, "stdin");
        record.scalar(1, "1000000");
        peer.send(variant::CREDIT, record);
        loop {
            match peer.next() {
                Message::Data { bytes, .. } => received.extend(bytes),
                Message::End { stream } => { assert_eq!(stream, "stdin"); break; }
                other => panic!("{other:?}"),
            }
        }
        assert_eq!(received, input);
        peer.exit(7);
        assert_eq!(session.wait(), Outcome::Exited(7));
    }

    #[test]
    fn a_signal_is_acknowledged_inline() {
        let (socket, mut peer) = pair();
        let session = open(socket, &info(vec![]), Composition::base(), Options { tty: None, stdin: Stdin::Ended });
        assert_eq!(peer.next(), Message::Open { stream: "init".into() });
        assert_eq!(peer.next(), Message::End { stream: "stdin".into() });

        let signaler = session.signaler();
        let asker = std::thread::spawn(move || signaler.signal("USR1", SignalDetail::default(), Duration::from_secs(5)));
        assert_eq!(peer.next(), Message::Open { stream: "USR1".into() });
        let mut record = Record::new();
        record.flag(0);
        peer.send(variant::SIGNAL_ACK, record);
        assert_eq!(asker.join().unwrap(), SignalAck::Accept);

        // Unanswered: times out, and the session is undisturbed.
        let signaler = session.signaler();
        assert_eq!(signaler.signal("USR2", SignalDetail::default(), Duration::from_millis(100)), SignalAck::Timeout);
        assert_eq!(peer.next(), Message::Open { stream: "USR2".into() });
        peer.exit(0);
        assert_eq!(session.wait(), Outcome::Exited(0));
    }

    // With no terminal of its own, a `run` is refused at once with 127, as a shell reports a
    // command it could not run.
    #[test]
    fn a_run_without_a_terminal_is_refused() {
        let (socket, mut peer) = pair();
        let session = open(socket, &info(vec![]), Composition::base(), Options { tty: None, stdin: Stdin::Ended });
        assert_eq!(peer.next(), Message::Open { stream: "init".into() });
        assert_eq!(peer.next(), Message::End { stream: "stdin".into() });
        let mut record = Record::new();
        record.scalar(0, "vi");
        peer.send(variant::RUN, record);
        assert_eq!(peer.next(), Message::ExitStatus { code: 127 });
        peer.exit(0);
        assert_eq!(session.wait(), Outcome::Exited(0));
    }

    #[test]
    fn a_connection_closed_before_any_document_is_a_refusal() {
        let (socket, peer) = pair();
        let session = open(socket, &info(vec![]), Composition::base(), Options { tty: None, stdin: Stdin::Ended });
        drop(peer);
        assert_eq!(session.wait(), Outcome::Refused);
    }

    #[test]
    fn a_connection_dropped_mid_session_is_reported() {
        let (socket, mut peer) = pair();
        let session = open(socket, &info(vec![]), Composition::base(), Options { tty: None, stdin: Stdin::Ended });
        let mut record = Record::new();
        record.scalar(0, "stdout");
        record.scalar(1, "1");
        peer.send(variant::CREDIT, record);
        drop(peer);
        assert_eq!(session.wait(), Outcome::Dropped);
    }

    // A descriptor the daemon opens is carried from the client's pipe, and ended at its EOF.
    #[test]
    fn an_opened_descriptor_is_pumped_until_eof() {
        let mut pipe = [0i32; 2];
        assert_eq!(unsafe { libc::pipe(pipe.as_mut_ptr()) }, 0);
        let descriptor = Descriptor { fd: pipe[0], direction: "r", kind: "pipe", path: None };
        let (socket, mut peer) = pair();
        let session = open(socket, &info(vec![descriptor]), Composition::base(), Options { tty: None, stdin: Stdin::Ended });
        assert_eq!(peer.next(), Message::Open { stream: "init".into() });
        assert_eq!(peer.next(), Message::End { stream: "stdin".into() });

        descriptors::write_all(pipe[1], b"from the pipe").unwrap();
        descriptors::close(pipe[1]);
        let mut record = Record::new();
        record.scalar(0, &pipe[0].to_string());
        peer.send(variant::OPEN, record);
        assert_eq!(peer.next(), Message::Data { stream: pipe[0].to_string(), bytes: b"from the pipe".to_vec() });
        assert_eq!(peer.next(), Message::End { stream: pipe[0].to_string() });
        peer.exit(0);
        assert_eq!(session.wait(), Outcome::Exited(0));
    }
}
