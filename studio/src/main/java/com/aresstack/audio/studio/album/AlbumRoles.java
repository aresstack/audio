package com.aresstack.audio.studio.album;

import com.aresstack.audio.studio.ai.ModelRole;
import com.aresstack.audio.studio.ai.ModelRoles;

/** The model role of the albums: planning the list of titles. Without a model of its own it follows planning. */
public final class AlbumRoles {
    public static final ModelRole ALBUM = ModelRoles.register(
            new ModelRole("album", "Album", "Trackliste eines Albums planen", "planning", false));

    private AlbumRoles() {
    }
}
