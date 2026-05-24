package dev.santo.bootstrap

import dev.santo.search.IndexState
import dev.santo.vectorization.ReferenceResources
import dev.santo.vectorization.Vectorizer

/** Dependencies shared across the application modules. */
class AppComponents(
    val vectorizer: Vectorizer,
    val indexState: IndexState,
) {
    companion object {
        fun create(): AppComponents = AppComponents(
            vectorizer = Vectorizer(
                ReferenceResources.loadNormalization(),
                ReferenceResources.loadMccRisk(),
            ),
            indexState = IndexState(),
        )
    }
}
