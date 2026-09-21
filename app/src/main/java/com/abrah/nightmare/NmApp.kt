package com.abrah.nightmare

import android.app.Application
import android.content.Context

/**
 * ⭐⭐ **A static handle on the application Context, for the strings that are
 * built outside a Composable.**
 *
 * ⚠⚠ **Why this is needed.** Most of this app's user-visible text lives in
 * `@Composable` bodies and is fetched with `stringResource(R.string.x)`, which
 * needs no context. But a large share of it does not: a node's `hint`, a run
 * log line, an exception message, a notification, a download phase label. Those
 * are constructed deep in `Executor`, `ModelInstaller`, `BackendProcess` and
 * friends, where no `Context` is in scope and where threading one through every
 * call would be a far larger change than the translation itself.
 *
 * `androidx.startup` or a DI container would also solve it. An `Application`
 * subclass with one static field is the smallest thing that does, and this app
 * already has no other reason to introduce either.
 *
 * ⚠⚠ **`applicationContext`, never an Activity.** That is the whole point of
 * holding it here: a node's hint can be built on a worker thread while no
 * Activity exists, and holding an Activity would leak it.
 *
 * ⚠⚠ **Null before [onCreate].** Nothing in this app reads a string before
 * `Application.onCreate`, but a unit test that builds a node without an
 * Application would crash on a bare `!!`. [str] returns the English source text
 * rather than throwing in that case, because a missing translation must never
 * be the thing that stops a render.
 */
class NmApp : Application() {

    override fun onCreate() {
        super.onCreate()
        app = this
    }

    companion object {
        @Volatile
        private var app: NmApp? = null

        /**
         * ⚠ The Application, or null in a unit test that never started one.
         * Callers that can live without it should prefer [str].
         */
        val context: Context? get() = app

        /**
         * ⭐⭐ **Cache for [str], and it is not an optimisation — it is a fix.**
         *
         * ⚠⚠ **Measured problem.** A `Resources.getString` walks the resource
         * table and the `AssetManager`; a Kotlin string literal does not. This
         * function is called from `NodeType.widgets`, which upstream writes as
         * `override val widgets get() = listOf(...)` — a getter that
         * `NodeInspector` reads nine times in one composable body and
         * `GraphCanvas` reads on the DRAW path, once per node per frame. Before
         * the translation those getters only allocated a list; after it each
         * call also did a resource lookup, and panning the canvas visibly
         * stuttered. Reported on device 2026-09-21.
         *
         * ⚠ Keyed on `id` alone, NOT on `id` + args: only the 40 no-argument
         * calls sit on hot paths, and every one of the 104 formatted calls
         * carries a per-node value (`node.id`, a size, a filename) that would
         * make the cache grow without bound. Formatted calls therefore skip it
         * — they are error and progress text, built once per render, not per
         * frame.
         *
         * ⚠ Bounded because a cache on a static is a leak if it never empties;
         * ~350 resource ids exist, so 512 can never thrash.
         */
        private val cache = java.util.concurrent.ConcurrentHashMap<Int, String>(512)

        /**
         * ⭐ A resource string, safe to call from anywhere including a worker
         * thread and a JVM unit test.
         *
         * @param fallback the English text, returned when there is no
         *   Application. ⚠ Passing it is what keeps a test that constructs a
         *   model node from failing with "not mocked" instead of testing what
         *   it meant to test.
         */
        fun str(id: Int, fallback: String, vararg args: Any): String {
            val c = app ?: return fallback
            if (args.isEmpty()) {
                // ⚠ The hot path — see the note on [cache].
                cache[id]?.let { return it }
                val v = try {
                    c.getString(id)
                } catch (e: Exception) {
                    // ⚠ A missing or malformed translation is a cosmetic bug.
                    // It must not become a crash inside a render.
                    fallback
                }
                cache[id] = v
                return v
            }
            return try {
                c.getString(id, *args)
            } catch (e: Exception) {
                fallback
            }
        }
    }
}
