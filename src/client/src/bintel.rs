//! The subset of BinTEL the launcher protocol needs — §4 varints, §6.1 framing, the §7.1
//! node forms and the §8.2 palimpsest signature — written against one fixed schema,
//! `ethereal-launcher`, whose TEL text is `spec/ethereal-launcher.tel`, the contract between
//! this client and any daemon.
//!
//! The schema's keyword order is compiled in: the document root has a single `select Message`
//! member, so a message is the root node (child count 1) containing one variant node whose
//! keyword index is the variant's position in the select, followed by the variant record's
//! fields in declaration order. A field is a scalar (index, byte length, bytes — UTF-8 for a
//! `String`, raw for the `base-256` `Bytes`), a flag (index alone) or a record (index, child
//! count, its own fields).
//!
//! The schema is a *base* and, in time, *layers* (TEL §20.3): a layer appends optional members
//! to existing records, so the base's keyword indices never move and a layer's fields take the
//! indices after them. A composition — the base with the first `depth − 1` layers — is what a
//! document is written under, and its signature (BinTEL §8.2, a palimpsest of the components'
//! hashes) travels in every frame. Which composition an invocation uses is settled before its
//! first connection from the daemon's acceptance (`acceptance.rs`); a document carrying any
//! other signature is rejected before a field is read, so a client and a daemon that disagree
//! fail loudly rather than misread each other.
//!
//! No general TEL machinery is here — no schema parsing, no hashing, no BASE-256 alphabet —
//! because the client is a size-optimised launcher and the contract is fixed at build time.
//! The component hashes are pinned constants, and a signature is a few XORs over them.

use std::io::{self, Read};

/// §6.1 field 1: the external-schema magic number, `βτελ` in BASE-256.
pub const MAGIC: [u8; 4] = [0xB2, 0xC4, 0xB5, 0xBB];

/// The BLAKE3-256 value hash of the `ethereal-launcher` base schema — the schema with every
/// `layer` removed (BinTEL §8.1) — pinned here and in the daemon's tests. The base alone has
/// the 33-byte signature of this hash followed by its cadence trailer.
pub const BASE: [u8; 32] = [
    0x55, 0xd1, 0x8c, 0x24, 0x7b, 0x88, 0xdb, 0x8f, 0xc6, 0xaf, 0x7a, 0x19, 0x7c, 0x56, 0xee,
    0x2b, 0x68, 0x00, 0x84, 0xd1, 0x80, 0x0a, 0xfe, 0x89, 0x47, 0xa2, 0xf6, 0x13, 0xc7, 0x04,
    0x92, 0xe5,
];

/// The kind of a record field, per the schema.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Kind { Scalar, Flag }

/// One layer of `spec/ethereal-launcher.tel`: its value hash (BinTEL §8.1) and the members
/// it appends, in declaration order, to each variant's record. A layer only ever appends
/// optional members (see `spec/COMPATIBILITY.md`), so the fields of the base and of earlier
/// layers keep their indices and this layer's take the next ones.
pub struct Layer {
    pub hash: [u8; 32],
    pub fields: &'static [(u64, Kind)],
}

/// The layers of the schema, in the order the schema file declares them. The client's
/// library is exactly the chain of prefixes of this list: the base, the base with the first
/// layer, and so on.
pub static LAYERS: &[Layer] = &[];

/// The hashes of the base and every layer, in composition order.
pub fn chain() -> Vec<[u8; 32]> {
    let mut hashes = vec![BASE];
    hashes.extend(LAYERS.iter().map(|layer| layer.hash));
    hashes
}

/// The BinTEL-pinned cadence byte of a palimpsest signature (§8.2 step 4).
pub const CADENCE: u8 = 0x79;

/// §8.2: the palimpsest signature of an ordered sequence of component hashes. The hashes are
/// XORed into a body at offsets 0, 4, 6, 8, …, and a trailer byte makes the whole XOR to the
/// cadence byte. 33 bytes for a base alone, then two more per further component.
pub fn palimpsest(hashes: &[[u8; 32]]) -> Vec<u8> {
    let n = hashes.len();
    let length = if n <= 1 { 32 } else { 36 + 2 * (n - 2) };
    let mut body = vec![0u8; length];
    for (i, hash) in hashes.iter().enumerate() {
        let offset = if i == 0 { 0 } else { 4 + 2 * (i - 1) };
        for (j, byte) in hash.iter().enumerate() { body[offset + j] ^= byte; }
    }
    let trailer = body.iter().fold(CADENCE, |acc, byte| acc ^ byte);
    body.push(trailer);
    body
}

/// The composition an invocation's documents are written under: the base with the first
/// `depth − 1` layers, and its signature.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Composition {
    pub depth: usize,
    pub signature: Vec<u8>,
}

impl Composition {
    /// The base alone: what a daemon that publishes no acceptance is sent.
    pub fn base() -> Composition { Composition::of(&chain(), 1) }

    /// The first `depth` components of `hashes`.
    pub fn of(hashes: &[[u8; 32]], depth: usize) -> Composition {
        Composition { depth, signature: palimpsest(&hashes[..depth]) }
    }
}

/// Variant indices of `select Message`, in the schema's declaration order.
pub mod variant {
    pub const INIT: u64 = 0;
    pub const DATA: u64 = 1;
    pub const END: u64 = 2;
    pub const CREDIT: u64 = 3;
    pub const OPEN: u64 = 4;
    pub const SIGNAL: u64 = 5;
    pub const SIGNAL_ACK: u64 = 6;
    pub const MODE: u64 = 7;
    pub const CLOSED: u64 = 8;
    pub const EXIT_STATUS: u64 = 9;
    pub const VERIFY: u64 = 10;
    pub const VERDICT: u64 = 11;
    // Sent by tooling, never by the launcher itself; listed so the indices stay complete.
    #[allow(dead_code)]
    pub const SHUTDOWN: u64 = 12;
    pub const RUN: u64 = 13;
    pub const EXITED: u64 = 14;
}

/// The daemon reads documents from a peer it did not choose; so does the client. A document
/// larger than this is not one of ours. Pinned in `spec/launcher.md`, and the daemon's limit
/// too.
pub const MAXIMUM_LENGTH: u64 = 1 << 20;

/// The most bytes one `data` document carries (`spec/launcher.md`, *Flow control*).
pub const MAXIMUM_CHUNK: usize = 65536;

// ── §4 varints ────────────────────────────────────────────────────────────────

pub fn encode_varint(out: &mut Vec<u8>, mut n: u64) {
    while n >= 0x80 {
        out.push(((n & 0x7f) as u8) | 0x80);
        n >>= 7;
    }
    out.push(n as u8);
}

/// `(value, bytes consumed)`, or `None` for a truncated, over-wide or overlong encoding
/// (all B02 under §4).
pub fn decode_varint(bytes: &[u8]) -> Option<(u64, usize)> {
    let mut value: u64 = 0;
    let mut shift: u32 = 0;
    for (i, &b) in bytes.iter().enumerate() {
        let chunk = (b & 0x7f) as u64;
        if shift >= 64 || (shift == 63 && chunk > 1) { return None; }
        value |= chunk << shift;
        if b & 0x80 == 0 {
            if i > 0 && chunk == 0 { return None; }
            return Some((value, i + 1));
        }
        shift += 7;
    }
    None
}

// ── Encoding ──────────────────────────────────────────────────────────────────

/// The fields of one record, accumulated in declaration order (§7.2 canonical order is
/// member order, and every message here is written that way).
pub struct Record {
    count: u64,
    bytes: Vec<u8>,
}

impl Record {
    pub fn new() -> Record { Record { count: 0, bytes: Vec::new() } }

    pub fn scalar(&mut self, index: u64, text: &str) { self.bytes_field(index, text.as_bytes()); }

    /// A `Bytes` scalar: the same node form as a `String`, with no UTF-8 obligation.
    pub fn bytes_field(&mut self, index: u64, bytes: &[u8]) {
        encode_varint(&mut self.bytes, index);
        encode_varint(&mut self.bytes, bytes.len() as u64);
        self.bytes.extend_from_slice(bytes);
        self.count += 1;
    }

    pub fn flag(&mut self, index: u64) {
        encode_varint(&mut self.bytes, index);
        self.count += 1;
    }

    /// A record-valued member: its index, its child count, its fields.
    pub fn record(&mut self, index: u64, inner: Record) {
        encode_varint(&mut self.bytes, index);
        encode_varint(&mut self.bytes, inner.count);
        self.bytes.extend_from_slice(&inner.bytes);
        self.count += 1;
    }
}

/// A complete framed document (§6.1) carrying one `Message` of the given variant, written
/// under `composition`.
pub fn document(variant: u64, record: Record, composition: &Composition) -> Vec<u8> {
    let signature = &composition.signature;
    let mut body = Vec::with_capacity(record.bytes.len() + 8);
    encode_varint(&mut body, 1);             // root: one child, the select member
    encode_varint(&mut body, variant);       // the variant's keyword index
    encode_varint(&mut body, record.count);  // the record's child count
    body.extend_from_slice(&record.bytes);

    let mut signature_length = Vec::new();
    encode_varint(&mut signature_length, signature.len() as u64);
    let length = signature_length.len() + signature.len() + body.len();

    let mut out = Vec::with_capacity(4 + 2 + length);
    out.extend_from_slice(&MAGIC);
    encode_varint(&mut out, length as u64);
    out.extend_from_slice(&signature_length);
    out.extend_from_slice(signature);
    out.extend_from_slice(&body);
    out
}

// ── Decoding ──────────────────────────────────────────────────────────────────

/// A document the daemon sends.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Message {
    Data { stream: String, bytes: Vec<u8> },
    End { stream: String },
    Credit { stream: String, bytes: u64 },
    Open { stream: String },
    SignalAck { accept: bool },
    Mode { canonical: bool, echo: bool },
    Closed { stream: String },
    Run { command: String, arguments: Vec<String>, pwd: Option<String> },
    ExitStatus { code: i32 },
    Verdict { fresh: bool },
}

/// Reads exactly one framed document from `reader` — the magic number, the length varint and
/// then the declared bytes — and returns it whole. Nothing beyond the document is consumed.
pub fn read_document(reader: &mut impl Read) -> io::Result<Vec<u8>> {
    let mut out = vec![0u8; 4];
    reader.read_exact(&mut out)?;
    if out[..4] != MAGIC {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "not a BinTEL document"));
    }
    let mut declared: u64 = 0;
    let mut shift = 0;
    loop {
        let mut byte = [0u8; 1];
        reader.read_exact(&mut byte)?;
        out.push(byte[0]);
        if shift > 63 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "document length too wide"));
        }
        declared |= ((byte[0] & 0x7f) as u64) << shift;
        shift += 7;
        if byte[0] & 0x80 == 0 { break; }
    }
    if declared > MAXIMUM_LENGTH {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "document too long"));
    }
    let start = out.len();
    out.resize(start + declared as usize, 0);
    reader.read_exact(&mut out[start..])?;
    Ok(out)
}

/// The fields of the base's inbound records: `(variant, index)` to kind. The client reads
/// only what the daemon sends, so the launcher-to-daemon records are not tabled.
fn base_field_kind(variant: u64, index: u64) -> Option<Kind> {
    match (variant, index) {
        (variant::DATA, 0) | (variant::DATA, 1) => Some(Kind::Scalar),
        (variant::END, 0) | (variant::OPEN, 0) | (variant::CLOSED, 0) => Some(Kind::Scalar),
        (variant::CREDIT, 0) | (variant::CREDIT, 1) => Some(Kind::Scalar),
        (variant::SIGNAL_ACK, 0) | (variant::VERDICT, 0) => Some(Kind::Flag),
        (variant::MODE, 0) | (variant::MODE, 1) => Some(Kind::Flag),
        (variant::EXIT_STATUS, 0) => Some(Kind::Scalar),
        (variant::RUN, 0) | (variant::RUN, 1) | (variant::RUN, 2) => Some(Kind::Scalar),
        _ => None,
    }
}

/// How many members the base declares on an inbound record.
fn base_field_count(variant: u64) -> u64 {
    match variant {
        variant::RUN => 3,
        variant::DATA | variant::CREDIT | variant::MODE => 2,
        variant::END | variant::OPEN | variant::CLOSED | variant::EXIT_STATUS => 1,
        variant::SIGNAL_ACK | variant::VERDICT => 1,
        _ => 0,
    }
}

/// The kind of field at `index` in an inbound variant's record under a composition of the
/// given depth over `layers`: the base's members first, then those each layer appends, in
/// order.
fn field_kind(layers: &[Layer], depth: usize, variant: u64, index: u64) -> Option<Kind> {
    if let Some(kind) = base_field_kind(variant, index) { return Some(kind); }
    let mut next = base_field_count(variant);
    for layer in layers.iter().take(depth.saturating_sub(1)) {
        for &(owner, kind) in layer.fields {
            if owner != variant { continue; }
            if next == index { return Some(kind); }
            next += 1;
        }
    }
    None
}

/// Decodes a framed document from the daemon, written under `composition`. `None` for
/// anything that is not a well-formed document of that composition carrying one inbound
/// variant.
pub fn parse(document: &[u8], composition: &Composition) -> Option<Message> {
    parse_with(LAYERS, document, composition)
}

fn parse_with(layers: &[Layer], document: &[u8], composition: &Composition) -> Option<Message> {
    let signature = &composition.signature;
    if document.len() < 4 || document[..4] != MAGIC { return None; }
    let mut cur = 4;
    let (declared, n) = decode_varint(&document[cur..])?;
    cur += n;
    if declared as usize != document.len() - cur { return None; }

    let (signature_length, n) = decode_varint(&document[cur..])?;
    cur += n;
    if signature_length as usize != signature.len() { return None; }
    if document.len() < cur + signature.len() || document[cur..cur + signature.len()] != signature[..] {
        return None;
    }
    cur += signature.len();

    let (root_count, n) = decode_varint(&document[cur..])?;
    cur += n;
    if root_count != 1 { return None; }
    let (variant, n) = decode_varint(&document[cur..])?;
    cur += n;
    let (field_count, n) = decode_varint(&document[cur..])?;
    cur += n;

    let mut flags: Vec<u64> = Vec::new();
    let mut scalars: Vec<(u64, &[u8])> = Vec::new();
    for _ in 0..field_count {
        let (index, n) = decode_varint(&document[cur..])?;
        cur += n;
        match field_kind(layers, composition.depth, variant, index)? {
            Kind::Flag => flags.push(index),
            Kind::Scalar => {
                let (length, n) = decode_varint(&document[cur..])?;
                cur += n;
                let end = cur.checked_add(length as usize)?;
                if end > document.len() { return None; }
                scalars.push((index, &document[cur..end]));
                cur = end;
            }
        }
    }
    // §6.1 field 2 / B16: the structure must end exactly where the declared length says.
    if cur != document.len() { return None; }

    let flag = |index: u64| flags.contains(&index);
    let raw = |index: u64| -> Option<&[u8]> {
        scalars.iter().find(|(i, _)| *i == index).map(|(_, bytes)| *bytes)
    };
    let text = |index: u64| -> Option<String> {
        raw(index).and_then(|bytes| String::from_utf8(bytes.to_vec()).ok())
    };
    let texts = |index: u64| -> Option<Vec<String>> {
        scalars.iter().filter(|(i, _)| *i == index)
            .map(|(_, bytes)| String::from_utf8(bytes.to_vec()).ok()).collect()
    };

    match variant {
        variant::DATA => {
            let bytes = raw(1)?;
            if bytes.len() > MAXIMUM_CHUNK { return None; }
            Some(Message::Data { stream: text(0)?, bytes: bytes.to_vec() })
        }
        variant::END => Some(Message::End { stream: text(0)? }),
        variant::CREDIT => Some(Message::Credit { stream: text(0)?, bytes: text(1)?.trim().parse().ok()? }),
        variant::OPEN => Some(Message::Open { stream: text(0)? }),
        variant::SIGNAL_ACK => Some(Message::SignalAck { accept: flag(0) }),
        variant::MODE => Some(Message::Mode { canonical: flag(0), echo: flag(1) }),
        variant::RUN => Some(Message::Run { command: text(0)?, arguments: texts(1)?, pwd: text(2) }),
        variant::CLOSED => Some(Message::Closed { stream: text(0)? }),
        variant::EXIT_STATUS => Some(Message::ExitStatus { code: text(0)?.trim().parse().ok()? }),
        variant::VERDICT => Some(Message::Verdict { fresh: flag(0) }),
        _ => None,
    }
}

#[cfg(test)]
pub mod fixtures {
    use super::{Kind, Layer, variant, BASE};

    // Two invented layers, to exercise the composition machinery until the schema declares a
    // real one: the first appends a scalar to `Init` and a flag to `Mode`, the second another
    // flag to `Mode`. Their hashes share no four-byte prefix with each other or the base.
    pub static LAYERS: &[Layer] = &[
        Layer { hash: [0x11; 32], fields: &[(variant::INIT, Kind::Scalar), (variant::MODE, Kind::Flag)] },
        Layer { hash: [0x22; 32], fields: &[(variant::MODE, Kind::Flag)] },
    ];

    pub fn chain() -> Vec<[u8; 32]> {
        let mut hashes = vec![BASE];
        hashes.extend(LAYERS.iter().map(|layer| layer.hash));
        hashes
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    pub fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{:02x}", b)).collect()
    }

    /// The base signature as hex: the pinned hash and its trailer.
    pub fn signature() -> String { hex(&Composition::base().signature) }

    #[test]
    fn varints_round_trip_and_reject_overlong() {
        for n in [0u64, 1, 127, 128, 300, 16384, u64::MAX] {
            let mut out = Vec::new();
            encode_varint(&mut out, n);
            assert_eq!(decode_varint(&out), Some((n, out.len())));
        }
        assert_eq!(decode_varint(&[0x80, 0x00]), None);
        assert_eq!(decode_varint(&[0x80]), None);
    }

    // The base alone signs as its hash plus the cadence trailer: the constant the daemon's
    // tests pin ("the schema signature is pinned" in Soundness's `ethereal_test.scala`), and
    // the one `xek_test.scala` derives from `spec/ethereal-launcher.tel`.
    #[test]
    fn the_base_signature_is_pinned() {
        assert_eq!(hex(&Composition::base().signature[..32]), hex(&BASE));
        assert_eq!(Composition::base().depth, 1);
        assert_eq!(Composition::base().signature.iter().fold(0u8, |a, b| a ^ b), CADENCE);
        assert_ne!(BASE, [0u8; 32], "the base hash has not been pinned");
    }

    // §8.2's shapes: 33 bytes for one component, 37 for two, 39 for three; every byte XORs
    // to the cadence; the first four bytes are the base's, uncontested.
    #[test]
    fn palimpsests_have_the_pinned_shape() {
        let chain = fixtures::chain();
        for (depth, length) in [(1, 33), (2, 37), (3, 39)] {
            let signature = palimpsest(&chain[..depth]);
            assert_eq!(signature.len(), length);
            assert_eq!(signature.iter().fold(0u8, |a, b| a ^ b), CADENCE);
            assert_eq!(&signature[..4], &BASE[..4]);
        }
        // With the base XORed out, the second component's first two bytes sit at offset 4.
        let two = palimpsest(&chain[..2]);
        assert_eq!(two[4] ^ BASE[4], 0x11);
        assert_eq!(two[5] ^ BASE[5], 0x11);
    }

    #[test]
    fn exit_status_document_has_the_pinned_layout() {
        let mut record = Record::new();
        record.scalar(0, "42");
        let doc = document(variant::EXIT_STATUS, record, &Composition::base());
        // magic, length (1 + 33 + 7 = 41), signature length, signature, body.
        assert_eq!(&doc[..4], &MAGIC);
        assert_eq!(doc[4], 41);
        assert_eq!(doc[5], 33);
        assert_eq!(hex(&doc[6..39]), signature());
        assert_eq!(&doc[39..], &[0x01, 0x09, 0x01, 0x00, 0x02, b'4', b'2']);
    }

    // The frames the daemon's own tests pin (in Soundness, `ethereal_test.scala`'s "Launcher
    // protocol" suite), produced by `Launcher.encode` on the Scala side: both implementations
    // must agree byte for byte.
    #[test]
    fn frames_match_the_daemon_side() {
        let sig = signature();
        let base = Composition::base();
        assert_eq!(hex(&document(variant::VERIFY, Record::new(), &base)),
                   format!("b2c4b5bb2521{sig}010a00"));
        let mut record = Record::new();
        record.flag(0);
        assert_eq!(hex(&document(variant::MODE, record, &base)),
                   format!("b2c4b5bb2621{sig}01070100"));

        // data: stream "stdin" (5 bytes), bytes 0x00 0xff (not UTF-8, which a Bytes scalar
        // need not be). Body: 01 01 02 | 00 05 "stdin" | 01 02 00 ff = 14 bytes; 1+33+14=48.
        assert_eq!(hex(&crate::protocol::data_document("stdin", &[0x00, 0xff], &base)),
                   format!("b2c4b5bb3021{sig}0101020005737464696e010200ff"));
        assert_eq!(hex(&crate::protocol::end_document("stdin", &base)),
                   format!("b2c4b5bb2c21{sig}0102010005737464696e"));
        assert_eq!(hex(&crate::protocol::credit_document("stdout", 65536, &base)),
                   format!("b2c4b5bb3421{sig}01030200067374646f757401053635353336"));
        assert_eq!(hex(&crate::protocol::closed_document("stdout", &base)),
                   format!("b2c4b5bb2d21{sig}01080100067374646f7574"));

        // A WINCH carries the terminal's size (fields 1 and 2); a Windows close, its deadline.
        let detail = crate::protocol::SignalDetail { size: Some((80, 24)), deadline_ms: None };
        assert_eq!(hex(&crate::protocol::signal_document("WINCH", detail, &base)),
                   format!("b2c4b5bb3421{sig}010503000557494e43480102383002023234"));
        let detail = crate::protocol::SignalDetail { size: None, deadline_ms: Some(5000) };
        assert_eq!(hex(&crate::protocol::signal_document("CTRL_CLOSE", detail, &base)),
                   format!("b2c4b5bb3721{sig}010502000a4354524c5f434c4f5345030435303030"));
    }

    // The `init` document: stdout deliberately not a terminal while stdin and stderr are
    // (`command > file` run from a terminal), so the three flags being written in the wrong
    // order or under the wrong indices is caught; and two descriptors, one a regular file
    // with a path, one a pipe without.
    #[test]
    fn init_document_has_the_pinned_layout() {
        let sig = signature();
        let base = Composition::base();
        let info = crate::protocol::ClientInfo {
            pid: 7, user_id: "501".into(), user_name: "jon".into(), script: "/usr/bin/x".into(),
            invoked_as: Some("x".into()), pwd: "/tmp".into(), args: vec!["a".into(), "b c".into()],
            env: vec!["K=V".into()], stdin_tty: true, stdout_tty: false, stderr_tty: true,
            umask: Some("022".into()), size: None, codepages: None,
            descriptors: vec![
                crate::descriptors::Descriptor { fd: 0, direction: "r", kind: "file", path: Some("/in".into()) },
                crate::descriptors::Descriptor { fd: 63, direction: "r", kind: "pipe", path: None },
            ],
            raw: vec![crate::protocol::Raw { kind: "argument", index: Some(1), bytes: vec![b'b', 0xff] }],
        };
        // pid, uid, username, script, pwd; stdin-tty and stderr-tty flags (5, 7); the two
        // arguments (8); the environment (9); invoked-as (10); umask (11); two descriptor
        // records (16): fd, direction, kind[, path]; one raw record (17): kind, index, bytes —
        // the second argument's bytes, which are not UTF-8.
        let fields = concat!(
            "000137", "01033530 31", "02 03 6a6f6e", "03 0a 2f7573722f62696e2f78", "04 04 2f746d70",
            "05", "07", "08 01 61", "08 03 622063", "09 03 4b3d56", "0a 01 78", "0b 03 303232",
            "10 04 0001 30 0101 72 0204 66696c65 0303 2f696e",
            "10 03 0002 3633 0101 72 0204 70697065",
            "11 03 0008 617267756d656e74 0101 31 0202 62ff",
        ).replace(' ', "");
        let body = format!("01000f{fields}");
        let mut length = Vec::new();
        encode_varint(&mut length, (1 + 33 + body.len() / 2) as u64);
        assert_eq!(hex(&crate::protocol::init_document(&info, &base)),
                   format!("b2c4b5bb{}21{sig}{body}", hex(&length)));
    }

    // Under a deeper composition the frame carries the longer signature, and a layer's
    // field is readable at the index after the base's.
    #[test]
    fn frames_under_a_layered_composition_carry_its_signature() {
        let chain = fixtures::chain();
        let two = Composition::of(&chain, 2);
        let three = Composition::of(&chain, 3);

        let doc = document(variant::VERIFY, Record::new(), &two);
        assert_eq!(doc[5], 37);
        assert_eq!(&doc[6..43], &two.signature[..]);

        // Mode under the first fixture layer: base flags 0 and 1, layer flag 2.
        let mut record = Record::new();
        record.flag(0);
        record.flag(2);
        let doc = document(variant::MODE, record, &two);
        assert_eq!(parse_with(fixtures::LAYERS, &doc, &two), Some(Message::Mode { canonical: true, echo: false }));
        // The same bytes are not a base document: wrong signature.
        assert_eq!(parse_with(fixtures::LAYERS, &doc, &Composition::base()), None);
        // Nor a depth-3 one, whose signature is longer still.
        assert_eq!(parse_with(fixtures::LAYERS, &doc, &three), None);

        // Index 3 exists only from the second layer on.
        let mut record = Record::new();
        record.flag(3);
        let doc = document(variant::MODE, record, &two);
        assert_eq!(parse_with(fixtures::LAYERS, &doc, &two), None);
        let mut record = Record::new();
        record.flag(3);
        let doc = document(variant::MODE, record, &three);
        assert_eq!(parse_with(fixtures::LAYERS, &doc, &three), Some(Message::Mode { canonical: false, echo: false }));
    }

    #[test]
    fn inbound_documents_parse() {
        let base = Composition::base();
        let mut record = Record::new();
        record.scalar(0, "3");
        let doc = document(variant::EXIT_STATUS, record, &base);
        assert_eq!(parse(&doc, &base), Some(Message::ExitStatus { code: 3 }));

        let mut record = Record::new();
        record.flag(0);
        let doc = document(variant::MODE, record, &base);
        assert_eq!(parse(&doc, &base), Some(Message::Mode { canonical: true, echo: false }));
        let mut record = Record::new();
        record.flag(0);
        record.flag(1);
        let doc = document(variant::MODE, record, &base);
        assert_eq!(parse(&doc, &base), Some(Message::Mode { canonical: true, echo: true }));

        // run: a command, two arguments, a directory; then one with neither.
        let mut record = Record::new();
        record.scalar(0, "vi");
        record.scalar(1, "-R");
        record.scalar(1, "notes.txt");
        record.scalar(2, "/home/jon");
        let doc = document(variant::RUN, record, &base);
        assert_eq!(parse(&doc, &base), Some(Message::Run {
            command: "vi".into(), arguments: vec!["-R".into(), "notes.txt".into()], pwd: Some("/home/jon".into()) }));
        let mut record = Record::new();
        record.scalar(0, "less");
        let doc = document(variant::RUN, record, &base);
        assert_eq!(parse(&doc, &base), Some(Message::Run { command: "less".into(), arguments: vec![], pwd: None }));
        // exited: body 01 0e 01 | 00 03 "127" = 8 bytes; 1 + 33 + 8 = 42.
        assert_eq!(hex(&crate::protocol::exited_document(127, &base)),
                   format!("b2c4b5bb2a21{}010e010003313237", signature()));

        let doc = document(variant::VERDICT, Record::new(), &base);
        assert_eq!(parse(&doc, &base), Some(Message::Verdict { fresh: false }));

        let doc = crate::protocol::data_document("stdout", b"hi\xff", &base);
        assert_eq!(parse(&doc, &base), Some(Message::Data { stream: "stdout".into(), bytes: b"hi\xff".to_vec() }));
        let doc = crate::protocol::credit_document("stdin", 4096, &base);
        assert_eq!(parse(&doc, &base), Some(Message::Credit { stream: "stdin".into(), bytes: 4096 }));
        let doc = crate::protocol::end_document("63", &base);
        assert_eq!(parse(&doc, &base), Some(Message::End { stream: "63".into() }));
        let mut record = Record::new();
        record.scalar(0, "63");
        let doc = document(variant::OPEN, record, &base);
        assert_eq!(parse(&doc, &base), Some(Message::Open { stream: "63".into() }));
        let doc = crate::protocol::closed_document("63", &base);
        assert_eq!(parse(&doc, &base), Some(Message::Closed { stream: "63".into() }));
    }

    // A chunk over the maximum is not a chunk: the sender has broken the contract.
    #[test]
    fn an_oversized_chunk_is_refused() {
        let base = Composition::base();
        let doc = crate::protocol::data_document("stdout", &vec![0u8; MAXIMUM_CHUNK + 1], &base);
        assert_eq!(parse(&doc, &base), None);
        let doc = crate::protocol::data_document("stdout", &vec![0u8; MAXIMUM_CHUNK], &base);
        assert!(parse(&doc, &base).is_some());
    }

    #[test]
    fn read_document_consumes_exactly_one_document() {
        let base = Composition::base();
        let mut record = Record::new();
        record.flag(0);
        let mut stream = document(variant::SIGNAL_ACK, record, &base);
        let length = stream.len();
        stream.extend_from_slice(b"trailing");
        let mut cursor = std::io::Cursor::new(stream);
        let doc = read_document(&mut cursor).unwrap();
        assert_eq!(doc.len(), length);
        assert_eq!(cursor.position() as usize, length);
        assert_eq!(parse(&doc, &base), Some(Message::SignalAck { accept: true }));
    }

    #[test]
    fn a_foreign_signature_is_rejected() {
        let base = Composition::base();
        let mut record = Record::new();
        record.scalar(0, "3");
        let mut doc = document(variant::EXIT_STATUS, record, &base);
        doc[6] ^= 0x01;
        assert_eq!(parse(&doc, &base), None);
    }
}
