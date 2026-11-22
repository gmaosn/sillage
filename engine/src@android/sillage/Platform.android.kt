package sillage

/** Controllers paired with the phone are not read yet: the touch screen plays the pad (see Touches). */
internal actual fun platformPads(): PadSource = PadSource.NONE
