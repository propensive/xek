use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

pub fn find_java(minimum: u16, preferred: u16, bundle: &str, do_download: bool) -> Option<PathBuf> {
    if let Some(path) = which("java") {
        if check_version(&path, minimum) { return Some(path); }
    }

    let java_home = crate::state::data_home().join("java");
    let preferred_install = java_home.join(format!("{}-{}", preferred, bundle))
        .join("bin").join(JAVA);
    if preferred_install.exists() && is_executable(&preferred_install) {
        return Some(preferred_install);
    }

    // The link to the preferred version may be missing: on Windows, making it needs
    // Developer Mode or elevation. Any earlier download is just as good if it is new enough.
    if let Some(downloaded) = find_downloaded(&java_home, minimum, bundle) {
        return Some(downloaded);
    }

    // update-alternatives --list java (Linux)
    if let Ok(output) = Command::new("update-alternatives").arg("--list").arg("java").output() {
        if output.status.success() {
            for line in String::from_utf8_lossy(&output.stdout).lines() {
                let candidate = PathBuf::from(line);
                if check_version(&candidate, minimum) { return Some(candidate); }
            }
        }
    }

    // /usr/libexec/java_home -v N (macOS)
    let version_arg = format!("-v{}", minimum);
    if let Ok(output) = Command::new("/usr/libexec/java_home").arg(&version_arg).output() {
        if output.status.success() {
            let java_home = String::from_utf8_lossy(&output.stdout).trim().to_string();
            if !java_home.is_empty() {
                let candidate = PathBuf::from(java_home).join("bin").join("java");
                if check_version(&candidate, minimum) { return Some(candidate); }
            }
        }
    }

    if do_download { return download(preferred, bundle); }
    None
}

// The name of the Java executable in a JDK's `bin` directory.
const JAVA: &str = if cfg!(windows) { "java.exe" } else { "java" };

// The newest JVM unpacked by `download` into `java_home` that satisfies `minimum`. Directories
// are named `<version>-<bundle>`, and are tried in descending order of version.
fn find_downloaded(java_home: &Path, minimum: u16, bundle: &str) -> Option<PathBuf> {
    let suffix = format!("-{}", bundle);
    let mut candidates: Vec<(Vec<u32>, PathBuf)> = std::fs::read_dir(java_home).ok()?
        .flatten()
        .filter_map(|entry| {
            let name = entry.file_name().into_string().ok()?;
            let version = name.strip_suffix(&suffix)?;
            let numbers = version.split(|c: char| !c.is_ascii_digit())
                .filter_map(|part| part.parse::<u32>().ok())
                .collect();
            Some((numbers, entry.path().join("bin").join(JAVA)))
        })
        .collect();
    candidates.sort_by(|left, right| right.0.cmp(&left.0));
    candidates.into_iter().map(|(_, java)| java).find(|java| check_version(java, minimum))
}

pub fn java_not_found_message(minimum: u16, invocation: &str) {
    eprintln!("\nJava {} or later is required, but no suitable version of Java was found.", minimum);
    let mut commands = Vec::new();
    if which("pacman").is_some() { commands.push("pacman -S jre-openjdk".to_string()); }
    if which("emerge").is_some() { commands.push(format!("emerge dev-java/openjdk-{}", minimum)); }
    if which("yum").is_some()    { commands.push(format!("yum install java-{}-openjdk", minimum)); }
    if which("dnf").is_some()    { commands.push(format!("dnf install java-{}-openjdk", minimum)); }
    if which("apt").is_some()    { commands.push(format!("apt update && apt install openjdk-{}-jre", minimum)); }
    if which("zypper").is_some() { commands.push(format!("zypper install openjdk-{}-headless", minimum)); }
    if which("brew").is_some()   { commands.push("brew update && brew install java".to_string()); }
    commands.push(format!("{} --install-java", invocation));
    if commands.len() == 1 {
        eprintln!("You can install Java with the following command:\n");
    } else {
        eprintln!("You can install Java with one of the following commands:\n");
    }
    for command in &commands { eprintln!("  > {}", command); }
    eprintln!();
}

pub fn check_version(java: &Path, minimum: u16) -> bool {
    let output = match Command::new(java).arg("-version").output() {
        Ok(output) => output,
        Err(_) => return false,
    };
    let combined = String::from_utf8_lossy(&output.stderr).to_string()
        + &String::from_utf8_lossy(&output.stdout);
    for line in combined.lines() {
        if !line.contains("version") { continue; }
        let Some(start) = line.find('"') else { continue; };
        let after_quote = &line[start + 1..];
        let Some(end) = after_quote.find('"') else { continue; };
        let version_text = &after_quote[..end];
        let major_text = version_text.split('.').next().unwrap_or("0");
        if let Ok(major) = major_text.parse::<u16>() {
            return major >= minimum;
        }
    }
    false
}

fn is_executable(path: &Path) -> bool {
    #[cfg(unix)]
    unsafe {
        use std::ffi::CString;
        let c_path = CString::new(path.as_os_str().to_string_lossy().as_bytes()).unwrap_or_default();
        libc::access(c_path.as_ptr(), libc::X_OK) == 0
    }
    #[cfg(windows)]
    { path.exists() }
}

pub fn which(command: &str) -> Option<PathBuf> {
    let path_var = std::env::var_os("PATH")?;
    for directory in std::env::split_paths(&path_var) {
        let candidate = directory.join(command);
        #[cfg(windows)]
        {
            let with_extension = candidate.with_extension("exe");
            if with_extension.exists() { return Some(with_extension); }
        }
        if is_executable(&candidate) { return Some(candidate); }
    }
    None
}

fn arch_label() -> &'static str {
    match std::env::consts::ARCH {
        "x86_64"  => "x64",
        "x86"     => "x86",
        "aarch64" | "arm64" => "aarch64",
        other     => other,
    }
}

fn os_label() -> &'static str {
    match std::env::consts::OS {
        "linux"   => "linux",
        "macos"   => "mac",
        "windows" => "windows",
        "solaris" => "solaris",
        other     => other,
    }
}

pub fn download(preferred: u16, bundle: &str) -> Option<PathBuf> {
    let data_home = crate::state::data_home();
    let java_home = data_home.join("java");
    let temp_root = data_home.join("tmp");
    let _ = std::fs::create_dir_all(&temp_root);
    let temp_dir = make_temp_dir(&temp_root)?;
    let url = format!(
        "https://api.adoptium.net/v3/binary/latest/{preferred}/ga/{os}/{arch}/{bundle}/hotspot/normal/eclipse",
        preferred = preferred, os = os_label(), arch = arch_label(), bundle = bundle,
    );

    let have_curl = which("curl").is_some();
    let have_wget = which("wget").is_some();
    if !have_curl && !have_wget {
        let _ = std::fs::remove_dir(&temp_dir);
        eprintln!("Could not download Java: neither curl nor wget is on the PATH.");
        return None;
    }

    crate::xek::spin(&format!("Downloading Java {preferred}…"));
    // Adoptium serves a zip for Windows, and a gzipped tarball for everything else.
    let archive = temp_dir.join(if cfg!(windows) { "jdk.zip" } else { "jdk.tar.gz" });
    let download = if have_curl {
        Command::new("curl").args(["-fsSL", "-o"]).arg(&archive).arg(&url).spawn()
    } else {
        Command::new("wget").args(["-q", "-O"]).arg(&archive).arg(&url).spawn()
    };
    // Poll rather than wait, so the spinner keeps turning while the download runs.
    let download_status = download.and_then(|mut child| loop {
        if let Some(status) = child.try_wait()? { break Ok(status); }
        crate::xek::tick();
        std::thread::sleep(std::time::Duration::from_millis(50));
    });
    if !matches!(&download_status, Ok(s) if s.success()) {
        crate::xek::clear();
        eprintln!("The download of Java {preferred} failed.");
        let _ = std::fs::remove_dir_all(&temp_dir);
        return None;
    }
    crate::xek::step("Unpacking Java…");
    let tar_status = Command::new(tar())
        .arg(if cfg!(windows) { "xf" } else { "xzf" }).arg(&archive).arg("-C").arg(&temp_dir)
        .stdin(Stdio::null())
        .status();
    let _ = std::fs::remove_file(&archive);
    if !matches!(&tar_status, Ok(s) if s.success()) {
        crate::xek::clear();
        eprintln!("Unpacking Java {preferred} failed.");
        let _ = std::fs::remove_dir_all(&temp_dir);
        return None;
    }
    crate::xek::clear();

    let fail = |message: String| {
        eprintln!("{}", message);
        let _ = std::fs::remove_dir_all(&temp_dir);
        None::<PathBuf>
    };

    let Some(release_file) = find_release(&temp_dir) else {
        return fail(format!("The download of Java {preferred} contains no `release` file."));
    };
    let Some(full_version) = read_java_version(&release_file) else {
        return fail(format!("Could not read the version of Java from {}.", release_file.display()));
    };
    let release_dir = release_file.parent().unwrap_or(&temp_dir).to_path_buf();
    let target_dir = java_home.join(format!("{}-{}", full_version, bundle));
    let _ = std::fs::remove_dir_all(&target_dir);
    let _ = std::fs::create_dir_all(&java_home);
    if let Err(error) = rename(&release_dir, &target_dir) {
        return fail(format!("Could not move Java {full_version} into {}: {error}", java_home.display()));
    }
    let _ = std::fs::remove_dir_all(&temp_dir);
    let symlink = java_home.join(format!("{}-{}", preferred, bundle));
    #[cfg(unix)] {
        let _ = std::fs::remove_file(&symlink);
        let _ = std::os::unix::fs::symlink(&target_dir, &symlink);
    }
    #[cfg(windows)] {
        let _ = std::fs::remove_dir(&symlink);
        let _ = std::os::windows::fs::symlink_dir(&target_dir, &symlink);
    }
    Some(target_dir.join("bin").join(JAVA))
}

// Windows' own `tar.exe`, which reads zip files. A GNU tar earlier on the `PATH`, as Git and
// MSYS install, cannot, and would take the `C:` of an absolute path for a remote host.
fn tar() -> PathBuf {
    #[cfg(windows)] {
        if let Some(root) = std::env::var_os("SystemRoot") {
            let system_tar = PathBuf::from(root).join("System32").join("tar.exe");
            if system_tar.exists() { return system_tar; }
        }
    }
    PathBuf::from("tar")
}

// Renames a freshly unpacked directory. On Windows, a virus scanner may still have its files
// open for a moment, which fails the rename with "access denied", so it is retried.
fn rename(from: &Path, to: &Path) -> std::io::Result<()> {
    let mut attempts = 0;
    loop {
        match std::fs::rename(from, to) {
            Err(_) if cfg!(windows) && attempts < 20 => {
                attempts += 1;
                std::thread::sleep(std::time::Duration::from_millis(250));
            }
            result => return result,
        }
    }
}

fn make_temp_dir(root: &Path) -> Option<PathBuf> {
    for _ in 0..6 {
        let nonce: u64 = crate::now_ms() ^ std::process::id() as u64;
        let candidate = root.join(format!("eth-{}", nonce));
        if std::fs::create_dir(&candidate).is_ok() { return Some(candidate); }
    }
    None
}

fn find_release(root: &Path) -> Option<PathBuf> {
    walk(root, 6).into_iter().find(|entry| {
        entry.file_name().and_then(|name| name.to_str()) == Some("release")
    })
}

fn walk(root: &Path, depth: u32) -> Vec<PathBuf> {
    let mut result = Vec::new();
    if depth == 0 { return result; }
    if let Ok(entries) = std::fs::read_dir(root) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                result.extend(walk(&path, depth - 1));
            } else {
                result.push(path);
            }
        }
    }
    result
}

fn read_java_version(release_file: &Path) -> Option<String> {
    let content = std::fs::read_to_string(release_file).ok()?;
    for line in content.lines() {
        if let Some(rest) = line.trim().strip_prefix("JAVA_VERSION=") {
            return Some(rest.trim_matches('"').to_string());
        }
    }
    None
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    fn fake_java(java_home: &Path, directory: &str, version: &str) {
        let bin = java_home.join(directory).join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        let java = bin.join(JAVA);
        std::fs::write(&java, format!("#!/bin/sh\necho 'openjdk version \"{version}\"' >&2\n")).unwrap();
        std::fs::set_permissions(&java, std::fs::Permissions::from_mode(0o755)).unwrap();
    }

    #[test]
    fn a_download_is_found_without_the_link_to_it() {
        let java_home = std::env::temp_dir().join(format!("xek-java-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&java_home);
        fake_java(&java_home, "17.0.2-jre", "17.0.2");
        fake_java(&java_home, "25.0.1-jre", "25.0.1");
        fake_java(&java_home, "26.0.0-jdk", "26.0.0");
        let found = find_downloaded(&java_home, 25, "jre");
        let too_old = find_downloaded(&java_home, 26, "jre");
        let _ = std::fs::remove_dir_all(&java_home);
        assert_eq!(found, Some(java_home.join("25.0.1-jre").join("bin").join(JAVA)));
        assert_eq!(too_old, None);
    }
}
