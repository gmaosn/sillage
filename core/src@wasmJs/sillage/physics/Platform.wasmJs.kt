package sillage.physics

internal actual fun platformWorkers(threads: Int): Workers = error("A web page has one thread")
internal actual fun platformWideSolver(world: PhysicsWorld): WideContacts? = null
