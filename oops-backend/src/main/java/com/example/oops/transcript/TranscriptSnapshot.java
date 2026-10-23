package com.example.oops.transcript;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Length-framed ordered text/time digest; never trims or repairs the source. */
public final class TranscriptSnapshot {
    private TranscriptSnapshot() {}
    public static String sha256(List<TranscriptProvider.TranscriptLine> lines) {
        if (lines.isEmpty()) throw new IllegalArgumentException("Stored transcript required");
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(var line:lines) {
                if(line.startMs()<0 || line.endMs()<line.startMs() || line.text()==null || line.text().isBlank())
                    throw new IllegalArgumentException("Invalid stored transcript");
                byte[] text=line.text().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(20).putLong(line.startMs()).putLong(line.endMs()).putInt(text.length).array());
                digest.update(text);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
}
