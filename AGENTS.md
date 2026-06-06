# Codex Project Notes

## Project Context

This is a hackathon project. Optimize for a functional demo, clear implementation, and easy teammate handoff.

## Development Guidelines

- Inspect the repo before changing files.
- Follow the existing framework, dependency choices, and project layout.
- Keep edits small and directly tied to the task.
- Avoid unrelated cleanup or formatting churn.
- Prefer built-in framework conventions over custom abstractions.
- Use ASCII text unless a file already uses another character set.

## Git Guidelines

- Always check `git status --short --branch` before and after changes.
- Never run destructive git commands such as `git reset --hard` or `git checkout --` unless explicitly asked.
- Do not revert teammate changes.
- If git is not initialized, remotes are missing, authentication fails, or conflicts appear, stop and guide the user instead of forcing a fix.

## Verification

- Run the narrowest useful verification command for the change.
- For frontend changes, run or open the app and verify the affected screen when practical.
- Report commands that were run and any commands that could not be run.
