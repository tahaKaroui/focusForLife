use std::io::{self, Write};
use std::time::{Duration, Instant};

use anyhow::Result;
use eframe::egui;
use ffl_shared::ipc::{DaemonEvent, FocusState, PromptRequest, PromptResponse, StatusSnapshot, UiEvent};

mod daemon_conn;
use daemon_conn::DaemonConn;

const SHIELD_PNG: &[u8] = include_bytes!("../assets/shield.png");
const URBANIST_TTF: &[u8] = include_bytes!("../assets/Urbanist.ttf");

// Brand palette (matches the Android app and the shield logo).
const BG: egui::Color32 = egui::Color32::from_rgb(8, 29, 36);
const SURFACE: egui::Color32 = egui::Color32::from_rgb(14, 42, 51);
const RAISED: egui::Color32 = egui::Color32::from_rgb(21, 58, 70);
const OUTLINE: egui::Color32 = egui::Color32::from_rgb(44, 82, 93);
const CREAM: egui::Color32 = egui::Color32::from_rgb(246, 241, 231);
const MUTED: egui::Color32 = egui::Color32::from_rgb(159, 184, 190);
const ORANGE: egui::Color32 = egui::Color32::from_rgb(242, 163, 60);
const TEAL: egui::Color32 = egui::Color32::from_rgb(85, 163, 181);
const GREEN: egui::Color32 = egui::Color32::from_rgb(83, 184, 132);
const AMBER: egui::Color32 = egui::Color32::from_rgb(242, 179, 60);
const RED: egui::Color32 = egui::Color32::from_rgb(228, 88, 76);

fn main() -> Result<()> {
    // UI entrypoint (overlay + prompts).
    let args: Vec<String> = std::env::args().collect();
    if args.iter().any(|a| a == "--prompt-test") {
        run_prompt_test()?;
        return Ok(());
    }

    let mut viewport = egui::ViewportBuilder::default()
        .with_inner_size([380.0, 520.0])
        .with_min_inner_size([330.0, 420.0])
        .with_title("FocusForLife");
    if let Some(icon) = load_window_icon() {
        viewport = viewport.with_icon(std::sync::Arc::new(icon));
    }
    let options = eframe::NativeOptions {
        viewport,
        ..Default::default()
    };

    if let Err(err) = eframe::run_native(
        "FocusForLife",
        options,
        Box::new(|cc| {
            apply_brand_style(&cc.egui_ctx);
            Box::new(FflApp::new())
        }),
    ) {
        eprintln!("ffl-ui failed to start: {err}");
    }
    Ok(())
}

fn load_window_icon() -> Option<egui::IconData> {
    let img = image::load_from_memory(SHIELD_PNG).ok()?.into_rgba8();
    let (width, height) = img.dimensions();
    Some(egui::IconData {
        rgba: img.into_raw(),
        width,
        height,
    })
}


const SHIELD_TEAL: egui::Color32 = egui::Color32::from_rgb(24, 88, 107);

fn cubic_to(p0: (f32, f32), p1: (f32, f32), p2: (f32, f32), p3: (f32, f32), out: &mut Vec<(f32, f32)>) {
    for i in 1..=12 {
        let t = i as f32 / 12.0;
        let u = 1.0 - t;
        out.push((
            u * u * u * p0.0 + 3.0 * u * u * t * p1.0 + 3.0 * u * t * t * p2.0 + t * t * t * p3.0,
            u * u * u * p0.1 + 3.0 * u * u * t * p1.1 + 3.0 * u * t * t * p2.1 + t * t * t * p3.1,
        ));
    }
}

/// Same geometry as the Android vector drawable (24x24 unit space).
fn shield_outline(scale: f32) -> Vec<(f32, f32)> {
    let mut pts = vec![(12.0, 1.6), (20.4, 4.7), (20.4, 11.0)];
    cubic_to((20.4, 11.0), (20.4, 16.7), (17.0, 20.8), (12.0, 22.5), &mut pts);
    cubic_to((12.0, 22.5), (7.0, 20.8), (3.6, 16.7), (3.6, 11.0), &mut pts);
    pts.push((3.6, 4.7));
    pts.into_iter()
        .map(|(x, y)| (12.0 + (x - 12.0) * scale, 12.05 + (y - 12.05) * scale))
        .collect()
}

/// Crisp vector logo, drawn directly so it stays sharp at any size or DPI.
fn draw_logo(ui: &mut egui::Ui, size: f32) {
    let (rect, _) = ui.allocate_exact_size(egui::vec2(size, size), egui::Sense::hover());
    let unit = size / 24.0;
    let to = move |pt: (f32, f32)| egui::pos2(rect.left() + pt.0 * unit, rect.top() + pt.1 * unit);
    let painter = ui.painter();
    painter.add(egui::Shape::convex_polygon(
        shield_outline(1.0).into_iter().map(to).collect(),
        SHIELD_TEAL,
        egui::Stroke::NONE,
    ));
    painter.add(egui::Shape::convex_polygon(
        shield_outline(0.78).into_iter().map(to).collect(),
        ORANGE,
        egui::Stroke::NONE,
    ));
    painter.add(egui::Shape::line(
        vec![to((7.17, 14.76)), to((10.65, 11.27)), to((12.73, 13.35)), to((15.3, 10.8))],
        egui::Stroke::new(1.05 * unit, egui::Color32::WHITE),
    ));
    painter.add(egui::Shape::convex_polygon(
        vec![to((14.08, 8.88)), to((17.2, 8.88)), to((17.2, 12.0))],
        egui::Color32::WHITE,
        egui::Stroke::NONE,
    ));
}

fn apply_brand_style(ctx: &egui::Context) {
    let mut fonts = egui::FontDefinitions::default();
    fonts.font_data.insert(
        "urbanist".to_owned(),
        egui::FontData::from_static(URBANIST_TTF),
    );
    fonts
        .families
        .entry(egui::FontFamily::Proportional)
        .or_default()
        .insert(0, "urbanist".to_owned());
    ctx.set_fonts(fonts);

    let mut style = (*ctx.style()).clone();
    style.visuals = egui::Visuals::dark();
    style.visuals.override_text_color = Some(CREAM);
    style.visuals.panel_fill = BG;
    style.visuals.window_fill = SURFACE;
    style.visuals.window_stroke = egui::Stroke::new(1.0, OUTLINE);
    style.visuals.widgets.noninteractive.bg_fill = SURFACE;
    style.visuals.widgets.noninteractive.bg_stroke = egui::Stroke::new(1.0, OUTLINE);
    style.visuals.widgets.inactive.bg_fill = RAISED;
    style.visuals.widgets.hovered.bg_fill = OUTLINE;
    style.visuals.widgets.active.bg_fill = OUTLINE;
    style.visuals.selection.bg_fill = ORANGE.linear_multiply(0.4);
    style.spacing.item_spacing = egui::vec2(8.0, 8.0);
    style.spacing.button_padding = egui::vec2(14.0, 7.0);
    ctx.set_style(style);
}

fn run_prompt_test() -> Result<()> {
    println!("Done with work? [y/N]");
    print!("> ");
    io::stdout().flush()?;
    let mut input = String::new();
    io::stdin().read_line(&mut input)?;
    let accepted = matches!(input.trim().to_lowercase().as_str(), "y" | "yes");

    let response = UiEvent::PromptResponse(PromptResponse {
        id: "free_time_21_00".to_string(),
        accepted,
    });
    let json = serde_json::to_string_pretty(&response)?;
    println!("{json}");
    Ok(())
}

struct FflApp {
    conn: Option<DaemonConn>,
    status: Option<StatusSnapshot>,
    pending_prompt: Option<PromptRequest>,
    retry_at: Instant,
}

impl FflApp {
    fn new() -> Self {
        let conn = DaemonConn::connect(std::path::Path::new("/run/focusforlife/daemon.sock")).ok();
        Self {
            conn,
            status: None,
            pending_prompt: None,
            retry_at: Instant::now(),
        }
    }

    fn send_prompt_response(&mut self, id: &str, accepted: bool) {
        if let Some(ref mut conn) = self.conn {
            conn.send_response(PromptResponse {
                id: id.to_string(),
                accepted,
            })
            .ok();
        }
    }

}

fn fmt_duration(total_seconds: u32) -> String {
    let hrs = total_seconds / 3600;
    let mins = (total_seconds % 3600) / 60;
    let secs = total_seconds % 60;
    if hrs > 0 {
        format!("{hrs}h {mins:02}m")
    } else if mins > 0 {
        format!("{mins}m {secs:02}s")
    } else {
        format!("{secs}s")
    }
}

struct StateBadge {
    label: &'static str,
    detail: String,
    color: egui::Color32,
}

fn state_badge(snap: &StatusSnapshot) -> StateBadge {
    match snap.state {
        FocusState::Allowed => StateBadge {
            label: "WITHIN SAFE WINDOW",
            detail: "Distracting sites are available. Spend wisely.".to_string(),
            color: GREEN,
        },
        FocusState::BlockedHardWindow => StateBadge {
            label: "HIBERNATE WINDOW",
            detail: match (&snap.hard_block_start, &snap.hard_block_end) {
                (Some(s), Some(e)) => format!("Hard lockdown runs {s} - {e}."),
                _ => "Hard lockdown is active.".to_string(),
            },
            color: RED,
        },
        FocusState::BlockedCooldown => StateBadge {
            label: "HOURLY COOLDOWN",
            detail: format!(
                "Hourly limit used. Unlocks in {}.",
                fmt_duration(snap.cooldown_remaining_seconds)
            ),
            color: AMBER,
        },
        FocusState::BlockedQuota => StateBadge {
            label: "DAILY QUOTA EXHAUSTED",
            detail: "The shared daily allowance is gone. See you tomorrow.".to_string(),
            color: AMBER,
        },
    }
}

fn quota_bar(
    ui: &mut egui::Ui,
    label: &str,
    remaining: u32,
    total: u32,
    fill: egui::Color32,
) {
    let frac = if total == 0 {
        0.0
    } else {
        (remaining as f32 / total as f32).clamp(0.0, 1.0)
    };
    ui.horizontal(|ui| {
        ui.label(egui::RichText::new(label).color(MUTED).size(13.0));
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            ui.label(
                egui::RichText::new(format!(
                    "{} left of {}",
                    fmt_duration(remaining),
                    fmt_duration(total)
                ))
                .color(CREAM)
                .strong()
                .size(13.0),
            );
        });
    });
    let bar = egui::ProgressBar::new(frac)
        .fill(fill)
        .desired_width(ui.available_width());
    ui.add(bar);
}


fn dot(ui: &mut egui::Ui, color: egui::Color32, radius: f32) {
    let (rect, _) = ui.allocate_exact_size(
        egui::vec2(radius * 2.0, radius * 2.0),
        egui::Sense::hover(),
    );
    ui.painter().circle_filled(rect.center(), radius, color);
}

fn section_frame() -> egui::Frame {
    egui::Frame::none()
        .fill(SURFACE)
        .stroke(egui::Stroke::new(1.0, OUTLINE))
        .rounding(egui::Rounding::same(12.0))
        .inner_margin(egui::Margin::same(14.0))
}

impl eframe::App for FflApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        // Retry connecting if disconnected.
        if self.conn.is_none() && self.retry_at.elapsed() > Duration::from_secs(3) {
            self.conn =
                DaemonConn::connect(std::path::Path::new("/run/focusforlife/daemon.sock")).ok();
            self.retry_at = Instant::now();
        }

        // Drain incoming events; detect dead connections so we reconnect.
        if self.conn.is_some() {
            loop {
                match self.conn.as_ref().unwrap().try_recv() {
                    Ok(Some(DaemonEvent::Status(snap))) => self.status = Some(snap),
                    Ok(Some(DaemonEvent::Prompt(req))) => self.pending_prompt = Some(req),
                    Ok(None) => break,
                    Err(()) => {
                        self.conn = None;
                        break;
                    }
                }
            }
        }

        // Keep UI refreshing even without input events.
        ctx.request_repaint_after(Duration::from_secs(1));


        egui::CentralPanel::default().show(ctx, |ui| {
            ui.add_space(4.0);

            // ---- Brand header ----
            ui.horizontal(|ui| {
                draw_logo(ui, 44.0);
                ui.add_space(4.0);
                ui.vertical(|ui| {
                    ui.horizontal(|ui| {
                        ui.spacing_mut().item_spacing.x = 0.0;
                        ui.label(egui::RichText::new("Focus").size(24.0).strong().color(CREAM));
                        ui.label(egui::RichText::new("For").size(24.0).strong().color(ORANGE));
                        ui.label(egui::RichText::new("Life").size(24.0).strong().color(CREAM));
                    });
                    ui.label(egui::RichText::new("Guard your attention").color(MUTED).size(13.0));
                });
            });
            ui.add_space(8.0);

            match self.status.clone() {
                None => {
                    section_frame().show(ui, |ui| {
                        ui.set_width(ui.available_width());
                        ui.horizontal(|ui| {
                            ui.spinner();
                            ui.label(
                                egui::RichText::new("Connecting to the FocusForLife daemon…")
                                    .color(MUTED),
                            );
                        });
                        ui.label(
                            egui::RichText::new(
                                "Make sure ffl-daemon.service is running:\n  systemctl status ffl-daemon",
                            )
                            .color(MUTED)
                            .size(12.0),
                        );
                    });
                }
                Some(snap) => {
                    let badge = state_badge(&snap);

                    // ---- Status banner ----
                    egui::Frame::none()
                        .fill(badge.color.linear_multiply(0.16))
                        .stroke(egui::Stroke::new(1.0, badge.color))
                        .rounding(egui::Rounding::same(12.0))
                        .inner_margin(egui::Margin::same(14.0))
                        .show(ui, |ui| {
                            ui.set_width(ui.available_width());
                            ui.horizontal(|ui| {
                                dot(ui, badge.color, 5.0);
                                ui.label(
                                    egui::RichText::new(badge.label)
                                        .color(badge.color)
                                        .strong()
                                        .size(15.0),
                                );
                            });
                            ui.label(egui::RichText::new(&badge.detail).color(CREAM).size(13.0));
                        });
                    ui.add_space(8.0);

                    // ---- Time left hero ----
                    let daily_rem = snap.daily_quota_seconds.saturating_sub(snap.daily_used_seconds);
                    let hourly_rem = snap.hourly_limit_seconds.saturating_sub(snap.hourly_used_seconds);

                    section_frame().show(ui, |ui| {
                        ui.set_width(ui.available_width());
                        ui.label(
                            egui::RichText::new(fmt_duration(daily_rem))
                                .size(36.0)
                                .strong()
                                .color(CREAM),
                        );
                        ui.label(egui::RichText::new("daily time left").color(MUTED).size(13.0));
                        ui.add_space(8.0);
                        quota_bar(ui, "Today", daily_rem, snap.daily_quota_seconds, ORANGE);
                        ui.add_space(4.0);
                        quota_bar(ui, "This hour", hourly_rem, snap.hourly_limit_seconds, TEAL);

                        if snap.cooldown_remaining_seconds > 0 {
                            ui.add_space(6.0);
                            ui.label(
                                egui::RichText::new(format!(
                                    "⏳ Cooldown: unlocks in {}",
                                    fmt_duration(snap.cooldown_remaining_seconds)
                                ))
                                .color(AMBER)
                                .size(13.0),
                            );
                        }
                    });
                    ui.add_space(8.0);

                    // ---- Schedule info ----
                    section_frame().show(ui, |ui| {
                        ui.set_width(ui.available_width());
                        ui.label(egui::RichText::new("Schedule").strong().size(14.0));
                        if let (Some(s), Some(e)) = (&snap.hard_block_start, &snap.hard_block_end) {
                            ui.label(
                                egui::RichText::new(format!("🌙 Hard block {s} - {e}"))
                                    .color(CREAM)
                                    .size(13.0),
                            );
                        }
                        ui.label(
                            egui::RichText::new(format!(
                                "⏱ Hourly limit {} · Daily quota {}",
                                fmt_duration(snap.hourly_limit_seconds),
                                fmt_duration(snap.daily_quota_seconds)
                            ))
                            .color(MUTED)
                            .size(13.0),
                        );
                        ui.label(
                            egui::RichText::new("Quotas are shared across all your devices.")
                                .color(MUTED)
                                .size(12.0),
                        );
                    });
                }
            }

            // ---- Footer: connection state ----
            ui.with_layout(egui::Layout::bottom_up(egui::Align::Center), |ui| {
                ui.add_space(6.0);
                let (dot_color, text) = if self.conn.is_some() {
                    (GREEN, "Daemon connected")
                } else {
                    (RED, "Daemon offline, reconnecting…")
                };
                ui.horizontal(|ui| {
                    let total = ui.available_width();
                    let approx = 150.0;
                    ui.add_space((total - approx).max(0.0) / 2.0);
                    dot(ui, dot_color, 4.0);
                    ui.label(egui::RichText::new(text).color(MUTED).size(12.0));
                });
            });
        });

        // Prompt dialog; appears centered over main window.
        if let Some(prompt) = self.pending_prompt.clone() {
            egui::Window::new(
                egui::RichText::new(&prompt.title).strong().color(CREAM),
            )
            .collapsible(false)
            .resizable(false)
            .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
            .show(ctx, |ui| {
                ui.label(&prompt.message);
                ui.add_space(10.0);
                ui.horizontal(|ui| {
                    let yes = egui::Button::new(
                        egui::RichText::new("Yes").color(BG).strong(),
                    )
                    .fill(ORANGE)
                    .rounding(egui::Rounding::same(8.0));
                    if ui.add(yes).clicked() {
                        self.send_prompt_response(&prompt.id, true);
                        self.pending_prompt = None;
                    }
                    let no = egui::Button::new("No").rounding(egui::Rounding::same(8.0));
                    if ui.add(no).clicked() {
                        self.send_prompt_response(&prompt.id, false);
                        self.pending_prompt = None;
                    }
                });
            });
        }
    }
}
