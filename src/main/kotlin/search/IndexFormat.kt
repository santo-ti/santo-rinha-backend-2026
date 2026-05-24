package dev.santo.search

/**
 * Magic header for the binary index artifact ("FSI1"). The single source of truth
 * for the on-disk format, shared by the reader ([IndexReader], runtime) and the
 * writer (`tools.IndexWriter`, offline build) so the two cannot drift apart.
 */
internal const val INDEX_MAGIC = 0x46534931
