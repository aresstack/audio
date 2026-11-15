package com.aresstack.audio.studio.stage;

/**
 * What the stage needs to know of whoever it seats: an id to keep the place by, and a role
 * (one of {@link com.aresstack.audio.composition.Role#ALL}) that decides the usual seat. The stage
 * depends on nothing above it (no cycle with the application, finding F7 of the architecture
 * review): the application's tracks are performers, and so can be anything else that has a role.
 */
public interface Performer {
    String id();

    String role();
}
