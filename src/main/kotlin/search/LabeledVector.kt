package dev.santo.search

/** A reference vector (14 dims) with its fraud label. */
class LabeledVector(val vector: DoubleArray, val isFraud: Boolean)
