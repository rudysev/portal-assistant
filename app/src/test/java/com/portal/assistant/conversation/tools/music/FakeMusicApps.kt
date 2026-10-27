package com.portal.assistant.conversation.tools.music

import com.portal.assistant.conversation.tools.AppEntry

/**
 * An in-memory [MusicApps]: the saved [default], the discovered [music] apps, the [searchPlayable] ones, the packages
 * with play-from-search support, and what each start returns. Every start it was asked to fire lands in [fired].
 * [appLabel] reads like the Android adapter's `PackageCatalog.labelFor`: any [installed] package (every listed app
 * counts as installed) by its label, whether or not it is in a list, and an unknown package as itself.
 */
internal class FakeMusicApps : MusicApps {
    var default: String? = null
    var music: List<AppEntry> = emptyList()
    var searchPlayable: List<AppEntry> = emptyList()
    var installed: Map<String, String> = emptyMap()
    var playFromSearchPkgs: Set<String> = emptySet()
    var deepLinkResult: Start = Start.Started
    var playFromSearchResult: Start = Start.Started
    var launchResult: Start = Start.Started

    val fired = mutableListOf<String>()

    override fun defaultPkg(): String? = default

    override fun musicApps(): List<AppEntry> = music

    override fun searchPlayableApps(): List<AppEntry> = searchPlayable

    override fun appLabel(pkg: String): String =
        installed[pkg] ?: (music + searchPlayable).firstOrNull { it.pkg == pkg }?.label ?: pkg

    override fun hasPlayFromSearch(pkg: String): Boolean = pkg in playFromSearchPkgs

    override fun searchDeepLink(pkg: String, query: String): Start {
        fired += "deepLink $pkg $query"
        return deepLinkResult
    }

    override fun playFromSearch(pkg: String, query: String, type: PlayType?): Start {
        fired += "playFromSearch $pkg $query ${type ?: "any"}"
        return playFromSearchResult
    }

    override fun launch(pkg: String): Start {
        fired += "launch $pkg"
        return launchResult
    }
}
