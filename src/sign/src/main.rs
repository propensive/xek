// xek-sign: keys and signatures for XEK self-upgrade (spec/ethrcfg.md, *Signing a release*).
//
// Usage:
//   xek-sign keygen --out <prefix>
//     Generates an ML-DSA-44 keypair: <prefix>.seed (32 bytes, mode 0600, the FIPS 204 key
//     generation seed, to be kept secret) and <prefix>.pub (1312 bytes, the public key a builder
//     writes into the record). Refuses to overwrite either.
//
//   xek-sign public-key (--key <seed-file> | --key-env <VAR>) --out <file>
//     Derives the public key from a seed, so that a committed public key can be checked.
//
//   xek-sign sign (--key <seed-file> | --key-env <VAR>) --in <binary> --out <signed>
//                 [--allow-downgrade] [--foreign-key]
//     Sets the record's flags byte, signs the statement, and writes the signature into the
//     record. The key must be the record's release or recovery key unless --foreign-key is given.
//
//   xek-sign statement --in <binary> [--allow-downgrade]
//     Prints, in hexadecimal, the 40-byte statement an external signer is asked to sign.
//
//   xek-sign attach --in <binary> --signature <file> --out <signed> [--allow-downgrade]
//     Writes a signature made elsewhere, over that statement, into the record.
//
//   xek-sign verify --public-key <file> [--app-id <text>] --in <binary>
//     Exits 0, printing the build id, only if the signature verifies under the key (and the
//     record is for that application).
//
// `--key-env` names an environment variable holding the seed as 64 hexadecimal digits, so a CI
// secret need never be written to disk. The seed itself is never accepted as an argument.

#[path = "../../client/src/signing.rs"]
#[allow(dead_code)]
mod signing;

use std::collections::HashMap;
use std::io::Write;
use std::path::{Path, PathBuf};

use ml_dsa::{B32, EncodedSignature, Keypair, MlDsa44, Signature, SigningKey, signature::Signer};
use rand::{TryRngCore, rngs::OsRng};

use signing::{APP_ID_LEN, APP_ID_OFFSET, BUILD_ID_OFFSET, FLAG_DOWNGRADE_PERMITTED, FLAGS_OFFSET, PUBKEY_LEN,
              RECORD_LEN, RECOVERY_KEY_OFFSET, RELEASE_KEY_OFFSET, SIGNATURE_LEN, SIGNATURE_OFFSET, is_zero,
              statement, verifies};

// The signer is not a stub, so it may hold the magic as a literal.
const MAGIC: [u8; 8] = *b"ETHRCFG\x04";
const SEED_LEN: usize = 32;

fn die(msg: impl AsRef<str>) -> ! {
    eprintln!("xek-sign: {}", msg.as_ref());
    std::process::exit(1);
}

// A binary and the offset of its record: the first magic with a whole record behind it.
struct Binary { bytes: Vec<u8>, record: usize }

impl Binary {
    fn read(path: &Path) -> Binary {
        let bytes = std::fs::read(path)
            .unwrap_or_else(|e| die(format!("could not read {}: {e}", path.display())));
        let record = (bytes.len() >= RECORD_LEN)
            .then(|| bytes[..bytes.len() - RECORD_LEN + MAGIC.len()].windows(MAGIC.len()).position(|w| w == MAGIC))
            .flatten()
            .unwrap_or_else(|| die(format!("{} contains no ETHRCFG v4 record", path.display())));
        Binary { bytes, record }
    }

    fn field(&self, offset: usize, len: usize) -> &[u8] {
        &self.bytes[self.record + offset..self.record + offset + len]
    }

    fn key(&self, offset: usize) -> [u8; PUBKEY_LEN] { self.field(offset, PUBKEY_LEN).try_into().unwrap() }
    fn release_key(&self) -> [u8; PUBKEY_LEN] { self.key(RELEASE_KEY_OFFSET) }
    fn recovery_key(&self) -> [u8; PUBKEY_LEN] { self.key(RECOVERY_KEY_OFFSET) }

    fn build_id(&self) -> u64 { u64::from_le_bytes(self.field(BUILD_ID_OFFSET, 8).try_into().unwrap()) }

    fn signature(&self) -> &[u8] { self.field(SIGNATURE_OFFSET, SIGNATURE_LEN) }

    // The flags are part of what is signed, so they are set before the statement is taken.
    fn set_downgrade(&mut self, allow: bool) {
        self.bytes[self.record + FLAGS_OFFSET] = if allow { FLAG_DOWNGRADE_PERMITTED } else { 0 };
    }

    fn statement(&self) -> [u8; signing::STATEMENT_LEN] { statement(&self.bytes, self.record) }

    fn attach(&mut self, signature: &[u8]) {
        let start = self.record + SIGNATURE_OFFSET;
        self.bytes[start..start + SIGNATURE_LEN].copy_from_slice(signature);
    }

    // Which of the record's own keys `key` is, as the signer reports it.
    fn role(&self, key: &[u8; PUBKEY_LEN]) -> Option<&'static str> {
        if !is_zero(key) && *key == self.release_key() { Some("release") }
        else if !is_zero(key) && *key == self.recovery_key() { Some("recovery") }
        else { None }
    }

    fn write(&self, path: &Path) {
        std::fs::write(path, &self.bytes).unwrap_or_else(|e| die(format!("could not write {}: {e}", path.display())));
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o755));
        }
    }
}

fn signing_key(seed: [u8; SEED_LEN]) -> SigningKey<MlDsa44> {
    let seed: B32 = seed.into();
    SigningKey::from_seed(&seed)
}

fn public_key(key: &SigningKey<MlDsa44>) -> [u8; PUBKEY_LEN] { key.verifying_key().encode().into() }

fn hex(bytes: &[u8]) -> String { bytes.iter().map(|b| format!("{b:02x}")).collect() }

fn unhex(text: &str) -> Option<Vec<u8>> {
    let text = text.trim();
    if text.len() % 2 != 0 || !text.is_ascii() { return None; }
    (0..text.len()).step_by(2).map(|i| u8::from_str_radix(&text[i..i + 2], 16).ok()).collect()
}

// The seed, from `--key <file>` (32 raw bytes) or `--key-env <VAR>` (64 hex digits).
fn seed(options: &Options) -> [u8; SEED_LEN] {
    let bytes = match (options.path("--key"), options.text("--key-env")) {
        (Some(path), None) => std::fs::read(&path)
            .unwrap_or_else(|e| die(format!("could not read seed file {}: {e}", path.display()))),
        (None, Some(variable)) => {
            let value = std::env::var(variable)
                .unwrap_or_else(|_| die(format!("the environment variable {variable} is not set")));
            unhex(&value).unwrap_or_else(|| die(format!("{variable} must hold the seed as 64 hexadecimal digits")))
        },
        _ => die("give exactly one of --key <seed-file> or --key-env <VAR>"),
    };
    bytes.as_slice().try_into()
        .unwrap_or_else(|_| die(format!("a seed is {SEED_LEN} bytes, but this one is {}", bytes.len())))
}

// Creates a file that must not already exist, with `mode` on Unix.
fn create(path: &Path, bytes: &[u8], mode: u32) {
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(mode);
    }
    #[cfg(not(unix))]
    let _ = mode;
    let mut file = options.open(path).unwrap_or_else(|e| match e.kind() {
        std::io::ErrorKind::AlreadyExists => die(format!("{} already exists; not overwriting it", path.display())),
        _ => die(format!("could not create {}: {e}", path.display())),
    });
    file.write_all(bytes).and_then(|()| file.sync_all())
        .unwrap_or_else(|e| die(format!("could not write {}: {e}", path.display())));
}

fn keygen(options: &Options) {
    let prefix = options.required_path("--out", "<prefix>");
    let mut seed = [0u8; SEED_LEN];
    OsRng.try_fill_bytes(&mut seed).unwrap_or_else(|e| die(format!("could not read entropy from the OS: {e}")));
    let public = public_key(&signing_key(seed));

    let seed_path = prefix.with_extension("seed");
    let public_path = prefix.with_extension("pub");
    if public_path.exists() { die(format!("{} already exists; not overwriting it", public_path.display())); }
    create(&seed_path, &seed, 0o600);
    create(&public_path, &public, 0o644);
    eprintln!("wrote {} ({SEED_LEN} bytes) and {} ({PUBKEY_LEN} bytes)", seed_path.display(), public_path.display());
}

fn derive(options: &Options) {
    let out = options.required_path("--out", "<file>");
    std::fs::write(&out, public_key(&signing_key(seed(options))))
        .unwrap_or_else(|e| die(format!("could not write {}: {e}", out.display())));
}

fn sign(options: &Options) {
    let key = signing_key(seed(options));
    let mut binary = Binary::read(&options.required_path("--in", "<binary>"));
    let out = options.required_path("--out", "<signed>");
    binary.set_downgrade(options.flag("--allow-downgrade"));

    match binary.role(&public_key(&key)) {
        Some(role) => eprintln!("signing with the record's {role} key"),
        None if options.flag("--foreign-key") =>
            eprintln!("signing with a key the record does not carry (--foreign-key)"),
        None => die("the key is neither the record's release key nor its recovery key; \
                     give --foreign-key if that is intended, as for the release that follows a rotation"),
    }

    let signature: [u8; SIGNATURE_LEN] = key.sign(&binary.statement()).encode().into();
    binary.attach(&signature);
    binary.write(&out);
    eprintln!("wrote {} (build {}, allow_downgrade={})", out.display(), binary.build_id(), options.flag("--allow-downgrade"));
}

fn print_statement(options: &Options) {
    let mut binary = Binary::read(&options.required_path("--in", "<binary>"));
    binary.set_downgrade(options.flag("--allow-downgrade"));
    println!("{}", hex(&binary.statement()));
}

fn attach(options: &Options) {
    let mut binary = Binary::read(&options.required_path("--in", "<binary>"));
    let path = options.required_path("--signature", "<file>");
    let out = options.required_path("--out", "<signed>");
    let signature = std::fs::read(&path).unwrap_or_else(|e| die(format!("could not read {}: {e}", path.display())));
    let decodes = EncodedSignature::<MlDsa44>::try_from(signature.as_slice()).ok()
        .and_then(|encoded| Signature::<MlDsa44>::decode(&encoded)).is_some();
    if !decodes { die(format!("{} is not an ML-DSA-44 signature of {SIGNATURE_LEN} bytes", path.display())); }

    binary.set_downgrade(options.flag("--allow-downgrade"));
    let statement = binary.statement();
    let by = [("release", binary.release_key()), ("recovery", binary.recovery_key())].into_iter()
        .find(|(_, key)| !is_zero(key) && verifies(&statement, &signature, key))
        .map(|(role, _)| role);
    match by {
        Some(role) => eprintln!("the signature verifies under the record's {role} key"),
        None => eprintln!("the signature verifies under neither of the record's keys (a foreign key?)"),
    }
    binary.attach(&signature);
    binary.write(&out);
}

fn verify(options: &Options) {
    let path = options.required_path("--public-key", "<file>");
    let key: [u8; PUBKEY_LEN] = std::fs::read(&path)
        .unwrap_or_else(|e| die(format!("could not read {}: {e}", path.display())))
        .as_slice().try_into()
        .unwrap_or_else(|_| die(format!("{} is not a {PUBKEY_LEN}-byte public key", path.display())));
    let binary = Binary::read(&options.required_path("--in", "<binary>"));

    if let Some(identifier) = options.text("--app-id") {
        if binary.field(APP_ID_OFFSET, APP_ID_LEN) != signing::app_id(identifier) {
            die(format!("the record is not for the application {identifier}"));
        }
    }
    if !verifies(&binary.statement(), binary.signature(), &key) {
        die("the signature does not verify under that key");
    }
    println!("{}", binary.build_id());
}

// `--name value` pairs and bare flags, checked against what each subcommand accepts.
struct Options { values: HashMap<String, String>, flags: Vec<String> }

impl Options {
    fn parse(args: &[String], valued: &[&str], flags: &[&str]) -> Options {
        let mut options = Options { values: HashMap::new(), flags: Vec::new() };
        let mut iter = args.iter();
        while let Some(arg) = iter.next() {
            if valued.contains(&arg.as_str()) {
                let value = iter.next().unwrap_or_else(|| die(format!("{arg} needs a value")));
                options.values.insert(arg.clone(), value.clone());
            } else if flags.contains(&arg.as_str()) {
                options.flags.push(arg.clone());
            } else {
                die(format!("unknown argument: {arg}"));
            }
        }
        options
    }

    fn text(&self, name: &str) -> Option<&str> { self.values.get(name).map(String::as_str) }
    fn path(&self, name: &str) -> Option<PathBuf> { self.text(name).map(PathBuf::from) }
    fn flag(&self, name: &str) -> bool { self.flags.iter().any(|flag| flag == name) }

    fn required_path(&self, name: &str, operand: &str) -> PathBuf {
        self.path(name).unwrap_or_else(|| die(format!("missing {name} {operand}")))
    }
}

const USAGE: &str = "usage: xek-sign <keygen|public-key|sign|statement|attach|verify> [options]";

fn main() {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    let Some((command, args)) = argv.split_first() else { die(USAGE) };
    let seeded = ["--key", "--key-env"];
    match command.as_str() {
        "keygen" => keygen(&Options::parse(args, &["--out"], &[])),
        "public-key" => derive(&Options::parse(args, &[&seeded[..], &["--out"]].concat(), &[])),
        "sign" => sign(&Options::parse(args, &[&seeded[..], &["--in", "--out"]].concat(),
                                       &["--allow-downgrade", "--foreign-key"])),
        "statement" => print_statement(&Options::parse(args, &["--in"], &["--allow-downgrade"])),
        "attach" => attach(&Options::parse(args, &["--in", "--signature", "--out"], &["--allow-downgrade"])),
        "verify" => verify(&Options::parse(args, &["--public-key", "--app-id", "--in"], &[])),
        "--help" | "-h" | "help" => println!("{USAGE}"),
        other => die(format!("unknown subcommand: {other}\n{USAGE}")),
    }
}
