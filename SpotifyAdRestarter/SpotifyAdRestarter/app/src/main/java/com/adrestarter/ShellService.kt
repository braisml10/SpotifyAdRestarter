package com.adrestarter

import kotlin.system.exitProcess

/**
 * Corre con privilegios de shell dentro de Shizuku. Solo sabe hacer dos cosas,
 * y ambas únicamente sobre Spotify: cerrarlo y lanzarlo.
 */
class ShellService : IShell.Stub() {

    private companion object {
        const val SPOTIFY = "com.spotify.music"
        val COMPONENT = Regex("^com\\.spotify\\.music/[A-Za-z0-9_.$]+$")
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun forceStop(pkg: String): Boolean {
        if (pkg != SPOTIFY) return false
        return exec("cmd", "activity", "force-stop", SPOTIFY)
    }

    override fun startApp(component: String): Boolean {
        if (!COMPONENT.matches(component)) return false
        return exec("am", "start", "--activity-no-animation", "-n", component)
    }

    private fun exec(vararg cmd: String): Boolean = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        p.inputStream.use { it.readBytes() }
        p.waitFor() == 0
    } catch (e: Exception) {
        false
    }
}
