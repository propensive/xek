use std::ffi::{OsStr, OsString};
use std::path::Path;
use std::sync::OnceLock;

// The launcher's settings, which live in the environment so that no argument is reserved
// (spec/launcher.md). Each is read under two names: `<TOOL>_<option>`, where `<TOOL>` is the
// executable's name in upper case, and `XEK_<option>`. The first is for one tool alone, and
// wins when both are set: `FLAME_DEBUG_LOG=0` turns tracing off for `flame` even when
// `XEK_DEBUG_LOG=1` turns it on for every other tool.

// Download a JVM if no suitable one is installed.
pub const INSTALL_JAVA: &str = "INSTALL_JAVA";
// Options for the JVM, added to those the application was built with.
pub const JAVA_OPTIONS: &str = "JAVA_OPTIONS";
// The port, or `host:port`, a JDWP agent in the JVM listens on for a debugger.
pub const DEBUG_PORT: &str = "DEBUG_PORT";
// Trace the launcher's progress.
pub const DEBUG_LOG: &str = "DEBUG_LOG";
// How long to wait for the daemon to acknowledge a forwarded signal, in milliseconds.
pub const SIGNAL_TIMEOUT_MS: &str = "SIGNAL_TIMEOUT_MS";

static TOOL: OnceLock<String> = OnceLock::new();

// The executable's name as a variable prefix: in upper case, without the `.exe` of a Windows
// executable, and with every character but a letter or a digit made `_`, so that `my-tool`
// gives `MY_TOOL`. Taken from the running executable, which is the file the launcher hands the
// JVM, so it is the name the tool's state directory is named after too.
pub fn tool() -> &'static str {
    TOOL.get_or_init(|| {
        let executable = std::env::current_exe().ok()
            .or_else(|| std::env::args_os().next().map(Into::into))
            .unwrap_or_default();
        prefix(&executable)
    })
}

pub fn prefix(executable: &Path) -> String {
    let name = executable.file_name().unwrap_or_default().to_string_lossy();
    let name = if cfg!(windows) && name.to_ascii_lowercase().ends_with(".exe") { &name[..name.len() - 4] }
               else { &name[..] };
    name.chars().map(|c| if c.is_ascii_alphanumeric() { c.to_ascii_uppercase() } else { '_' }).collect()
}

// The names an option is read under, the tool's own first. For `xek` itself they are one.
pub fn names(option: &str) -> Vec<String> {
    let generic = format!("XEK_{option}");
    let specific = format!("{}_{option}", tool());
    if specific == generic { vec![generic] } else { vec![specific, generic] }
}

// The name a user is told to set: the one every tool reads.
pub fn generic(option: &str) -> String { format!("XEK_{option}") }

// An option's value under the first of its names that is set.
pub fn get(option: &str) -> Option<OsString> {
    names(option).into_iter().find_map(std::env::var_os)
}

// Whether a switch is on: set to anything but the empty string or `0`.
pub fn on(option: &str) -> bool { switched(get(option).as_deref()) }

pub fn switched(value: Option<&OsStr>) -> bool {
    value.is_some_and(|value| !value.is_empty() && value != "0")
}

// Whether tracing is on, read once: `debug!` asks on every line it might write.
pub fn tracing() -> bool {
    static TRACING: OnceLock<bool> = OnceLock::new();
    *TRACING.get_or_init(|| on(DEBUG_LOG))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_prefix_is_the_name_in_upper_case() {
        assert_eq!(prefix(Path::new("/usr/local/bin/flame")), "FLAME");
        assert_eq!(prefix(Path::new("my-tool")), "MY_TOOL");
        assert_eq!(prefix(Path::new("tool.v2")), "TOOL_V2");
    }

    #[cfg(windows)]
    #[test]
    fn the_prefix_drops_the_exe_suffix() {
        assert_eq!(prefix(Path::new(r"C:\tools\Flame.EXE")), "FLAME");
    }

    #[test]
    fn a_switch_is_on_unless_unset_empty_or_zero() {
        assert!(switched(Some(OsStr::new("1"))));
        assert!(switched(Some(OsStr::new("yes"))));
        assert!(!switched(Some(OsStr::new("0"))));
        assert!(!switched(Some(OsStr::new(""))));
        assert!(!switched(None));
    }

    #[test]
    fn the_tool_name_is_read_before_the_generic_one() {
        let names = names(DEBUG_PORT);
        assert_eq!(names.last().map(String::as_str), Some("XEK_DEBUG_PORT"));
        assert_eq!(names.first(), Some(&format!("{}_DEBUG_PORT", tool())));
    }
}
