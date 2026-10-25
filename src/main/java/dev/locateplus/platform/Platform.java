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

import dev.locateplus.core.LPConstants;

import java.nio.file.Path;

/** Loader-agnostic services. */
public interface Platform {

    /** Loader name, e.g. {@code "fabric"} or {@code "forge"}. Used in export headers. */
    String loaderName();

    /** Mod version as declared in the loader metadata. */
    String modVersion();

    /** Running Minecraft version, e.g. {@code "1.20.1"}. */
    String minecraftVersion();

    /** The loader's config directory, e.g. {@code <gamedir>/config}. */
    Path configDir();

    /** Whether another mod with the given id is present. */
    boolean isModLoaded(String modId);

    /** {@code config/locate-plus/exports}, where scan exports are written. */
    default Path exportDir() {
        return configDir().resolve(LPConstants.MOD_ID_PATH).resolve("exports");
    }
}
