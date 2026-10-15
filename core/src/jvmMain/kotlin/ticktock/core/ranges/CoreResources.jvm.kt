// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.ranges

internal actual fun readCoreResource(path: String): String? =
    BaseRanges::class.java.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }
