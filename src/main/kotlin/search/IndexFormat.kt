package dev.santo.search

/**
 * Magic header for the binary index artifact ("FSI2" — int16 store; bumped from
 * "FSI1" int8 so an old artifact fails fast instead of being misread). The single
 * source of truth for the on-disk format, shared by the reader ([IndexReader],
 * runtime) and the writer (`tools.IndexWriter`, offline build).
 */
internal const val INDEX_MAGIC = 0x46534932

/**
 * Magic header for the two-level IVF artifact ("IVF3" — SoA-16 block point store
 * for the SIMD kernel; bumped from "IVF2" AoS so an old artifact fails fast instead
 * of being misread). Distinct from [INDEX_MAGIC] so a VP-tree artifact and an IVF
 * artifact each fail fast if fed to the wrong reader.
 */
internal const val IVF_MAGIC = 0x49564633
