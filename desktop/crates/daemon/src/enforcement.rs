use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};

use anyhow::Result;

pub struct Enforcement {
    blocklist_path: String,
    cdp_ports: Vec<u16>,
}

impl Enforcement {
    pub fn new(blocklist_path: String, cdp_ports: Vec<u16>) -> Self {
        Self {
            blocklist_path,
            cdp_ports,
        }
    }

    /// Full block: poison DNS then close blocked browser tabs via CDP.
    pub fn block_all(&self, domains: &[String]) -> Result<()> {
        self.apply_blocklist(domains)?;
        self.reload_unbound()?;
        let closed = close_blocked_tabs(&self.cdp_ports, domains);
        if closed > 0 {
            println!("enforcement: closed {closed} blocked tab(s) via CDP");
        }
        Ok(())
    }

    /// Close blocked tabs only, without touching DNS. Safe (and intended) to call
    /// every tick while blocked: catches tabs the user reopens from the browser's
    /// internal DNS/connection cache after the one-shot block_all already ran.
    pub fn close_tabs(&self, domains: &[String]) -> usize {
        close_blocked_tabs(&self.cdp_ports, domains)
    }

    /// Full unblock: clear DNS blocklist, flush cache.
    pub fn unblock_all(&self) -> Result<()> {
        self.apply_blocklist(&[])?;
        self.restart_unbound()?;
        Ok(())
    }

    pub fn apply_blocklist(&self, domains: &[String]) -> Result<()> {
        let blocklist_path = Path::new(&self.blocklist_path);
        write_blocklist_file(blocklist_path, domains)?;
        Ok(())
    }

    pub fn reload_unbound(&self) -> Result<()> {
        let status = std::process::Command::new("systemctl")
            .args(["kill", "--signal=HUP", "ffl-resolver"])
            .status()?;
        if !status.success() {
            anyhow::bail!("systemctl kill --signal=HUP ffl-resolver failed");
        }
        Ok(())
    }

    pub fn restart_unbound(&self) -> Result<()> {
        let status = std::process::Command::new("systemctl")
            .args(["restart", "ffl-resolver"])
            .status()?;
        if !status.success() {
            anyhow::bail!("systemctl restart ffl-resolver failed");
        }
        Ok(())
    }

    pub fn write_unbound_config(
        &self,
        config_path: &Path,
        include_path: Option<&str>,
        resolver_port: u16,
    ) -> Result<()> {
        let blocklist_path = Path::new(&self.blocklist_path);
        let include_target = include_path
            .map(str::to_string)
            .unwrap_or_else(|| blocklist_path.display().to_string());
        let contents = format!(
            "# Managed by FocusForLife daemon\n\
server:\n\
  interface: 127.0.0.1\n\
  port: {resolver_port}\n\
  access-control: 127.0.0.0/8 allow\n\
  do-ip4: yes\n\
  do-ip6: no\n\
  include: \"{include_target}\"\n\
\n\
# Forward all non-blocked queries to upstream DNS (port 53, not DoH).\n\
forward-zone:\n\
  name: \".\"\n\
  forward-addr: 8.8.8.8\n\
  forward-addr: 8.8.4.4\n"
        );
        atomic_write(config_path, &contents)?;
        Ok(())
    }

    pub fn write_nft_rules(&self, rules_path: &Path, resolver_port: u16) -> Result<()> {
        let contents = format!(
            "# Managed by FocusForLife daemon\n\
table inet focusforlife_dns {{\n\
  chain output_nat {{\n\
    type nat hook output priority dstnat; policy accept;\n\
    # Redirect DNS to local resolver, but not the upstream forwarders unbound uses.\n\
    ip daddr != {{ 8.8.8.8, 8.8.4.4 }} udp dport 53 redirect to :{resolver_port}\n\
    ip daddr != {{ 8.8.8.8, 8.8.4.4 }} tcp dport 53 redirect to :{resolver_port}\n\
  }}\n\
\n\
  chain output_filter {{\n\
    type filter hook output priority filter; policy accept;\n\
    # Block DNS-over-TLS (port 853)\n\
    udp dport 853 reject\n\
    tcp dport 853 reject\n\
    # Block DNS-over-HTTPS to known DoH providers (Cloudflare, Google, Quad9)\n\
    ip daddr {{ 1.1.1.1, 1.0.0.1, 8.8.8.8, 8.8.4.4, 9.9.9.9 }} tcp dport 443 reject\n\
    ip daddr {{ 1.1.1.1, 1.0.0.1, 8.8.8.8, 8.8.4.4, 9.9.9.9 }} udp dport 443 reject\n\
  }}\n\
}}\n"
        );
        atomic_write(rules_path, &contents)?;
        Ok(())
    }

    pub fn write_dns_test_assets(
        &self,
        output_dir: &Path,
        domains: &[String],
        include_path: Option<&str>,
        resolver_port: u16,
    ) -> Result<DnsTestAssets> {
        fs::create_dir_all(output_dir)?;

        let blocklist_path = output_dir.join("focusforlife-blocklist.conf");
        let unbound_config_path = output_dir.join("unbound.conf");
        let nft_rules_path = output_dir.join("ffl-dns.nft");

        write_blocklist_file(&blocklist_path, domains)?;

        let runtime = Self {
            blocklist_path: blocklist_path.display().to_string(),
            cdp_ports: vec![],
        };
        runtime.write_unbound_config(&unbound_config_path, include_path, resolver_port)?;
        runtime.write_nft_rules(&nft_rules_path, resolver_port)?;

        Ok(DnsTestAssets {
            output_dir: output_dir.to_path_buf(),
            blocklist_path,
            unbound_config_path,
            nft_rules_path,
            resolver_port,
        })
    }
}

fn normalize_domain(input: &str) -> Option<String> {
    let mut s = input.trim().to_lowercase();
    if s.is_empty() || s.contains(char::is_whitespace) {
        return None;
    }
    if s.ends_with('.') {
        s.pop();
    }
    Some(s)
}

pub struct DnsTestAssets {
    pub output_dir: PathBuf,
    pub blocklist_path: PathBuf,
    pub unbound_config_path: PathBuf,
    pub nft_rules_path: PathBuf,
    pub resolver_port: u16,
}

fn write_blocklist_file(blocklist_path: &Path, domains: &[String]) -> Result<()> {
    let mut contents = String::from("# Managed by FocusForLife daemon\n");
    for domain in domains {
        if let Some(cleaned) = normalize_domain(domain) {
            // always_null returns 0.0.0.0 / :: instead of NXDOMAIN.
            // Chromium/Brave treats NXDOMAIN as a resolver failure and retries
            // via DNS-over-HTTPS, bypassing the sinkhole. A null-address answer
            // is a valid response so DoH fallback is never triggered.
            contents.push_str(&format!("local-zone: \"{}\" always_null\n", cleaned));
            contents.push_str(&format!("local-zone: \"www.{}\" always_null\n", cleaned));
        }
    }
    atomic_write(blocklist_path, &contents)
}

/// Close browser tabs whose URL matches a blocked domain via CDP.
/// This kills active streams (YouTube video, WebSocket games) immediately
/// without the collateral damage of IP-based firewall blocking.
fn close_blocked_tabs(ports: &[u16], domains: &[String]) -> usize {
    let mut closed = 0;
    for &port in ports {
        let url = format!("http://localhost:{port}/json");
        let body = match ureq::get(&url)
            .timeout(std::time::Duration::from_millis(300))
            .call()
        {
            Ok(resp) => match resp.into_string() {
                Ok(b) => b,
                Err(_) => continue,
            },
            Err(_) => continue,
        };
        let targets: Vec<serde_json::Value> = match serde_json::from_str(&body) {
            Ok(t) => t,
            Err(_) => continue,
        };
        for target in &targets {
            if target.get("type").and_then(|t| t.as_str()) != Some("page") {
                continue;
            }
            let tab_url = match target.get("url").and_then(|u| u.as_str()) {
                Some(u) => u,
                None => continue,
            };
            let tab_domain = match url::Url::parse(tab_url)
                .ok()
                .and_then(|u| u.host_str().map(|h| h.to_lowercase()))
            {
                Some(d) => d,
                None => continue,
            };
            let is_blocked = domains.iter().any(|blocked| {
                tab_domain == *blocked
                    || tab_domain == format!("www.{blocked}")
                    || tab_domain.ends_with(&format!(".{blocked}"))
            });
            if !is_blocked {
                continue;
            }
            // Close via CDP: GET /json/close/{targetId}
            if let Some(id) = target.get("id").and_then(|i| i.as_str()) {
                let close_url = format!("http://localhost:{port}/json/close/{id}");
                if ureq::get(&close_url)
                    .timeout(std::time::Duration::from_millis(300))
                    .call()
                    .is_ok()
                {
                    println!("enforcement: closed tab {} ({})", tab_domain, tab_url);
                    closed += 1;
                }
            }
        }
    }
    closed
}

fn atomic_write(path: &Path, contents: &str) -> Result<()> {
    let tmp_path = path.with_extension("tmp");
    let mut file = File::create(&tmp_path)?;
    file.write_all(contents.as_bytes())?;
    file.flush()?;
    fs::rename(tmp_path, path)?;
    Ok(())
}
