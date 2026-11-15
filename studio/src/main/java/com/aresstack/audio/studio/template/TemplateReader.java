package com.aresstack.audio.studio.template;

import com.aresstack.audio.studio.ai.ChatModel;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Reads a template in whatever form it comes: MIDI and MusicXML (also compressed) the studio
 * reads itself, exactly as written; any other text (ABC, LilyPond, ...) the AI writes over into
 * notes ({@link TextTemplate}). What can be neither (a PDF, a picture of a page) is refused with
 * words that say what to give instead.
 */
public final class TemplateReader {
    /** The largest template that is read, in bytes. */
    public static final long MOST_BYTES = 32L * 1024 * 1024;

    private TemplateReader() {
    }

    /** The endings of the files a chooser offers first. */
    public static final String[] ENDINGS = {"mid", "midi", "kar", "musicxml", "xml", "mxl", "abc", "ly", "krn",
            "txt"};

    /**
     * @param model           asked only for a template that is text in another notation; may be
     *                        {@code null}, then such a template is refused
     * @param linesPerRequest the lines of such a template the AI writes over in one request
     * @param folder          the folder of the piece, where the answers of the AI are kept
     * @param heard           told what the AI is writing and how much of it has arrived; may be {@code null}
     */
    public static TemplateScore read(Path file, ChatModel model, int maxTokens, int linesPerRequest, Path folder,
                                     BiConsumer<String, Long> heard) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("Die Notenvorlage fehlt: " + file);
        }
        if (Files.size(file) > MOST_BYTES) {
            throw new IOException("Die Notenvorlage ist größer als " + (MOST_BYTES / 1024 / 1024) + " MB.");
        }
        byte[] bytes = Files.readAllBytes(file);
        String name = file.getFileName().toString();
        String title = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        String ending = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (MidiTemplate.looksLikeMidi(bytes)) {
            return MidiTemplate.read(bytes, title);
        }
        if (MusicXmlTemplate.looksCompressed(bytes)) {
            return MusicXmlTemplate.read(bytes, title);
        }
        if (ending.equals("pdf") || startsWith(bytes, "%PDF") || ending.matches("png|jpe?g|gif|bmp|tiff?")) {
            throw new IOException("Eine Notenvorlage als PDF oder Bild lässt sich nicht lesen. Bitte die Noten als"
                    + " MIDI, MusicXML (z. B. aus MuseScore oder IMSLP) oder ABC geben.");
        }
        String text = text(bytes);
        if (text == null) {
            throw new IOException("„" + name + "“ ist weder MIDI noch MusicXML noch Text.");
        }
        if (MusicXmlTemplate.looksLikeMusicXml(text)) {
            return MusicXmlTemplate.read(bytes, title);
        }
        if (model == null) {
            throw new IOException("„" + name + "“ ist Text in einer Notenschrift, die nur die KI liest; dazu fehlt"
                    + " die Verbindung zur KI.");
        }
        return TextTemplate.read(text, title, model, maxTokens, linesPerRequest, folder, heard);
    }

    private static boolean startsWith(byte[] bytes, String ascii) {
        if (bytes.length < ascii.length()) {
            return false;
        }
        for (int i = 0; i < ascii.length(); i++) {
            if (bytes[i] != ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** The bytes as UTF-8 text, else as Latin-1 if they hold no control characters; {@code null} for binary data. */
    static String text(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notUtf8) {
            text = new String(bytes, StandardCharsets.ISO_8859_1);
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        for (int i = 0; i < Math.min(text.length(), 8192); i++) {
            char c = text.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t' && c != '\f') {
                return null;
            }
        }
        return text;
    }
}
