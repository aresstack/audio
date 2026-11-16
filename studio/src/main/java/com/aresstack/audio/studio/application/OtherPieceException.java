package com.aresstack.audio.studio.application;

import java.io.IOException;

/**
 * A folder holds a piece that was planned otherwise than the piece asked for now. It is thrown
 * before the first file of the piece is touched: the piece in the folder is exactly as it was.
 */
public final class OtherPieceException extends IOException {
    private static final long serialVersionUID = 1L;

    public OtherPieceException(String message) {
        super(message);
    }
}
