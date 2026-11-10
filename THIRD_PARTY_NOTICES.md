# Third-party notices

## fluidsynth-kmp 1.0.0

LumpForge uses `dev.kotlinds:fluidsynth-kmp:1.0.0` for Android MIDI synthesis with user-selected SoundFont 2 (`.sf2`) files.

- Kotlin wrapper: Apache License 2.0.
- Bundled FluidSynth native libraries: GNU Lesser General Public License v2.1 (LGPL-2.1).
- Upstream project: https://github.com/kotlinds/fluidsynth-kmp
- FluidSynth project and license: https://github.com/FluidSynth/fluidsynth

The SoundFont bank is selected and supplied by the user; LumpForge does not bundle a SoundFont.

Before distributing a release build, retain the upstream license texts and notices and review the LGPL obligations for the exact native binaries shipped with the APK. The FluidSynth libraries are dynamically linked as shared Android libraries; do not modify or statically merge them without reviewing the corresponding license requirements.
