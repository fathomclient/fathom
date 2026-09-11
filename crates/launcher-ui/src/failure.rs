//! The window shown when the launcher cannot start.
//!
//! # Why this exists at all
//!
//! `App` needs a `Launcher`, and a `Launcher` needs a data directory. When that
//! fails there is nothing to build an application around — and the previous
//! behaviour was `eprintln!` followed by `exit(1)`.
//!
//! In a release build that is **silent**. `windows_subsystem = "windows"` means
//! there is no console, so the message goes nowhere and the user double-clicks
//! an icon and watches nothing happen. That is the worst failure mode a desktop
//! application has: indistinguishable from a corrupt download, a blocked
//! antivirus, or a broken machine.
//!
//! So a launcher that cannot start still opens a window and says why. It is
//! deliberately plain — this milestone is not a visual one — and it carries the
//! same [`Problem`] values every other failure path produces, so the
//! explanation, the technical detail and the retry advice are the ones the core
//! already decided on.

use iced::widget::{button, column, container, row, scrollable, text, Space};
use iced::{Alignment, Element, Length, Task, Theme};

use launcher_core::problem::Problem;

#[derive(Debug, Clone)]
pub enum Message {
    CopyDetails,
    Quit,
}

pub struct Failure {
    problems: Vec<Problem>,
    log_file: Option<std::path::PathBuf>,
    copied: bool,
}

impl Failure {
    pub fn new(
        problems: Vec<Problem>,
        log_file: Option<std::path::PathBuf>,
    ) -> (Self, Task<Message>) {
        (
            Self {
                problems,
                log_file,
                copied: false,
            },
            Task::none(),
        )
    }

    pub fn title(&self) -> String {
        format!("{} — could not start", launcher_core::PRODUCT_NAME)
    }

    pub fn theme(&self) -> Theme {
        crate::style::theme()
    }

    pub fn update(&mut self, message: Message) -> Task<Message> {
        match message {
            Message::CopyDetails => {
                self.copied = true;
                iced::clipboard::write(self.details())
            }
            Message::Quit => iced::exit(),
        }
    }

    /// Everything, as text a user can paste into a bug report.
    ///
    /// Built from `Problem::report`, which is already scrubbed and already
    /// carries the technical context — so the "copy details" button and a
    /// diagnostic export agree about what happened.
    fn details(&self) -> String {
        let mut out = format!(
            "{} {} could not start.\n",
            launcher_core::PRODUCT_NAME,
            launcher_core::PRODUCT_VERSION
        );
        for problem in &self.problems {
            out.push('\n');
            out.push_str(&problem.report());
            out.push('\n');
        }
        if let Some(log) = &self.log_file {
            out.push_str(&format!("\nLog file: {}\n", log.display()));
        }
        out
    }

    pub fn view(&self) -> Element<'_, Message> {
        let mut body = column![
            row![
                container(
                    text("!")
                        .size(crate::style::type_size::SECTION_TITLE)
                        .font(crate::style::FONT_SEMIBOLD),
                )
                .center(Length::Fixed(38.0))
                .style(crate::style::notice(crate::style::BAD)),
                column![
                    text(format!("{} could not start", launcher_core::PRODUCT_NAME))
                        .size(crate::style::type_size::PAGE_TITLE)
                        .font(crate::style::FONT_SEMIBOLD)
                        .color(crate::style::TEXT),
                    text("Your data has not been changed.")
                        .size(crate::style::type_size::BODY)
                        .color(crate::style::TEXT_MUTED),
                ]
                .spacing(crate::style::space::XXS),
            ]
            .spacing(crate::style::space::MD)
            .align_y(Alignment::Center),
            Space::with_height(crate::style::space::SM),
        ]
        .spacing(crate::style::space::XS);

        for problem in &self.problems {
            body = body.push(
                text(problem.summary().to_owned())
                    .size(crate::style::type_size::CARD_TITLE)
                    .font(crate::style::FONT_MEDIUM)
                    .color(crate::style::TEXT),
            );
            if let Some(detail) = problem.technical_detail() {
                body = body.push(
                    text(detail.to_owned())
                        .size(crate::style::type_size::SUPPORTING)
                        .color(crate::style::TEXT_MUTED),
                );
            }
            for cause in problem.causes() {
                body = body.push(
                    text(format!("  {cause}"))
                        .size(crate::style::type_size::SUPPORTING)
                        .color(crate::style::TEXT_FAINT),
                );
            }
            body = body.push(Space::with_height(8));
        }

        if let Some(log) = &self.log_file {
            body = body.push(text(format!("Log: {}", log.display())).size(12));
        }

        body = body.push(Space::with_height(12));
        body = body.push(
            iced::widget::row![
                button(text(if self.copied {
                    "Details copied"
                } else {
                    "Copy details"
                }))
                .padding(crate::style::CONTROL_PADDING)
                .style(crate::style::secondary_button)
                .on_press(Message::CopyDetails),
                Space::with_width(8),
                button(text("Close"))
                    .padding(crate::style::CONTROL_PADDING)
                    .style(crate::style::primary_button)
                    .on_press(Message::Quit),
            ]
            .spacing(4),
        );

        container(
            container(
                scrollable(body)
                    .height(Length::Fill)
                    .style(crate::style::scrollable),
            )
            .padding(crate::style::space::XL)
            .width(Length::Fill)
            .style(crate::style::card),
        )
        .padding(crate::style::space::XL)
        .width(Length::Fill)
        .height(Length::Fill)
        .style(crate::style::app_background)
        .into()
    }
}
