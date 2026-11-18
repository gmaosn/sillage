package sillage.math

/**
 * Marks a type written to become a `value class` once Kotlin maps multi-field value classes onto
 * Valhalla (JEP 401). Such a type is final, immutable and identity-free: code must never compare it
 * with `===`, lock on it, or rely on two equal instances being distinct. Until then the JIT's escape
 * analysis removes most short-lived instances; hot loops keep their data in primitive arrays anyway.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
annotation class ValueCandidate
