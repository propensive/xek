// The launcher's status line: the one line xeq itself writes to the terminal, and only to a
// terminal. It is redrawn in place — gray text for a step in progress, a blue braille spinner
// for a download — with the cursor hidden and parked at the start of the line, and it is
// cleared, not kept, when the step completes. Diagnostics are not drawn here: they are printed
// as ordinary lines by their callers, after `clear`, so they are neither overwritten nor
// intermixed with the status line.
use std::io::{self, IsTerminal, Write};
use std::sync::Mutex;
use std::time::{Duration, Instant};

// 256-colour mid-gray and mid-blue: legible on both dark and light backgrounds.
const GRAY: &str = "\x1b[38;5;245m";
const BLUE: &str = "\x1b[38;5;68m";
const RESET: &str = "\x1b[0m";
const CLEAR_EOL: &str = "\x1b[K";
const HIDE_CURSOR: &str = "\x1b[?25l";
const SHOW_CURSOR: &str = "\x1b[?25h";
const FRAMES: [char; 10] = ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'];
// The spinner turns at this rate however often `tick` is called.
const FRAME_INTERVAL: Duration = Duration::from_millis(80);

struct Line {
    shown: bool,
    spinner: bool,
    frame: usize,
    turned: Option<Instant>,
    message: String,
}

static LINE: Mutex<Line> =
    Mutex::new(Line { shown: false, spinner: false, frame: 0, turned: None, message: String::new() });

// Shows `message` in gray, replacing whatever the line showed before.
pub fn step(message: &str) { show(message, false); }

// Shows `message` behind a spinner, replacing whatever the line showed before. The spinner
// advances on each `tick`.
pub fn spin(message: &str) { show(message, true); }

// Advances the spinner, if one is showing and a frame interval has passed. Called from polling
// loops, at whatever rate they poll; a no-op otherwise.
pub fn tick() {
    let mut line = LINE.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    if !line.shown || !line.spinner { return; }
    let now = Instant::now();
    if line.turned.is_some_and(|turned| now.duration_since(turned) < FRAME_INTERVAL) { return; }
    line.turned = Some(now);
    line.frame = (line.frame + 1) % FRAMES.len();
    draw(&line);
}

// Erases the status line and restores the cursor. Idempotent: safe to call before printing a
// diagnostic whether or not anything was showing.
pub fn clear() {
    let mut line = LINE.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    if !line.shown { return; }
    line.shown = false;
    let mut out = io::stderr().lock();
    let _ = write!(out, "\r{CLEAR_EOL}{SHOW_CURSOR}");
    let _ = out.flush();
}

fn show(message: &str, spinner: bool) {
    if !io::stderr().is_terminal() { return; }
    let mut line = LINE.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    if !line.shown { install_cursor_guard(); }
    line.shown = true;
    line.spinner = spinner;
    line.message.clear();
    line.message.push_str(message);
    line.turned = Some(Instant::now());
    draw(&line);
}

fn draw(line: &Line) {
    let no_color = std::env::var_os("NO_COLOR").is_some_and(|v| !v.is_empty())
        || std::env::var("TERM").map(|t| t == "dumb").unwrap_or(false);
    let mut out = io::stderr().lock();
    let _ = out.write_all(render(line, no_color).as_bytes());
    let _ = out.flush();
}

// One redraw: back to column one, cursor hidden, the text, the rest of the line erased, and
// the cursor parked at column one again.
fn render(line: &Line, no_color: bool) -> String {
    let mut text = format!("\r{HIDE_CURSOR}");
    if line.spinner {
        let frame = FRAMES[line.frame];
        if no_color { text.push_str(&format!("{frame} {}", line.message)); }
        else { text.push_str(&format!("{BLUE}{frame}{RESET} {GRAY}{}{RESET}", line.message)); }
    } else if no_color { text.push_str(&line.message); }
    else { text.push_str(&format!("{GRAY}{}{RESET}", line.message)); }
    text.push_str(CLEAR_EOL);
    text.push('\r');
    text
}

// A terminating signal while the line is showing would otherwise leave the cursor hidden. The
// launcher's own signal handling is installed only once it is connected, after the line has
// been cleared, so until then a minimal handler restores the terminal and re-raises.
#[cfg(unix)]
fn install_cursor_guard() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        for signal in [libc::SIGINT, libc::SIGTERM, libc::SIGHUP, libc::SIGQUIT] {
            crate::signals::install_handler(signal, restore_and_die);
        }
    });
}

#[cfg(unix)]
extern "C" fn restore_and_die(signal: libc::c_int) {
    // Only async-signal-safe calls here: a raw write, then the default action.
    const RESTORE: &[u8] = b"\r\x1b[K\x1b[?25h";
    unsafe { libc::write(2, RESTORE.as_ptr() as *const libc::c_void, RESTORE.len()); }
    crate::signals::raise_default(signal);
}

#[cfg(not(unix))]
fn install_cursor_guard() {}

#[cfg(test)]
mod tests {
    use super::*;

    fn line(message: &str, spinner: bool, frame: usize) -> Line {
        Line { shown: true, spinner, frame, turned: None, message: message.to_string() }
    }

    #[test]
    fn a_step_is_gray_text_on_an_erased_line_with_the_cursor_hidden_at_column_one() {
        assert_eq!(render(&line("Starting…", false, 0), false),
            "\r\x1b[?25l\x1b[38;5;245mStarting…\x1b[0m\x1b[K\r");
    }

    #[test]
    fn a_spinner_is_a_blue_braille_frame_then_a_space_then_the_gray_message() {
        assert_eq!(render(&line("Fetching dependencies 3/128 (12.3 MB)…", true, 0), false),
            "\r\x1b[?25l\x1b[38;5;68m⠋\x1b[0m \x1b[38;5;245mFetching dependencies 3/128 (12.3 MB)…\x1b[0m\x1b[K\r");
        assert_eq!(render(&line("x", true, 3), false), "\r\x1b[?25l\x1b[38;5;68m⠸\x1b[0m \x1b[38;5;245mx\x1b[0m\x1b[K\r");
    }

    #[test]
    fn without_colour_only_the_cursor_and_erase_sequences_remain() {
        assert_eq!(render(&line("Updating…", false, 0), true), "\r\x1b[?25lUpdating…\x1b[K\r");
        assert_eq!(render(&line("Downloading Java 21…", true, 9), true), "\r\x1b[?25l⠏ Downloading Java 21…\x1b[K\r");
    }

    #[test]
    fn the_spinner_wraps_around_its_ten_frames() {
        assert_eq!(FRAMES.len(), 10);
        assert_eq!(FRAMES[(9 + 1) % FRAMES.len()], '⠋');
    }
}
