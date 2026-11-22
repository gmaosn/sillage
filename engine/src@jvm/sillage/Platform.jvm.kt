package sillage

/** The computer's controllers. Read on Linux (hidraw, evdev); on other systems the keyboard and the mouse play alone for now. */
internal actual fun platformPads(): PadSource = if (sillage.platform.Os.linux) sillage.platform.SystemPads() else PadSource.NONE
