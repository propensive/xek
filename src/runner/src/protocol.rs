use std::io::Write;
use std::path::Path;
use std::time::Duration;

use crate::bintel::{self, variant, Composition, Message, Record};
use crate::descriptors::Descriptor;
use crate::uds::UnixStream;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SignalAck {
    Accept,
    Reject,
    Timeout,
}

// What the launcher tells the daemon about an invocation, in the `init` document. Everything
// is text on the wire; see `spec/launcher.md` for what each value means and how it is found.
pub struct ClientInfo {
    pub pid:         u32,
    // The platform's identifier for the user: numeric on Unix, a SID on Windows.
    pub user_id:     String,
    pub user_name:   String,
    // The canonical path of the executable.
    pub script:      String,
    // argv[0] as the caller supplied it, for a multi-call binary to dispatch on.
    pub invoked_as:  Option<String>,
    pub pwd:         String,
    pub args:        Vec<String>,
    pub env:         Vec<String>,
    pub stdin_tty:   bool,
    pub stdout_tty:  bool,
    pub stderr_tty:  bool,
    // The client's umask, in octal, where the platform has one.
    pub umask:       Option<String>,
    // The terminal's size, when stdout is a terminal: (columns, rows).
    pub size:        Option<(u16, u16)>,
    // The console's input and output code pages, on Windows.
    pub codepages:   Option<(u32, u32)>,
    // The descriptors the client holds, which the daemon may open as streams.
    pub descriptors: Vec<Descriptor>,
}

// An invocation is one connection: it opens with `init`, written under the composition chosen
// for the invocation from the daemon's acceptance (`acceptance.rs`), and then carries the
// session's documents in both directions — `data`, `end`, `credit`, `open`, `signal`,
// `signal-ack`, `mode` and `closed` — until the daemon's `exit-status` ends it. `verify` is
// asked on a connection of its own and answered with one `verdict`. See `session.rs`.

pub fn init_document(info: &ClientInfo, composition: &Composition) -> Vec<u8> {
    let mut record = Record::new();
    record.scalar(0, &info.pid.to_string());
    record.scalar(1, &info.user_id);
    record.scalar(2, &info.user_name);
    record.scalar(3, &info.script);
    record.scalar(4, &info.pwd);
    if info.stdin_tty { record.flag(5); }
    if info.stdout_tty { record.flag(6); }
    if info.stderr_tty { record.flag(7); }
    for argument in &info.args { record.scalar(8, argument); }
    for variable in &info.env { record.scalar(9, variable); }
    if let Some(invoked_as) = &info.invoked_as { record.scalar(10, invoked_as); }
    if let Some(umask) = &info.umask { record.scalar(11, umask); }
    if let Some((columns, rows)) = info.size {
        record.scalar(12, &columns.to_string());
        record.scalar(13, &rows.to_string());
    }
    if let Some((input, output)) = info.codepages {
        record.scalar(14, &input.to_string());
        record.scalar(15, &output.to_string());
    }
    for descriptor in &info.descriptors {
        let mut inner = Record::new();
        inner.scalar(0, &descriptor.fd.to_string());
        inner.scalar(1, descriptor.direction);
        inner.scalar(2, descriptor.kind);
        if let Some(path) = &descriptor.path { inner.scalar(3, path); }
        record.record(16, inner);
    }
    bintel::document(variant::INIT, record, composition)
}

pub fn send_init(connection: &mut UnixStream, info: &ClientInfo, composition: &Composition) {
    let _ = connection.write_all(&init_document(info, composition));
    let _ = connection.flush();
}

fn stream_record(stream: &str) -> Record {
    let mut record = Record::new();
    record.scalar(0, stream);
    record
}

// One chunk of a stream the launcher carries to the daemon: stdin, or a descriptor the
// daemon opened to read.
pub fn data_document(stream: &str, bytes: &[u8], composition: &Composition) -> Vec<u8> {
    let mut record = stream_record(stream);
    record.bytes_field(1, bytes);
    bintel::document(variant::DATA, record, composition)
}

// The end of a stream the launcher carries: the daemon's reader of it sees end-of-file after
// every chunk sent before.
pub fn end_document(stream: &str, composition: &Composition) -> Vec<u8> {
    bintel::document(variant::END, stream_record(stream), composition)
}

// Credit for a stream the daemon carries: the launcher can take this many more bytes of it.
pub fn credit_document(stream: &str, bytes: u64, composition: &Composition) -> Vec<u8> {
    let mut record = stream_record(stream);
    record.scalar(1, &bytes.to_string());
    bintel::document(variant::CREDIT, record, composition)
}

// The named stream has lost its reader on the client's side — `mytool | head -1`, once head
// has gone — so the daemon can fail the invocation's further writes to it as a broken pipe
// would. Not answered.
pub fn closed_document(stream: &str, composition: &Composition) -> Vec<u8> {
    bintel::document(variant::CLOSED, stream_record(stream), composition)
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Verdict {
    Fresh,
    Stale,
}

// Ask the resident daemon whether the launcher it was started from still has the
// content it remembers — sent when the file's mtime disagrees with the build file's
// record, i.e. after a `touch` or a same-size rebuild. The daemon hashes at most once
// per change and remembers the answer, which a stateless launcher cannot. The verdict
// document says fresh or stale (the daemon then shuts down; await its death and launch
// afresh); anything else — including a daemon too old to speak this protocol, which just
// closes the connection — means proceed as normal.
pub fn verify(socket_path: &Path, composition: &Composition) -> Verdict {
    let mut connection = match UnixStream::connect(socket_path) {
        Ok(connection) => connection,
        Err(_) => return Verdict::Fresh,
    };
    let _ = connection.set_read_timeout(Some(REPLY_TIMEOUT));
    let _ = connection.write_all(&bintel::document(variant::VERIFY, Record::new(), composition));
    let _ = connection.flush();
    match bintel::read_document(&mut connection).ok().and_then(|doc| bintel::parse(&doc, composition)) {
        Some(Message::Verdict { fresh: false }) => Verdict::Stale,
        _ => Verdict::Fresh,
    }
}

// What travels with a signal's name: the terminal's size, for a signal that says it may have
// changed, or the time the system allows before it ends the client, for a Windows control
// event that comes with one.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct SignalDetail {
    pub size: Option<(u16, u16)>,
    pub deadline_ms: Option<u64>,
}

pub fn signal_document(name: &str, detail: SignalDetail, composition: &Composition) -> Vec<u8> {
    let mut record = Record::new();
    record.scalar(0, name);
    if let Some((columns, rows)) = detail.size {
        record.scalar(1, &columns.to_string());
        record.scalar(2, &rows.to_string());
    }
    if let Some(deadline) = detail.deadline_ms { record.scalar(3, &deadline.to_string()); }
    bintel::document(variant::SIGNAL, record, composition)
}

// How long a daemon that has accepted a connection is given to answer a question — a
// verdict — before the launcher treats it as wedged. An invocation's own running time is
// unbounded by design; only the daemon's replies are bounded.
pub const REPLY_TIMEOUT: Duration = Duration::from_secs(10);
