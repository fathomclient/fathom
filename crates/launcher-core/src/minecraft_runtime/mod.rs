//! Everything about the Minecraft process: what version metadata says, what
//! JVM flags we pass, and how the command is assembled and started.
//!
//! Split so that the two security-critical pieces are testable without a
//! network, a JVM, or a game installation:
//!
//! - [`jvm_args`] decides which flags are acceptable,
//! - [`launch`] decides what argv the child process gets,
//! - [`process`] starts it, captures its output, and says what its exit meant.

pub mod jvm_args;
pub mod launch;
pub mod process;
pub mod version;

pub use jvm_args::{
    build_jvm_args, judge_heap, validate_args, ArgRejection, HeapVerdict, ValidationReport,
};
pub use launch::{build_plan, Argument, LaunchContext, LaunchError, LaunchPlan, LaunchSpec};
pub use process::{spawn, GameProcess, Outcome, ProcessError, RunState};
pub use version::{
    AssetIndex, AssetObject, Download, Library, Os, OsFamily, VersionDetail, VersionManifest,
};
