//! Node agent. Registers its GPUs, heartbeats, and reconciles local
//! processes with the assignment list in every heartbeat reply: starts
//! missing job attempts (with CUDA_VISIBLE_DEVICES and NEBULA_* set) and
//! kills processes that are no longer assigned.
//!
//! GPUs are simulated: the agent reports `--gpus` devices and a GPU failure
//! is injected by creating `<work-dir>/fail-gpu-<index>`.
//!
//! nebula-agent --server URL --token T --id NODE [--gpus 8] [--islands 2]
//!              [--model h100] [--work-dir DIR] [--interval-ms 500]

use nebula::server::{AttemptRef, Finished, GpuInfo, HeartbeatRequest, HeartbeatResponse, RegisterRequest};
use std::collections::{BTreeSet, HashMap};
use std::path::PathBuf;
use std::process::Stdio;
use std::time::{Duration, Instant};
use tokio::process::{Child, Command};

struct Args {
    server: String,
    token: String,
    id: String,
    gpus: u32,
    islands: u32,
    model: String,
    work_dir: PathBuf,
    interval: Duration,
}

fn parse() -> Args {
    let mut a = Args {
        server: "http://127.0.0.1:7070".into(),
        token: String::new(),
        id: String::new(),
        gpus: 8,
        islands: 2,
        model: "h100".into(),
        work_dir: PathBuf::from("agent-work"),
        interval: Duration::from_millis(500),
    };
    let v: Vec<String> = std::env::args().skip(1).collect();
    for p in v.chunks(2) {
        let val = p.get(1).cloned().unwrap_or_else(|| panic!("missing value for {}", p[0]));
        match p[0].as_str() {
            "--server" => a.server = val,
            "--token" => a.token = val,
            "--id" => a.id = val,
            "--gpus" => a.gpus = val.parse().unwrap(),
            "--islands" => a.islands = val.parse().unwrap(),
            "--model" => a.model = val,
            "--work-dir" => a.work_dir = val.into(),
            "--interval-ms" => a.interval = Duration::from_millis(val.parse().unwrap()),
            x => panic!("unknown argument {x}"),
        }
    }
    assert!(!a.id.is_empty(), "--id is required");
    a
}

struct Proc {
    child: Child,
    pid: u32,
    /// set when we asked it to stop; escalated to SIGKILL after a grace period
    stopping: Option<Instant>,
}

fn signal_group(pid: u32, sig: &str) {
    // each job runs in its own process group so its children die with it
    let _ = std::process::Command::new("kill")
        .arg(format!("-{sig}"))
        .arg("--")
        .arg(format!("-{pid}"))
        .stderr(Stdio::null())
        .status();
}

struct Agent {
    a: Args,
    http: reqwest::Client,
    procs: HashMap<AttemptRef, Proc>,
    /// attempts that exited here; never restarted even if still assigned
    /// (a non-rank-0 member of a gang that finished before rank 0)
    done: BTreeSet<AttemptRef>,
    pending_finished: Vec<Finished>,
    pending_failed: Vec<u32>,
}

impl Agent {
    async fn register(&mut self) {
        let per = (self.a.gpus / self.a.islands.max(1)).max(1);
        let req = RegisterRequest {
            id: self.a.id.clone(),
            gpus: (0..self.a.gpus)
                .map(|i| GpuInfo {
                    index: i,
                    model: self.a.model.clone(),
                    island: i / per,
                })
                .collect(),
        };
        loop {
            let r = self
                .http
                .post(format!("{}/v1/nodes/register", self.a.server))
                .bearer_auth(&self.a.token)
                .json(&req)
                .send()
                .await;
            match r {
                Ok(r) if r.status().is_success() => {
                    tracing::info!("registered as {}", self.a.id);
                    // the control plane requeued whatever ran here before
                    self.stop_all().await;
                    self.done.clear();
                    self.pending_finished.clear();
                    return;
                }
                Ok(r) => tracing::warn!("register: HTTP {}", r.status()),
                Err(e) => tracing::warn!("register: {e}"),
            }
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
    }

    async fn stop_all(&mut self) {
        for p in self.procs.values_mut() {
            signal_group(p.pid, "KILL");
            let _ = p.child.wait().await;
        }
        self.procs.clear();
    }

    fn reap(&mut self) {
        let mut exited = vec![];
        for (k, p) in self.procs.iter_mut() {
            if let Ok(Some(st)) = p.child.try_wait() {
                exited.push((k.clone(), st, p.stopping.is_some()));
            } else if let Some(t) = p.stopping
                && t.elapsed() > Duration::from_secs(3)
            {
                signal_group(p.pid, "KILL");
            }
        }
        for (k, st, stopped) in exited {
            self.procs.remove(&k);
            if stopped {
                continue; // we killed it because it is no longer assigned
            }
            let code = st.code().unwrap_or(128 + 9);
            tracing::info!(job = k.job, attempt = k.attempt, code, "exited");
            self.done.insert(k.clone());
            self.pending_finished.push(Finished {
                job: k.job,
                attempt: k.attempt,
                exit_code: code,
                reason: String::new(),
            });
        }
        if let Ok(rd) = std::fs::read_dir(&self.a.work_dir) {
            for e in rd.flatten() {
                let name = e.file_name().to_string_lossy().to_string();
                if let Some(g) = name.strip_prefix("fail-gpu-").and_then(|x| x.parse::<u32>().ok()) {
                    tracing::warn!(gpu = g, "injected GPU failure");
                    self.pending_failed.push(g);
                    let _ = std::fs::remove_file(e.path());
                }
            }
        }
    }

    fn reconcile(&mut self, resp: HeartbeatResponse) {
        let want: HashMap<AttemptRef, _> = resp
            .assignments
            .into_iter()
            .map(|a| {
                (
                    AttemptRef {
                        job: a.job,
                        attempt: a.attempt,
                    },
                    a,
                )
            })
            .collect();
        for (k, p) in self.procs.iter_mut() {
            if !want.contains_key(k) && p.stopping.is_none() {
                tracing::info!(job = k.job, attempt = k.attempt, "no longer assigned; stopping");
                signal_group(p.pid, "TERM");
                p.stopping = Some(Instant::now());
            }
        }
        self.done.retain(|k| want.contains_key(k));
        for (k, a) in want {
            if self.procs.contains_key(&k) || self.done.contains(&k) {
                continue;
            }
            let log = self.a.work_dir.join(format!("job-{}-a{}.log", k.job, k.attempt));
            let spawned = (|| {
                let out = std::fs::File::create(&log)?;
                let err = out.try_clone()?;
                let mut cmd = Command::new(a.command.first().ok_or(std::io::Error::other("empty command"))?);
                cmd.args(&a.command[1..])
                    .envs(&a.env)
                    .stdin(Stdio::null())
                    .stdout(out)
                    .stderr(err)
                    .process_group(0);
                cmd.spawn()
            })();
            match spawned {
                Ok(child) => {
                    let pid = child.id().unwrap_or(0);
                    tracing::info!(job = k.job, attempt = k.attempt, gpus = ?a.gpus, pid, "started");
                    self.procs.insert(
                        k,
                        Proc {
                            child,
                            pid,
                            stopping: None,
                        },
                    );
                }
                Err(e) => {
                    tracing::warn!(job = k.job, "spawn failed: {e}");
                    self.done.insert(k.clone());
                    self.pending_finished.push(Finished {
                        job: k.job,
                        attempt: k.attempt,
                        exit_code: 127,
                        reason: format!("spawn: {e}"),
                    });
                }
            }
        }
    }

    async fn heartbeat(&mut self) -> Result<Option<HeartbeatResponse>, String> {
        let req = HeartbeatRequest {
            running: self.procs.keys().cloned().collect(),
            finished: self.pending_finished.clone(),
            failed_gpus: self.pending_failed.clone(),
        };
        let r = self
            .http
            .post(format!("{}/v1/nodes/{}/heartbeat", self.a.server, self.a.id))
            .bearer_auth(&self.a.token)
            .json(&req)
            .send()
            .await
            .map_err(|e| e.to_string())?;
        if r.status() == reqwest::StatusCode::NOT_FOUND {
            return Ok(None);
        }
        if !r.status().is_success() {
            return Err(format!("HTTP {}", r.status()));
        }
        let resp: HeartbeatResponse = r.json().await.map_err(|e| e.to_string())?;
        // delivered: the control plane has these now
        self.pending_finished.clear();
        self.pending_failed.clear();
        Ok(Some(resp))
    }
}

#[tokio::main(flavor = "current_thread")]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .init();
    let a = parse();
    std::fs::create_dir_all(&a.work_dir).expect("work dir");
    let http = reqwest::Client::builder().timeout(Duration::from_secs(5)).build().unwrap();
    let mut ag = Agent {
        a,
        http,
        procs: HashMap::new(),
        done: BTreeSet::new(),
        pending_finished: vec![],
        pending_failed: vec![],
    };
    ag.register().await;
    let mut iv = tokio::time::interval(ag.a.interval);
    let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()).unwrap();
    let mut int = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::interrupt()).unwrap();
    loop {
        tokio::select! {
            _ = iv.tick() => {}
            _ = term.recv() => break,
            _ = int.recv() => break,
        }
        ag.reap();
        match ag.heartbeat().await {
            Ok(Some(resp)) => ag.reconcile(resp),
            Ok(None) => ag.register().await,
            Err(e) => tracing::warn!("heartbeat failed: {e}; jobs keep running"),
        }
    }
    tracing::info!("shutting down: stopping jobs and leaving the cluster");
    ag.stop_all().await;
    let _ = ag
        .http
        .delete(format!("{}/v1/nodes/{}", ag.a.server, ag.a.id))
        .bearer_auth(&ag.a.token)
        .send()
        .await;
}
