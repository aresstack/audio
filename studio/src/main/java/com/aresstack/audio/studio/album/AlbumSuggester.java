package com.aresstack.audio.studio.album;

import java.io.IOException;
import java.util.List;

/**
 * Proposes what the user might write into a field of an album: titles, what the album is about,
 * and styles. Proposals are an inspiration, not a decision; nothing is taken over unasked.
 */
public interface AlbumSuggester {
    /** The field proposals are made for. */
    enum Field {
        TITLE("title"), DESCRIPTION("description"), STYLE("style");

        private final String key;

        Field(String key) {
            this.key = key;
        }

        /** The name of the field in files. */
        public String key() {
            return key;
        }
    }

    /**
     * Proposals for one field, the best first; an empty list if there are none.
     *
     * @param field       what is proposed
     * @param title       the title of the album as it stands, may be empty
     * @param description what the album is about as it stands, may be empty
     * @param style       the style as it stands, may be empty; for {@link Field#STYLE} what the
     *                    user has typed so far, which the proposals refine
     * @param known       proposals made before, which are not to come again
     */
    List<String> suggest(Field field, String title, String description, String style, List<String> known)
            throws IOException;

    /**
     * The same, and the proposals can be seen while they arrive: {@code seen} is given the ones
     * written so far (the last one perhaps not yet whole), a few times a second at most.
     */
    default List<String> suggest(Field field, String title, String description, String style, List<String> known,
                                 java.util.function.Consumer<List<String>> seen) throws IOException {
        return suggest(field, title, description, style, known);
    }
}
