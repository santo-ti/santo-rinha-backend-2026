package dev.santo.bootstrap

import dev.santo.search.IndexState
import dev.santo.vectorization.ByteVectorizer
import dev.santo.vectorization.ReferenceResources
import dev.santo.vectorization.Vectorizer

/** Dependencies shared across the application modules. */
class AppComponents(
    val vectorizer: Vectorizer,
    val byteVectorizer: ByteVectorizer,
    val indexState: IndexState,
) {
    companion object {
        fun create(): AppComponents {
            val constants = ReferenceResources.loadNormalization()
            val mccRisk = ReferenceResources.loadMccRisk()
            return AppComponents(
                vectorizer = Vectorizer(constants, mccRisk),
                byteVectorizer = ByteVectorizer(constants, mccRisk),
                indexState = IndexState(),
            )
        }
    }
}
