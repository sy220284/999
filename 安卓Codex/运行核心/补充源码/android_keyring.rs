//! Android-only DefaultKeyringStore backend. Host persists ciphertext with Android Keystore.
//! Protocol: version:u8, op:u8, service/account/value as u32-BE-length UTF-8 fields.
//! Reply: status:u8, u32-BE-length UTF-8 value. Neither requests nor replies are logged.
use super::CredentialStoreError;
use super::DefaultKeyringStore;
use super::KeyringStore;
use std::io;
use std::io::Read;
use std::io::Write;
use std::os::fd::AsRawFd;
use std::os::unix::net::UnixStream;
use std::time::Duration;

fn failure(kind: io::ErrorKind, message: &'static str) -> CredentialStoreError {
    CredentialStoreError::new(keyring::Error::PlatformFailure(Box::new(io::Error::new(kind, message))))
}
fn transport(error: io::Error) -> CredentialStoreError {
    failure(error.kind(), "Android keyring transport failed")
}
fn field(stream: &mut UnixStream, value: &str, limit: usize) -> Result<(), CredentialStoreError> {
    if value.len() > limit {
        return Err(failure(io::ErrorKind::InvalidInput, "Android keyring field too large"));
    }
    stream.write_all(&(value.len() as u32).to_be_bytes()).map_err(transport)?;
    stream.write_all(value.as_bytes()).map_err(transport)
}
fn request(operation: u8, service: &str, account: &str, value: &str) -> Result<(u8, String), CredentialStoreError> {
    if service.is_empty() || account.is_empty() || service.contains('\0') || account.contains('\0') {
        return Err(failure(io::ErrorKind::InvalidInput, "Invalid Android keyring identity"));
    }
    let path = std::env::var("CODEX_ANDROID_VAULT_SOCKET")
        .map_err(|_| failure(io::ErrorKind::NotConnected, "Android keyring host is unavailable"))?;
    let expected_pid: i32 = std::env::var("CODEX_ANDROID_VAULT_HOST_PID").ok()
        .and_then(|value| value.parse().ok()).filter(|pid| *pid > 0)
        .ok_or_else(|| failure(io::ErrorKind::PermissionDenied, "Android keyring host identity is missing"))?;
    let mut stream = UnixStream::connect(path).map_err(transport)?;
    stream.set_read_timeout(Some(Duration::from_secs(10))).map_err(transport)?;
    stream.set_write_timeout(Some(Duration::from_secs(10))).map_err(transport)?;
    // Authenticate the host process as well as the app UID; a tool child with the same UID is insufficient.
    let mut peer: libc::ucred = unsafe { std::mem::zeroed() };
    let mut length = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
    let result = unsafe {
        libc::getsockopt(stream.as_raw_fd(), libc::SOL_SOCKET, libc::SO_PEERCRED,
            (&mut peer as *mut libc::ucred).cast(), &mut length)
    };
    if result != 0 || length as usize != std::mem::size_of::<libc::ucred>()
        || peer.pid != expected_pid || peer.uid != unsafe { libc::getuid() }
    {
        return Err(failure(io::ErrorKind::PermissionDenied, "Android keyring host identity mismatch"));
    }
    stream.write_all(&[1, operation]).map_err(transport)?;
    field(&mut stream, service, 4096)?;
    field(&mut stream, account, 4096)?;
    field(&mut stream, value, 1_048_576)?;
    let mut status = [0_u8; 1]; stream.read_exact(&mut status).map_err(transport)?;
    let mut length = [0_u8; 4]; stream.read_exact(&mut length).map_err(transport)?;
    let length = u32::from_be_bytes(length) as usize;
    if length > 1_048_576 {
        return Err(failure(io::ErrorKind::InvalidData, "Android keyring reply exceeds limit"));
    }
    let mut bytes = vec![0_u8; length]; stream.read_exact(&mut bytes).map_err(transport)?;
    let value = String::from_utf8(bytes).map_err(|_| failure(io::ErrorKind::InvalidData, "Invalid Android keyring reply"))?;
    if status[0] == 255 {
        // Host errors intentionally omit platform exception chains and credential content.
        return Err(failure(io::ErrorKind::PermissionDenied, "Android keyring operation was rejected"));
    }
    Ok((status[0], value))
}
impl KeyringStore for DefaultKeyringStore {
    fn load(&self, service: &str, account: &str) -> Result<Option<String>, CredentialStoreError> {
        let (status, value) = request(1, service, account, "")?;
        match status { 0 => Ok(None), 1 => Ok(Some(value)), _ => Err(failure(io::ErrorKind::InvalidData, "Unexpected Android keyring response")) }
    }
    fn save(&self, service: &str, account: &str, value: &str) -> Result<(), CredentialStoreError> {
        match request(2, service, account, value)?.0 { 2 => Ok(()), _ => Err(failure(io::ErrorKind::InvalidData, "Unexpected Android keyring response")) }
    }
    fn delete(&self, service: &str, account: &str) -> Result<bool, CredentialStoreError> {
        match request(3, service, account, "")?.0 { 3 => Ok(true), 4 => Ok(false), _ => Err(failure(io::ErrorKind::InvalidData, "Unexpected Android keyring response")) }
    }
}
