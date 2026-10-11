# Contributing

Bug reports, ideas and pull requests are welcome.

## Issues

Use the templates. For a bug, the phone model, the Android version and what the
status line said are the most useful details; the log
(`adb logcat -s python.stdout python.stderr`) is better still. If the problem is
about connecting, check [docs/connecting.md](docs/connecting.md) first.

## Pull requests

- Build with the steps in the [README](README.md#build-from-source), and test on
  a real robot: say which phone, Android version and robot you tested with.
- Keep one change per pull request.
- The app talks to the robot and to nothing else. Changes that add network
  access, analytics, crash reporting, advertising or accounts will not be
  merged.
- Do not commit third-party files. Anki's assets in particular are not
  redistributable and must never enter the repository or the APK; Python
  packages come in through Chaquopy at build time.
- Comments should explain *why* a piece of code is there, especially when it
  works around a robot or pycozmo quirk.
- Commit messages and comments may be in English or French.

## Licence

By contributing, you agree that your contribution is licensed under the
[EUPL-1.2](LICENSE) licence, like the rest of the project.
