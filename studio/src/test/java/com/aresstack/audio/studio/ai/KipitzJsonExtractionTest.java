package com.aresstack.audio.studio.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import org.junit.Test;

/** A model's answer with its JSON object among other words is still read. */
public class KipitzJsonExtractionTest {
    @Test
    public void plainObject() {
        assertEquals("Winter", KipitzChatClient.jsonObjectIn("{\"title\":\"Winter\"}").get("title").getAsString());
    }

    @Test
    public void thinkingWithBracesBefore() {
        String answer = "We need {style, description, title}. Let me think {not json}.\n"
                + "{\"title\":\"Schnee\",\"style\":\"Chor\"}";
        JsonObject read = KipitzChatClient.jsonObjectIn(answer);
        assertEquals("Schnee", read.get("title").getAsString());
    }

    @Test
    public void codeBlockAndWordsAfter() {
        String answer = "```json\n{\"title\":\"Stern {hell}\",\"list\":[{\"a\":1}]}\n```\nHope this helps {smile}";
        JsonObject read = KipitzChatClient.jsonObjectIn(answer);
        assertEquals("Stern {hell}", read.get("title").getAsString());
    }

    @Test
    public void nothingUsable() {
        assertNull(KipitzChatClient.jsonObjectIn("no json here"));
        assertNull(KipitzChatClient.jsonObjectIn("{broken"));
    }

    @Test
    public void glimpseIsShort() {
        String text = "x".repeat(500);
        assertTrue(KipitzChatClient.glimpse(text).length() < 210);
    }

    @Test
    public void wholeAnswerWithoutContentIsEmptyNotUnreadable() {
        KipitzChatClient.Answer cut = KipitzChatClient.wholeAnswer(
                "{\"choices\":[{\"message\":{\"content\":null,\"reasoning_content\":\"thinking\"},"
                        + "\"finish_reason\":\"length\"}]}");
        assertEquals("", cut.text);
        assertEquals("length", cut.finishReason);
        assertNull(KipitzChatClient.wholeAnswer("<html>gateway</html>"));
    }

    /** An answer asked for without a place to keep it (the album's field proposals) is not kept, not an error. */
    @Test
    public void anAnswerWithoutCacheIsNotKept() throws Exception {
        AnswerCache.store(null, null, "{}");
    }
}
