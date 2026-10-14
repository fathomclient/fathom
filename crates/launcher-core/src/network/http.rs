//! The only place in the codebase that opens a socket.
//!
//! Everything goes through [`HttpClient::send`], which:
//!
//! 1. evaluates the [`NetworkPolicy`],
//! 2. builds the URL from a [`Destination`] enum variant (callers cannot supply
//!    a host),
//! 3. records the attempt and its outcome in the [`NetworkJournal`],
//! 4. bounds the response size.
//!
//! Cookies are not enabled as a ureq feature, so there is no cookie jar to
//! persist and no cross-request identifier can be established by a server.

use std::io::Read;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use serde::de::DeserializeOwned;

use super::journal::{NetworkJournal, Outcome};
use super::policy::{NetworkPolicy, PerActionGrant, PolicyDenial, Purpose, RequestDescriptor};
use crate::secret::Secret;

/// Cap on any single in-memory response. Metadata documents are far smaller
/// than this; anything larger is either a hostile endpoint or a bug.
const MAX_IN_MEMORY_RESPONSE: u64 = 32 * 1024 * 1024;

/// Cap on a streamed download. The 1.8.9 client jar is ~8 MB and the largest
/// library is far smaller, but modpack-era jars can be larger, so this is
/// generous rather than tight.
pub const MAX_STREAMED_DOWNLOAD: u64 = 512 * 1024 * 1024;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(30);
const GLOBAL_TIMEOUT: Duration = Duration::from_secs(300);

/// The whole of one mod-index metadata request: connect, send, read.
///
/// The global timeout above is sized for a large download. A search or a
/// project page is a few kilobytes, and somebody waiting on a results list is
/// better told after twenty seconds that the index is not answering than left
/// with a spinner for five minutes.
const METADATA_TIMEOUT: Duration = Duration::from_secs(20);

/// The longest wait a server's `Retry-After` is believed for. Past this it is
/// either a misconfiguration or not worth waiting on inside a launcher.
const MAX_RETRY_AFTER: u64 = 15 * 60;

#[derive(Debug, thiserror::Error)]
pub enum NetworkError {
    #[error("request refused by network policy: {0}")]
    Denied(#[from] PolicyDenial),
    #[error("transport error contacting {host}: {source}")]
    Transport {
        host: &'static str,
        #[source]
        source: Box<ureq::Error>,
    },
    #[error("{host} returned HTTP {status}")]
    Status {
        host: &'static str,
        status: u16,
        body: String,
    },
    #[error("response from {host} exceeded the {limit} byte limit")]
    ResponseTooLarge { host: &'static str, limit: u64 },
    #[error("could not parse the response from {host}: {source}")]
    Malformed {
        host: &'static str,
        #[source]
        source: serde_json::Error,
    },
    #[error("i/o error while streaming from {host}: {source}")]
    Io {
        host: &'static str,
        #[source]
        source: std::io::Error,
    },
    #[error("{host} redirected more than {MAX_REDIRECTS} times")]
    TooManyRedirects { host: &'static str },
    #[error("{host} sent a redirect with no usable Location header")]
    MalformedRedirect { host: &'static str },
    #[error("{host} redirected to {location}, which is not on the allowlist. Refusing to follow.")]
    RedirectOffAllowlist {
        host: &'static str,
        location: String,
    },
    #[error("the transfer was cancelled")]
    Cancelled,
}

/// How many hops we will follow before giving up.
pub const MAX_REDIRECTS: usize = 5;

/// Resolve a `Location` header against the request that produced it.
///
/// Returns `None` when the target is not somewhere we are allowed to go, which
/// the caller turns into a refusal. Both forms are handled:
///
/// - absolute (`https://host/path`) -- re-validated through the allowlist;
/// - relative (`/path`) -- same host, new path, still path-validated.
fn resolve_redirect(from: &RequestDescriptor, location: &str) -> Option<RequestDescriptor> {
    if location.starts_with('/') {
        // Same host. `RequestDescriptor::new` plus the policy's path check will
        // reject a protocol-relative `//evil.example/...`.
        let candidate = RequestDescriptor::new(
            from.destination,
            from.purpose,
            from.feature,
            location.to_owned(),
        );
        return crate::network::policy::path_is_acceptable(&candidate.path).then_some(candidate);
    }
    crate::network::policy::descriptor_from_url(location, from.purpose, from.feature).ok()
}

/// One hop's result, before redirects are resolved.
struct RawResponse {
    status: u16,
    body: Vec<u8>,
    location: Option<String>,
    retry_after: Option<u64>,
}

/// Read a wait in whole seconds from a rate-limit response's headers.
///
/// Only the delta-seconds form of `Retry-After` is understood; the HTTP-date
/// form would need a clock comparison against a server we do not trust to have
/// one, and no index Fathom talks to sends it.
fn retry_after(status: u16, headers: &ureq::http::HeaderMap) -> Option<u64> {
    if status != 429 && status != 503 {
        return None;
    }
    ["retry-after", "x-ratelimit-reset"]
        .iter()
        .find_map(|name| {
            headers
                .get(*name)?
                .to_str()
                .ok()?
                .trim()
                .parse::<u64>()
                .ok()
        })
        .map(|seconds| seconds.min(MAX_RETRY_AFTER))
}

#[derive(Debug)]
enum CopyError {
    Cancelled,
    Io(std::io::Error),
}

/// `std::io::copy`, but it asks `keep_going` between chunks.
fn copy_cancellable(
    reader: &mut dyn Read,
    sink: &mut dyn std::io::Write,
    keep_going: &dyn Fn() -> bool,
) -> Result<u64, CopyError> {
    const CHUNK: usize = 64 * 1024;
    let mut buf = vec![0u8; CHUNK];
    let mut total = 0u64;

    loop {
        if !keep_going() {
            return Err(CopyError::Cancelled);
        }
        let read = match reader.read(&mut buf) {
            Ok(0) => return Ok(total),
            Ok(n) => n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(CopyError::Io(e)),
        };
        sink.write_all(buf.get(..read).unwrap_or_default())
            .map_err(CopyError::Io)?;
        total += read as u64;
    }
}

/// Request body.
#[derive(Debug)]
pub enum Body<'a> {
    Empty,
    Json(&'a serde_json::Value),
    Form(&'a [(&'a str, &'a str)]),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Method {
    Get,
    Post,
}

/// A completed response. `status` is always populated, including for 4xx and
/// 5xx, because the auth chain needs to read error bodies (XSTS `XErr`).
#[derive(Debug, Clone)]
pub struct Response {
    pub status: u16,
    pub body: Vec<u8>,
    /// How long the server asked us to wait before the next request, in
    /// seconds, when it said so on a 429 or 503 (`Retry-After`, or Modrinth's
    /// `X-Ratelimit-Reset`). Capped; `None` when it did not say.
    pub retry_after: Option<u64>,
}

impl Response {
    pub fn is_success(&self) -> bool {
        (200..300).contains(&self.status)
    }

    pub fn json<T: DeserializeOwned>(&self, host: &'static str) -> Result<T, NetworkError> {
        serde_json::from_slice(&self.body)
            .map_err(|source| NetworkError::Malformed { host, source })
    }

    pub fn text_lossy(&self) -> String {
        String::from_utf8_lossy(&self.body).into_owned()
    }
}

/// Shared, cheap-to-clone HTTP client.
#[derive(Clone)]
pub struct HttpClient {
    agent: ureq::Agent,
    policy: Arc<Mutex<NetworkPolicy>>,
    journal: NetworkJournal,
}

impl std::fmt::Debug for HttpClient {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("HttpClient")
            .field("policy", &self.policy())
            .finish_non_exhaustive()
    }
}

impl HttpClient {
    pub fn new(policy: NetworkPolicy, journal: NetworkJournal, user_agent: &str) -> Self {
        let config = ureq::Agent::config_builder()
            .user_agent(user_agent)
            .timeout_connect(Some(CONNECT_TIMEOUT))
            .timeout_global(Some(GLOBAL_TIMEOUT))
            // We handle non-2xx ourselves: the XSTS error body carries the
            // XErr code we need to explain the failure to the user.
            .http_status_as_error(false)
            // Redirects are followed by hand, in `stream`, so that every hop is
            // re-checked against the allowlist. Left to ureq, a 302 from a
            // Mojang host to anywhere at all would be followed silently, which
            // would quietly undo the guarantee the allowlist exists to give.
            .max_redirects(0)
            .build();

        Self {
            agent: config.into(),
            policy: Arc::new(Mutex::new(policy)),
            journal,
        }
    }

    pub fn journal(&self) -> &NetworkJournal {
        &self.journal
    }

    pub fn policy(&self) -> NetworkPolicy {
        self.policy
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .clone()
    }

    pub fn set_policy(&self, policy: NetworkPolicy) {
        *self.policy.lock().unwrap_or_else(PoisonError::into_inner) = policy;
    }

    /// Evaluate policy and journal the denial if it fails. Separated so that
    /// callers which only want to *ask* (the UI, when greying out a button) can
    /// do so without a socket.
    pub fn check(
        &self,
        request: &RequestDescriptor,
        grant: Option<&PerActionGrant>,
    ) -> Result<(), PolicyDenial> {
        let decision = self.policy().evaluate(request, grant);
        if let Err(denial) = decision {
            self.journal.record(request, Outcome::Denied(denial));
        }
        decision
    }

    /// The one function that performs a request.
    /// Send a request, following redirects by hand.
    ///
    /// Redirects are not disabled -- allowlisted hosts use them, and
    /// `maven.legacyfabric.net` 302s every artifact to `repo.legacyfabric.net`
    /// -- but the transport is not allowed to follow them on its own. Each hop
    /// is re-resolved and re-checked against the allowlist here, so the rule
    /// that a caller cannot express a hostname holds for hop five as much as
    /// for hop one.
    ///
    /// Only redirects on a GET are followed. A redirected POST would mean
    /// replaying a body -- possibly one carrying a credential -- at a host the
    /// original request did not name, and none of our POST endpoints redirect.
    pub fn send(
        &self,
        request: &RequestDescriptor,
        grant: Option<&PerActionGrant>,
        method: Method,
        body: Body<'_>,
        headers: &[(&str, &Secret)],
    ) -> Result<Response, NetworkError> {
        let mut current = request.clone();

        // The first hop carries whatever body the caller gave. Every hop after
        // it is a GET with no body -- `send_once` only reports a redirect for a
        // GET, so a later hop cannot be anything else.
        let mut first = Some(body);

        for _ in 0..=MAX_REDIRECTS {
            let this_body = first.take().unwrap_or(Body::Empty);
            let response = self.send_once(&current, grant, method, this_body, headers)?;

            let Some(location) = response.location.as_deref() else {
                return Ok(Response {
                    status: response.status,
                    body: response.body,
                    retry_after: response.retry_after,
                });
            };

            // Redirected. `send_once` only reports a location for a GET.
            let next = resolve_redirect(&current, location).ok_or_else(|| {
                NetworkError::RedirectOffAllowlist {
                    host: current.destination.host(),
                    location: location.to_owned(),
                }
            })?;
            self.check(&next, grant)?;
            current = next;
        }

        Err(NetworkError::TooManyRedirects {
            host: current.destination.host(),
        })
    }

    /// One hop. Reports a `Location` rather than following it.
    fn send_once(
        &self,
        request: &RequestDescriptor,
        grant: Option<&PerActionGrant>,
        method: Method,
        body: Body<'_>,
        headers: &[(&str, &Secret)],
    ) -> Result<RawResponse, NetworkError> {
        self.check(request, grant)?;

        let host = request.destination.host();
        let url = request.url();

        tracing::debug!(
            destination = %request.destination,
            purpose = request.purpose.label(),
            feature = request.feature.label(),
            path = %request.path,
            "outbound request"
        );

        // ureq 3 uses a typestate on the builder: GET is `WithoutBody`, POST is
        // `WithBody`, so the two paths cannot be unified into one binding.
        let sent = match (method, body) {
            (Method::Get, _) if request.purpose == Purpose::ResolveModMetadata => apply_headers(
                self.agent
                    .get(&url)
                    .config()
                    .timeout_global(Some(METADATA_TIMEOUT))
                    .build(),
                headers,
            )
            .call(),
            (Method::Get, _) => apply_headers(self.agent.get(&url), headers).call(),
            (Method::Post, Body::Empty) => {
                apply_headers(self.agent.post(&url), headers).send_empty()
            }
            (Method::Post, Body::Json(value)) => {
                apply_headers(self.agent.post(&url), headers).send_json(value)
            }
            (Method::Post, Body::Form(pairs)) => apply_headers(self.agent.post(&url), headers)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .send(encode_form(pairs)),
        };

        let mut response = match sent {
            Ok(r) => r,
            Err(e) => {
                self.journal
                    .record(request, Outcome::Failed(describe_transport(&e)));
                return Err(NetworkError::Transport {
                    host,
                    source: Box::new(e),
                });
            }
        };

        let status = response.status().as_u16();
        let retry_after = retry_after(status, response.headers());

        // A redirect on a GET is reported so `send` can re-check the next hop.
        // On anything else it is ignored: replaying a body at a host the caller
        // did not name is not something this client does.
        let location = if matches!(method, Method::Get) && (300..400).contains(&status) {
            response
                .headers()
                .get("location")
                .and_then(|v| v.to_str().ok())
                .map(str::to_owned)
        } else {
            None
        };

        let bytes = match response
            .body_mut()
            .with_config()
            .limit(MAX_IN_MEMORY_RESPONSE)
            .read_to_vec()
        {
            Ok(b) => b,
            Err(e) => {
                self.journal
                    .record(request, Outcome::Failed(describe_transport(&e)));
                return Err(NetworkError::ResponseTooLarge {
                    host,
                    limit: MAX_IN_MEMORY_RESPONSE,
                });
            }
        };

        self.journal.record(
            request,
            Outcome::Completed {
                status,
                bytes: bytes.len() as u64,
            },
        );

        Ok(RawResponse {
            status,
            body: bytes,
            location,
            retry_after,
        })
    }

    /// Convenience for a plain GET with no credentials.
    pub fn get(&self, request: &RequestDescriptor) -> Result<Response, NetworkError> {
        self.send(request, None, Method::Get, Body::Empty, &[])
    }

    /// GET whose body is written to `sink` rather than buffered. Returns the
    /// number of bytes written.
    ///
    /// Used for artifacts. The caller is responsible for hashing what it
    /// receives; see [`crate::downloads`].
    ///
    /// # The closure says *keep going*, not *stop*
    ///
    /// This passed `|| false` and therefore cancelled every transfer before it
    /// read a byte. The predicate is `keep_going`, checked as
    /// `if !keep_going() { cancel }` -- so `false` means stop, and an
    /// uncancellable stream has to answer `true`.
    ///
    /// It went unnoticed because nothing reached it. The only other caller is
    /// the self-updater, which refuses to run at all in a build with no signing
    /// key, so the first real transfer through here was a mod download. It
    /// failed with "the transfer was cancelled" and nobody had cancelled
    /// anything.
    ///
    /// The regression test below is `an_uncancellable_stream_is_not_cancelled`.
    pub fn stream(
        &self,
        request: &RequestDescriptor,
        grant: Option<&PerActionGrant>,
        limit: u64,
        sink: &mut dyn std::io::Write,
    ) -> Result<u64, NetworkError> {
        self.stream_cancellable(request, grant, limit, sink, &|| true)
    }

    /// As [`HttpClient::stream`], but checks `keep_going` between chunks so a
    /// long download can be stopped promptly rather than at the next file
    /// boundary.
    pub fn stream_cancellable(
        &self,
        request: &RequestDescriptor,
        grant: Option<&PerActionGrant>,
        limit: u64,
        sink: &mut dyn std::io::Write,
        keep_going: &dyn Fn() -> bool,
    ) -> Result<u64, NetworkError> {
        self.check(request, grant)?;

        // Follow redirects by hand so each hop is re-validated. `descriptor`
        // can only ever name an allowlisted host, so a redirect off the
        // allowlist fails here rather than being fetched.
        let mut descriptor = request.clone();
        let mut response = None;

        for hop in 0..=MAX_REDIRECTS {
            let host = descriptor.destination.host();
            let url = descriptor.url();

            let attempt = match self.agent.get(&url).call() {
                Ok(r) => r,
                Err(e) => {
                    self.journal
                        .record(&descriptor, Outcome::Failed(describe_transport(&e)));
                    return Err(NetworkError::Transport {
                        host,
                        source: Box::new(e),
                    });
                }
            };

            let status = attempt.status().as_u16();
            if !(300..400).contains(&status) {
                response = Some((descriptor.clone(), attempt));
                break;
            }

            if hop == MAX_REDIRECTS {
                self.journal
                    .record(&descriptor, Outcome::Failed("too many redirects".into()));
                return Err(NetworkError::TooManyRedirects { host });
            }

            let location = attempt
                .headers()
                .get("location")
                .and_then(|v| v.to_str().ok())
                .map(str::to_owned)
                .ok_or(NetworkError::MalformedRedirect { host })?;

            let next = resolve_redirect(&descriptor, &location)
                .ok_or(NetworkError::RedirectOffAllowlist { host, location })?;

            tracing::debug!(from = %descriptor.destination, to = %next.destination, "following redirect");
            // The new host gets a fresh policy decision, not a free pass
            // inherited from the first request.
            self.check(&next, grant)?;
            descriptor = next;
        }

        let Some((descriptor, mut response)) = response else {
            return Err(NetworkError::TooManyRedirects {
                host: descriptor.destination.host(),
            });
        };
        let request = &descriptor;
        let host = request.destination.host();

        let status = response.status().as_u16();
        if !(200..300).contains(&status) {
            self.journal
                .record(request, Outcome::Completed { status, bytes: 0 });
            return Err(NetworkError::Status {
                host,
                status,
                body: String::new(),
            });
        }

        // `limit + 1` so that hitting exactly `limit` is fine but exceeding it
        // is detectable rather than silently truncated.
        let mut reader = response.body_mut().as_reader().take(limit + 1);
        let written = match copy_cancellable(&mut reader, sink, keep_going) {
            Ok(n) => n,
            Err(CopyError::Cancelled) => {
                self.journal
                    .record(request, Outcome::Failed("cancelled".into()));
                return Err(NetworkError::Cancelled);
            }
            Err(CopyError::Io(source)) => {
                self.journal
                    .record(request, Outcome::Failed(source.to_string()));
                return Err(NetworkError::Io { host, source });
            }
        };

        if written > limit {
            self.journal
                .record(request, Outcome::Failed("exceeded size limit".into()));
            return Err(NetworkError::ResponseTooLarge { host, limit });
        }

        self.journal.record(
            request,
            Outcome::Completed {
                status,
                bytes: written,
            },
        );
        Ok(written)
    }
}

fn apply_headers<Any>(
    mut builder: ureq::RequestBuilder<Any>,
    headers: &[(&str, &Secret)],
) -> ureq::RequestBuilder<Any> {
    builder = builder.header("Accept", "application/json");
    for (name, value) in headers {
        builder = builder.header(*name, value.expose());
    }
    builder
}

/// ureq errors can embed the URL; the URL can embed a token in a query string
/// for some providers. We never put secrets in query strings, but describing
/// errors by variant rather than by `Display` keeps that guarantee local.
fn describe_transport(e: &ureq::Error) -> String {
    match e {
        ureq::Error::StatusCode(code) => format!("http status {code}"),
        ureq::Error::Timeout(_) => "timed out".to_owned(),
        ureq::Error::HostNotFound => "host not found".to_owned(),
        ureq::Error::ConnectionFailed => "connection failed".to_owned(),
        ureq::Error::TooManyRedirects => "too many redirects".to_owned(),
        ureq::Error::Io(io) => format!("i/o: {}", io.kind()),
        other => format!("transport error: {other}"),
    }
}

fn encode_form(pairs: &[(&str, &str)]) -> String {
    let mut out = String::new();
    for (i, (k, v)) in pairs.iter().enumerate() {
        if i > 0 {
            out.push('&');
        }
        out.push_str(&percent_encode(k));
        out.push('=');
        out.push_str(&percent_encode(v));
    }
    out
}

/// `application/x-www-form-urlencoded` encoding. Unreserved characters per
/// RFC 3986 pass through; everything else is percent-encoded. Space becomes
/// `+` per the form-encoding rules.
fn percent_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char);
            }
            b' ' => out.push('+'),
            _ => {
                use std::fmt::Write as _;
                // Writing to a String is infallible.
                let _ = write!(out, "%{b:02X}");
            }
        }
    }
    out
}

#[cfg(test)]
#[allow(
    clippy::indexing_slicing,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic
)]
mod tests {
    /// The predicate is `keep_going`, so `false` means stop.
    ///
    /// `HttpClient::stream` passed `false` and cancelled every transfer before
    /// it read a byte. This is that bug, isolated to the one function that
    /// decides: no server, no request, just the loop and its predicate.
    #[test]
    fn an_uncancellable_stream_is_not_cancelled() {
        let source = b"the bytes that should arrive".to_vec();
        let mut sink = Vec::new();
        let copied = copy_cancellable(&mut source.as_slice(), &mut sink, &|| true)
            .expect("a stream nobody cancelled must not be cancelled");
        assert_eq!(copied, source.len() as u64);
        assert_eq!(sink, source);
    }

    /// And the other direction still works, or cancellation would be the thing
    /// that broke instead.
    #[test]
    fn a_cancelled_stream_stops_before_it_writes_anything() {
        let source = b"these bytes must not arrive".to_vec();
        let mut sink = Vec::new();
        let outcome = copy_cancellable(&mut source.as_slice(), &mut sink, &|| false);
        assert!(matches!(outcome, Err(CopyError::Cancelled)));
        assert!(sink.is_empty(), "cancelling wrote {} bytes", sink.len());
    }

    use super::*;
    use crate::network::policy::{Destination, Feature, Purpose};

    fn client(policy: NetworkPolicy) -> HttpClient {
        HttpClient::new(policy, NetworkJournal::new(), "test/0.0")
    }

    fn mod_request() -> RequestDescriptor {
        RequestDescriptor::new(
            Destination::ModrinthApi,
            Purpose::ResolveModMetadata,
            Feature::ModBrowse,
            "/v2/project/example",
        )
    }

    #[test]
    fn denied_requests_never_reach_the_transport_and_are_journalled() {
        let c = client(NetworkPolicy::default());
        let req = mod_request();

        let err = c
            .send(&req, None, Method::Get, Body::Empty, &[])
            .unwrap_err();
        assert!(matches!(
            err,
            NetworkError::Denied(PolicyDenial::ModRepositoriesDisabled)
        ));

        let entries = c.journal().recent(10);
        assert_eq!(entries.len(), 1);
        assert!(matches!(entries[0].outcome, Outcome::Denied(_)));
        assert_eq!(entries[0].destination, Destination::ModrinthApi);
        assert_eq!(entries[0].feature, Feature::ModBrowse);
    }

    #[test]
    fn offline_mode_blocks_core_flows_too() {
        let c = client(NetworkPolicy {
            online: false,
            ..NetworkPolicy::default()
        });
        let req = RequestDescriptor::new(
            Destination::PistonMeta,
            Purpose::ResolveGameMetadata,
            Feature::ProfileInstall,
            "/mc/game/version_manifest_v2.json",
        );
        assert!(matches!(
            c.get(&req).unwrap_err(),
            NetworkError::Denied(PolicyDenial::Offline)
        ));
    }

    #[test]
    fn check_reports_without_sending() {
        let c = client(NetworkPolicy::default());
        assert!(c.check(&mod_request(), None).is_err());
        assert_eq!(c.journal().summary().denied, 1);
        assert_eq!(c.journal().summary().completed, 0);
    }

    #[test]
    fn policy_can_be_swapped_at_runtime() {
        let c = client(NetworkPolicy::default());
        assert!(c.check(&mod_request(), None).is_err());
        c.set_policy(NetworkPolicy {
            allow_mod_repositories: true,
            ..NetworkPolicy::default()
        });
        let grant = PerActionGrant::user_requested("test");
        assert!(c.check(&mod_request(), Some(&grant)).is_ok());
    }

    #[test]
    fn form_encoding_escapes_reserved_characters() {
        let encoded = encode_form(&[
            ("client_id", "abc-123"),
            ("scope", "XboxLive.signin offline_access"),
            ("weird", "a&b=c/d"),
        ]);
        assert_eq!(
            encoded,
            "client_id=abc-123&scope=XboxLive.signin+offline_access&weird=a%26b%3Dc%2Fd"
        );
    }

    #[test]
    fn debug_of_client_does_not_include_an_agent_dump() {
        let c = client(NetworkPolicy::default());
        let rendered = format!("{c:?}");
        assert!(rendered.contains("HttpClient"));
        assert!(rendered.contains("policy"));
    }
}
