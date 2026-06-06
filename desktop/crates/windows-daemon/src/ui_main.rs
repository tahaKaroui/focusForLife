use std::fs;
use std::time::Duration;
use eframe::egui;

const STATUS_FILE: &str = r"C:\ProgramData\FocusForLife\status.json";

fn main() {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([260.0, 120.0])
            .with_resizable(false)
            .with_title("FocusForLife"),
        ..Default::default()
    };
    eframe::run_native("FocusForLife", options, Box::new(|_| Box::new(App::default()))).ok();
}

#[derive(Default)]
struct App {
    daily_remaining: u32,
    hourly_remaining: u32,
    state: String,
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _: &mut eframe::Frame) {
        if let Ok(text) = fs::read_to_string(STATUS_FILE) {
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) {
                self.daily_remaining = v["daily_remaining"].as_u64().unwrap_or(0) as u32;
                self.hourly_remaining = v["hourly_remaining"].as_u64().unwrap_or(0) as u32;
                self.state = v["state"].as_str().unwrap_or("unknown").to_string();
            }
        }

        ctx.request_repaint_after(Duration::from_secs(2));

        egui::CentralPanel::default().show(ctx, |ui| {
            ui.heading("FocusForLife");
            ui.separator();
            ui.label(format!(
                "Daily left:  {:02}:{:02}",
                self.daily_remaining / 60,
                self.daily_remaining % 60
            ));
            ui.label(format!(
                "Hourly left: {:02}:{:02}",
                self.hourly_remaining / 60,
                self.hourly_remaining % 60
            ));
            if self.state != "allowed" && !self.state.is_empty() {
                ui.colored_label(egui::Color32::RED, format!("⛔ {}", self.state.replace('_', " ")));
            }
        });
    }
}
