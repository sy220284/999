//! Android provider: authenticated local host socket, bounded JSON frames, guard-aware cancellation.
use crate::{UserVerificationProvider, UserVerificationKeyNamespace, UserVerificationRequestGuard,
    UserVerificationError, UserVerificationUnavailableReason as Unavailable,
    UserVerificationFailureReason as Failure, UserVerificationCancellationReason as Cancellation,
    UserVerificationStatus, UserVerificationKeyInfo, UserVerificationKeyCreation,
    UserVerificationKeyDeletion, UserVerificationRequest, UserVerificationProof};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use serde_json::{Value, json};
use std::{io::{Read, Write}, os::{fd::AsRawFd, unix::net::UnixStream}, time::{Duration, Instant}};
const LIMIT: usize = 16 * 1024;
pub(crate) struct NativeProvider { pub(crate) namespace: UserVerificationKeyNamespace }
fn failed() -> UserVerificationError { UserVerificationError::Failed { reason: Failure::ProviderError, message: "Android verification bridge failed".into() } }
fn category(value: &str) -> UserVerificationError {
    match value {
        "credentialMissing" => UserVerificationError::Unavailable { reason: Unavailable::CredentialMissing, message: "local credential is missing".into() },
        "biometricsUnavailable" => UserVerificationError::Unavailable { reason: Unavailable::BiometricsUnavailable, message: "strong biometrics unavailable".into() },
        "providerUnavailable" => UserVerificationError::Unavailable { reason: Unavailable::ProviderUnavailable, message: "protected provider unavailable".into() },
        "interrupted" | "cancelled" => UserVerificationError::Cancelled { reason: if value == "cancelled" { Cancellation::UserCancelled } else { Cancellation::Interrupted }, message: "verification cancelled".into() },
        "timeout" => UserVerificationError::Failed { reason: Failure::Timeout, message: "verification timed out".into() },
        "authenticationFailed" => UserVerificationError::Failed { reason: Failure::AuthenticationFailed, message: "authentication failed".into() },
        _ => failed(),
    }
}
fn read_guarded(stream: &mut UnixStream, bytes: &mut [u8], guard: &UserVerificationRequestGuard, deadline: Instant) -> Result<(), UserVerificationError> {
    let mut offset = 0;
    while offset < bytes.len() {
        guard.check()?;
        if Instant::now() >= deadline { return Err(category("timeout")); }
        match stream.read(&mut bytes[offset..]) {
            Ok(0) => return Err(failed()),
            Ok(count) => offset += count,
            Err(error) if matches!(error.kind(), std::io::ErrorKind::TimedOut | std::io::ErrorKind::WouldBlock | std::io::ErrorKind::Interrupted) => {},
            Err(_) => return Err(failed()),
        }
    }
    guard.check()
}
fn request(value: Value, guard: &UserVerificationRequestGuard) -> Result<Value, UserVerificationError> {
    guard.check()?;
    let path = std::env::var("CODEX_ANDROID_VERIFICATION_SOCKET").map_err(|_| category("providerUnavailable"))?;
    let pid: i32 = std::env::var("CODEX_ANDROID_VAULT_HOST_PID").ok().and_then(|v| v.parse().ok()).filter(|v| *v > 0).ok_or_else(failed)?;
    let mut stream = UnixStream::connect(path).map_err(|_| failed())?;
    stream.set_write_timeout(Some(Duration::from_secs(2))).map_err(|_| failed())?;
    stream.set_read_timeout(Some(Duration::from_millis(100))).map_err(|_| failed())?;
    let mut peer: libc::ucred = unsafe { std::mem::zeroed() };
    let mut length = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
    let checked = unsafe { libc::getsockopt(stream.as_raw_fd(), libc::SOL_SOCKET, libc::SO_PEERCRED, (&mut peer as *mut libc::ucred).cast(), &mut length) };
    if checked != 0 || length as usize != std::mem::size_of::<libc::ucred>() || peer.pid != pid || peer.uid != unsafe { libc::getuid() } { return Err(failed()); }
    let bytes = serde_json::to_vec(&value).map_err(|_| failed())?;
    if bytes.len() > LIMIT { return Err(failed()); }
    guard.check()?;
    stream.write_all(&(bytes.len() as u32).to_be_bytes()).and_then(|_| stream.write_all(&bytes)).map_err(|_| failed())?;
    let duration = if value["operation"] == "device" { 2 } else if value["operation"] == "verify" { 150 } else { 15 };
    let deadline = Instant::now() + Duration::from_secs(duration);
    let mut prefix = [0; 4];
    // Dropping this per-request socket on guard failure signals cancellation to the host.
    read_guarded(&mut stream, &mut prefix, guard, deadline)?;
    let length = u32::from_be_bytes(prefix) as usize;
    if length == 0 || length > LIMIT { return Err(failed()); }
    let mut bytes = vec![0; length];read_guarded(&mut stream, &mut bytes, guard, deadline)?;
    let result: Value = serde_json::from_slice(&bytes).map_err(|_| failed())?;
    guard.check()?;
    if let Some(error) = result["error"].as_str() { return Err(category(error)); }
    Ok(result)
}
fn credential(value: &Value) -> Result<UserVerificationKeyInfo, UserVerificationError> {
    let point = URL_SAFE_NO_PAD.decode(value["publicPoint"].as_str().ok_or_else(failed)?).map_err(|_| failed())?;
    let info = UserVerificationKeyInfo::from_sec1_public_key(&point)?;
    if value["credentialId"].as_str() != Some(info.credential_id.as_str()) { return Err(failed()); }
    Ok(info)
}
pub(crate) fn device_supported() -> bool { request(json!({"operation":"device"}), &UserVerificationRequestGuard::default()).ok().and_then(|v| v["supported"].as_bool()).unwrap_or(false) }
impl NativeProvider {
    fn request(&self, operation: &str, guard: &UserVerificationRequestGuard) -> Result<Value, UserVerificationError> {
        request(json!({"operation":operation,"namespace":self.namespace.label}), guard)
    }
}
impl UserVerificationProvider for NativeProvider {
    fn status(&self, guard: &UserVerificationRequestGuard) -> Result<UserVerificationStatus, UserVerificationError> {
        let result = self.request("status",guard)?;
        let unavailable = match result["unavailable"].as_str() {
            None => None,
            Some("credentialMissing") => Some(Unavailable::CredentialMissing),
            Some("biometricsUnavailable") => Some(Unavailable::BiometricsUnavailable),
            Some(_) => Some(Unavailable::ProviderUnavailable),
        };
        let credential = if result["credential"].is_object() { Some(credential(&result["credential"])?) } else { None };
        if credential.is_none() && unavailable.is_none() { return Err(failed()); }
        Ok(UserVerificationStatus { credential, unavailable_reason: unavailable, unavailable_message: unavailable.map(|_| "Android local verification is unavailable or not enrolled".into()) })
    }
    fn ensure_key(&self, guard: &UserVerificationRequestGuard) -> Result<UserVerificationKeyCreation, UserVerificationError> {
        let result=self.request("ensure",guard)?;guard.check()?;
        Ok(UserVerificationKeyCreation { created: result["created"].as_bool().ok_or_else(failed)?, credential:credential(&result["credential"])? })
    }
    fn delete(&self, guard: &UserVerificationRequestGuard) -> Result<UserVerificationKeyDeletion, UserVerificationError> {
        let result=self.request("delete",guard)?;guard.check()?;
        Ok(UserVerificationKeyDeletion { deleted_credential_id:result["deletedCredentialId"].as_str().map(str::to_owned) })
    }
    fn verify(&self, value: &UserVerificationRequest, guard: &UserVerificationRequestGuard) -> Result<UserVerificationProof, UserVerificationError> {
        let result=request(json!({"operation":"verify","namespace":self.namespace.label,"challenge":URL_SAFE_NO_PAD.encode(&value.challenge),"title":value.title,"description":value.description}),guard)?;
        guard.check()?;
        let id=result["credentialId"].as_str().ok_or_else(failed)?;
        let signature=result["signature"].as_str().ok_or_else(failed)?;
        let bytes=URL_SAFE_NO_PAD.decode(signature).map_err(|_| failed())?;
        if URL_SAFE_NO_PAD.decode(id).map_err(|_| failed())?.len()!=32 || bytes.is_empty() || bytes.len()>80 { return Err(failed()); }
        Ok(UserVerificationProof { credential_id:id.into(), signature:signature.into() })
    }
}
