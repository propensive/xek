// The deadline on a completion request. A shell that has asked for completions waits for the
// command to finish, and the user can do nothing meanwhile; so an invocation whose first
// argument is `{completions}` gives up after a limit, silently and with no candidates, however
// far it has got — starting a daemon, waiting for one, or waiting for its answer. A daemon it
// started is its own session and carries on, so the next request finds it running.
//
// The limit is `COMPLETION_TIMEOUT_MS` (see `settings.rs`), 3000 by default; 0 is no limit.
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::time::Duration;

pub const DEFAULT_LIMIT_MS: u64 = 3000;
// The exit status of a request that ran out of time: that of `timeout(1)`.
pub const EXIT_STATUS: i32 = 124;
// How much longer the launcher lives, at most, to finish something it must not abandon.
const HOLD_GRACE: Duration = Duration::from_secs(2);
const HOLD_POLL: Duration = Duration::from_millis(10);

static ARMED: AtomicBool = AtomicBool::new(false);
static HOLDS: AtomicUsize = AtomicUsize::new(0);

// Work the deadline waits for, for as long as the value lives: handing a new daemon its
// environment and recording its pid, which an exit halfway through would leave a daemon
// without. Bounded by `HOLD_GRACE`, since a daemon that never reads its environment never
// lets the first of those finish.
pub struct Hold(());

pub fn hold() -> Hold {
    HOLDS.fetch_add(1, Ordering::SeqCst);
    Hold(())
}

impl Drop for Hold {
    fn drop(&mut self) { HOLDS.fetch_sub(1, Ordering::SeqCst); }
}

// The limit a setting's value gives: the default when it is unset or not a number.
pub fn limit(value: Option<&str>) -> Option<Duration> {
    match value.and_then(|value| value.trim().parse::<u64>().ok()).unwrap_or(DEFAULT_LIMIT_MS) {
        0 => None,
        milliseconds => Some(Duration::from_millis(milliseconds)),
    }
}

// Whether this invocation is a completion request under a deadline. Such a request does
// nothing that could not finish within it and would only be cut short again on the next one:
// it never downloads a JVM.
pub fn armed() -> bool { ARMED.load(Ordering::SeqCst) }

// Starts the clock. Nothing is written when it runs out: stdout is the shell's list of
// candidates, and stderr the terminal the prompt is being drawn on.
pub fn arm() {
    let setting = crate::settings::get(crate::settings::COMPLETION_TIMEOUT_MS);
    let Some(limit) = limit(setting.as_deref().and_then(|value| value.to_str())) else { return };
    ARMED.store(true, Ordering::SeqCst);
    std::thread::spawn(move || {
        std::thread::sleep(limit);
        crate::debug!("deadline: completion request exceeded {:?}", limit);
        let mut waited = Duration::ZERO;
        while HOLDS.load(Ordering::SeqCst) > 0 && waited < HOLD_GRACE {
            std::thread::sleep(HOLD_POLL);
            waited += HOLD_POLL;
        }
        std::process::exit(EXIT_STATUS);
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_limit_is_three_seconds_unless_set() {
        assert_eq!(limit(None), Some(Duration::from_millis(3000)));
        assert_eq!(limit(Some("nonsense")), Some(Duration::from_millis(3000)));
        assert_eq!(limit(Some("250")), Some(Duration::from_millis(250)));
    }

    #[test]
    fn a_limit_of_zero_is_no_limit() {
        assert_eq!(limit(Some("0")), None);
    }

    #[test]
    fn a_hold_lasts_as_long_as_its_value() {
        let before = HOLDS.load(Ordering::SeqCst);
        let hold = hold();
        assert_eq!(HOLDS.load(Ordering::SeqCst), before + 1);
        drop(hold);
        assert_eq!(HOLDS.load(Ordering::SeqCst), before);
    }
}
