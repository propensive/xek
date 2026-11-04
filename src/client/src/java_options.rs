use std::path::Path;

use crate::settings;

// The options a daemon's JVM is started with, beyond the launcher's own `-D` properties: those
// the application was built with (spec/ethrcfg.md), then a JDWP agent for `DEBUG_PORT`, then
// `JAVA_OPTIONS`, so that an option given at run time overrides a built-in one. They take
// effect only when a daemon starts, so the options a daemon was started with are recorded in
// its state directory, and an invocation which asks for others is told why it does not get
// them (spec/launcher.md).

// The options for a daemon this invocation would start.
pub fn chosen(built_in: &[String]) -> Vec<String> {
    let mut options = built_in.to_vec();
    if let Some(port) = settings::get(settings::DEBUG_PORT) {
        if settings::switched(Some(&port)) { options.push(debug_agent(&port.to_string_lossy())); }
    }
    if let Some(text) = settings::get(settings::JAVA_OPTIONS) {
        options.extend(split(&text.to_string_lossy()));
    }
    options
}

// A JDWP agent listening for a debugger on a port of the loopback interface or, given as
// `host:port` (`*:5005`, say), on whatever address that names. The JVM is not suspended: to
// debug startup, give `-agentlib:jdwp=…,suspend=y` in `JAVA_OPTIONS` instead.
pub fn debug_agent(port: &str) -> String {
    let port = port.trim();
    let address = if port.chars().all(|c| c.is_ascii_digit()) { format!("localhost:{port}") }
                  else { port.to_string() };
    format!("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address={address}")
}

// The address a JVM started with `options` waits for a debugger on before it runs anything:
// the `address` of a JDWP agent with `suspend=y`.
pub fn suspended(options: &[String]) -> Option<String> {
    options.iter().rev().find_map(|option| {
        let settings = option.strip_prefix("-agentlib:jdwp=")
            .or_else(|| option.strip_prefix("-Xrunjdwp:"))?;
        let mut address = None;
        let mut suspend = true;
        for setting in settings.split(',') {
            match setting.split_once('=') {
                Some(("suspend", value)) => suspend = value == "y",
                Some(("address", value)) => address = Some(value.to_string()),
                _ => (),
            }
        }
        if suspend { Some(address.unwrap_or_default()) } else { None }
    })
}

// Splits options as the `java` launcher splits `JDK_JAVA_OPTIONS`: at white space, except
// within single or double quotes, which are removed.
pub fn split(text: &str) -> Vec<String> {
    let mut options = Vec::new();
    let mut current = String::new();
    let mut started = false;
    let mut quote: Option<char> = None;
    for c in text.chars() {
        match quote {
            Some(open) if c == open => quote = None,
            Some(_) => current.push(c),
            None if c == '"' || c == '\'' => { quote = Some(c); started = true; }
            None if c.is_whitespace() => {
                if started { options.push(std::mem::take(&mut current)); started = false; }
            }
            None => { current.push(c); started = true; }
        }
    }
    if started { options.push(current); }
    options
}

// Records the options a daemon is being started with, one per line ended by a NUL.
pub fn record(file: &Path, options: &[String]) {
    let mut bytes = Vec::new();
    for option in options { bytes.extend_from_slice(option.as_bytes()); bytes.push(0); }
    let _ = std::fs::write(file, bytes);
}

// The options the running daemon was started with: none, if it was started by a launcher which
// recorded nothing.
pub fn recorded(file: &Path) -> Vec<String> {
    let Ok(bytes) = std::fs::read(file) else { return Vec::new(); };
    bytes.split(|&byte| byte == 0)
        .filter(|option| !option.is_empty())
        .map(|option| String::from_utf8_lossy(option).into_owned())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn strings(values: &[&str]) -> Vec<String> { values.iter().map(|v| v.to_string()).collect() }

    #[test]
    fn splits_at_white_space_outside_quotes() {
        assert_eq!(split("  -Xmx2g   -Dname='a b' \"-Dq=it's\"  "),
                   strings(&["-Xmx2g", "-Dname=a b", "-Dq=it's"]));
        assert_eq!(split("-Dempty='' -X"), strings(&["-Dempty=", "-X"]));
        assert_eq!(split(""), Vec::<String>::new());
    }

    #[test]
    fn a_port_listens_on_the_loopback_interface() {
        assert_eq!(debug_agent("5005"), "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:5005");
        assert_eq!(debug_agent("*:5005"), "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005");
    }

    #[test]
    fn only_a_suspending_agent_waits_for_a_debugger() {
        assert_eq!(suspended(&strings(&["-Xmx1g", &debug_agent("5005")])), None);
        assert_eq!(suspended(&strings(&["-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=8000"])),
                   Some("8000".to_string()));
        // `suspend` defaults to `y`.
        assert_eq!(suspended(&strings(&["-agentlib:jdwp=transport=dt_socket,server=y,address=8000"])),
                   Some("8000".to_string()));
        assert_eq!(suspended(&strings(&["-Xmx1g"])), None);
    }

    #[test]
    fn the_record_reads_back() {
        let file = std::env::temp_dir().join(format!("xek-java-options-{}", std::process::id()));
        let options = strings(&["-Xmx2g", "-Dname=a b"]);
        record(&file, &options);
        let read = recorded(&file);
        let _ = std::fs::remove_file(&file);
        assert_eq!(read, options);
        assert_eq!(recorded(&file), Vec::<String>::new());
    }
}
