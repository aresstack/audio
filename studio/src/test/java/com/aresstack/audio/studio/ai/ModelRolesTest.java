package com.aresstack.audio.studio.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Role registry, assignment with fallbacks and the lineup that cancels all its models. */
public final class ModelRolesTest {
    private static final String GEMMA = "google/gemma-4-31B-it";

    @Test
    public void thePageShowsPlanningAndCompositionOnly() {
        assertEquals(List.of(ModelRoles.PLANNING, ModelRoles.COMPOSITION), ModelRoles.visible());
        assertTrue(ModelRoles.all().size() >= 6);
    }

    @Test
    public void oneModelAnswersForEveryRoleUntilARoleGetsItsOwn() {
        ModelAssignment single = ModelAssignment.single(GEMMA);
        for (ModelRole role : ModelRoles.all()) {
            assertEquals(role.key(), GEMMA, single.modelFor(role));
        }
        ModelAssignment split = single.with(ModelRoles.PLANNING, "gpt-oss:20b");
        assertEquals("gpt-oss:20b", split.modelFor(ModelRoles.PLANNING));
        assertEquals(GEMMA, split.modelFor(ModelRoles.COMPOSITION));
        // Roles that follow planning follow it; roles that follow composition stay with it.
        assertEquals("gpt-oss:20b", split.modelFor(ModelRoles.INSTRUMENTS));
        assertEquals("gpt-oss:20b", split.modelFor(ModelRoles.ARRANGING));
        assertEquals("gpt-oss:20b", split.modelFor(ModelRoles.MIX));
        assertEquals(GEMMA, split.modelFor(ModelRoles.REWORK));
        // An own model of a follower wins; clearing it lets it follow again.
        ModelAssignment mix = split.with(ModelRoles.MIX, "other");
        assertEquals("other", mix.modelFor(ModelRoles.MIX));
        assertNull(mix.with(ModelRoles.MIX, " ").ownModel(ModelRoles.MIX));
        assertEquals(split, mix.with(ModelRoles.MIX, null));
    }

    @Test
    public void anotherRoleCanBeRegisteredLater() {
        ModelRole album = new ModelRole("album-test", "Album", "Trackliste planen", "planning", false);
        assertSame(album, ModelRoles.register(album));
        assertSame(album, ModelRoles.register(album));
        assertEquals("x", ModelAssignment.single("y").with(ModelRoles.PLANNING, "x").modelFor(album));
        try {
            ModelRoles.register(new ModelRole("album-test", "Anders", "", null, false));
            fail("same key, other role");
        } catch (IllegalArgumentException expected) {
        }
        try {
            ModelRoles.register(new ModelRole("orphan", "Waise", "", "nowhere", false));
            fail("unknown fallback");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void rolesWithTheSameModelShareOneClientAndCancelStopsAll() {
        AtomicInteger made = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        ModelAssignment assignment = ModelAssignment.single(GEMMA).with(ModelRoles.PLANNING, "gpt-oss:20b");
        ModelLineup lineup = new ModelLineup(assignment, name -> {
            made.incrementAndGet();
            return new ChatModel() {
                @Override
                public com.google.gson.JsonObject generate(String i, String p, int t, java.nio.file.Path c) {
                    return new com.google.gson.JsonObject();
                }

                @Override
                public void cancel() {
                    cancelled.incrementAndGet();
                }
            };
        });
        ChatModel planning = lineup.model(ModelRoles.PLANNING);
        assertSame(planning, lineup.model(ModelRoles.MIX));
        ChatModel composition = lineup.model(ModelRoles.COMPOSITION);
        assertSame(composition, lineup.model(ModelRoles.REWORK));
        assertEquals(2, made.get());
        assertEquals(List.of("gpt-oss:20b", GEMMA), lineup.usedModels());

        lineup.cancel();
        assertEquals("both models stopped", 2, cancelled.get());
        lineup.model(ModelRoles.INSTRUMENTS); // known model: no new one
        assertEquals(3, cancelled.get()); // handed out after cancelling: told to stop at once
    }

    /** A lineup with every model wrapped keeps the models and the assignment of the lineup it came from. */
    @Test
    public void aWrappedLineupSharesTheModelsOfItsLineup() {
        java.util.List<String> made = new java.util.ArrayList<>();
        java.util.List<String> cancelled = new java.util.ArrayList<>();
        ModelLineup lineup = new ModelLineup(ModelAssignment.single("base").with(ModelRoles.COMPOSITION, "big"),
                name -> {
                    made.add(name);
                    return new ChatModel() {
                        @Override
                        public com.google.gson.JsonObject generate(String instruction, String prompt, int maxTokens,
                                                                   java.nio.file.Path cache) {
                            return new com.google.gson.JsonObject();
                        }

                        @Override
                        public void cancel() {
                            cancelled.add(name);
                        }
                    };
                });
        java.util.List<ChatModel> wrapped = new java.util.ArrayList<>();
        ModelLineup through = lineup.through(model -> {
            wrapped.add(model);
            return model;
        });
        org.junit.Assert.assertSame(lineup.model(ModelRoles.COMPOSITION), through.model(ModelRoles.COMPOSITION));
        through.model(ModelRoles.PLANNING);
        through.model(ModelRoles.PLANNING);
        org.junit.Assert.assertEquals(java.util.List.of("big", "base"), made);
        org.junit.Assert.assertEquals(2, wrapped.size());
        org.junit.Assert.assertEquals(lineup.assignment(), through.assignment());
        through.cancel();
        org.junit.Assert.assertEquals(java.util.List.of("big", "base"), cancelled);
    }
}
