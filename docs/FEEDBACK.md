# Where reports come from, and how they become GitHub issues

Every bug and idea ends up as a GitHub Issue, so there is one list to work from. This page says how each source gets there, what is automatic, and what is a habit.

**The one inbox:** the repo's Issues tab, filtered with `label:needs-triage`. A workflow ([triage.yml](../.github/workflows/triage.yml)) adds that label to every new issue. Take it off when you have looked at the issue.

## Sources

| Source | How it reaches GitHub | Label | Automatic? |
|---|---|---|---|
| **App crash** | After a crash, the next launch shows the report. The player taps **Report on GitHub**, which opens a pre-filled bug form (version, device, stack trace). Or **Share…** sends the text anywhere. | `from-app`, `bug` | Pre-filled; the player presses Submit |
| **App "Report a problem" / "Send feedback"** (menu) | Opens the pre-filled bug or feedback form on GitHub. | `from-app`, `bug` or `feedback` | Pre-filled; the player presses Submit |
| **GitHub directly** | The issue forms in [.github/ISSUE_TEMPLATE](../.github/ISSUE_TEMPLATE). | `bug` or `feedback` | Yes |
| **Discord** `#bug-reports`, `#feedback` | You file an issue from the thread (below) and link the thread both ways. | `from-discord` | Manual (a habit, one command) |
| **Google Play** reviews and Android vitals crashes | Read them in Play Console; file an issue for anything real and reply on Play. | `from-play` | Manual |
| **Anything else** (mail, in person) | File it by hand. | none | Manual |

Players without a GitHub account use **Share…** in the crash sheet to send the text to Discord or mail; you turn it into an issue.

## Filing from Discord (the habit)

When a thread in `#bug-reports` reproduces, or a `#feedback` idea is worth keeping:

```
gh issue create -R miss-mercury-studio/hgCOMMLINK --label bug,from-discord \
  --title "Short summary" --body "Reported in Discord: <thread link>

Steps, version and device from the thread."
```

Then reply in the Discord thread with the issue link, and when the release that fixes it ships, comment on the issue with the release link and close it (the release script's notes use the same words).

## What the app sends, and does not

- The app asks for **no network permission**, so it cannot send anything itself. Opening GitHub or sharing is the player's action, from a form they see and can edit.
- A report holds the app version, the phone model, the Android version, the time and the stack trace. No character names, no files, no account.
- The crash text is kept in the app's private storage until the player reports or dismisses it.

## Limits

- Only crashes are caught. A freeze ("app not responding") leaves no report from the app; Play Console shows those once the app is on Play.
- A very long trace is trimmed to fit a link (the top frames and every "Caused by" line are kept).
- GitHub issue forms only pre-fill fields by their id; renaming a field in an issue form breaks the pre-fill. The ids are `version`, `device`, `steps`, `crash`.
- The repo address is a constant in [CrashReporter.kt](../app/src/main/kotlin/studio/missmercury/hgcommlink/CrashReporter.kt). If the repo moves again, change it there (GitHub redirects in the meantime).

## Later, if the volume grows

- A **Discord bot** that turns a thread into an issue with one reaction or command. Worth it once there are more than a few reports a week; it needs a small always-on service and a bot token.
- **Crashlytics** would give crash reports without the player doing anything, but it needs the network permission and a data-collection disclosure, which changes the app's privacy story. Not recommended for now.
