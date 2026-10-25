/*
 * Locate Plus
 * Copyright (C) 2026 forest_mask
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.locateplus.platform;

/** Static holder for the active {@link Platform}. */
public final class Services {

    private static volatile Platform platform;

    private Services() {
    }

    public static void bootstrap(Platform impl) {
        if (impl == null) {
            throw new IllegalArgumentException("platform must not be null");
        }
        platform = impl;
    }

    public static Platform platform() {
        Platform p = platform;
        if (p == null) {
            throw new IllegalStateException(
                    "Locate Plus platform not bootstrapped: the loader entrypoint must call "
                            + "Services.bootstrap() first");
        }
        return p;
    }

    public static boolean isBootstrapped() {
        return platform != null;
    }
}
