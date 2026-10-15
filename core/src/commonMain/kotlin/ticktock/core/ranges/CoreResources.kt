// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.ranges

/**
 * Reads a data file bundled with :core, or null when it is missing. Each
 * platform bundles the files its own way: the JVM target ships them as
 * classpath resources.
 */
internal expect fun readCoreResource(path: String): String?
