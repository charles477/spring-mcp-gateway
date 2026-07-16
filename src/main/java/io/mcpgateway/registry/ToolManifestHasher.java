package io.mcpgateway.registry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes the pinned content hash of a tool definition (FR-REG-5).
 *
 * <p>The hash covers everything an LLM ever sees about the tool — name, description, input
 * schema — because the rug-pull attack works by mutating any of them. Fields are length-prefixed
 * before hashing so no concatenation of one field can impersonate another.
 */
public final class ToolManifestHasher {

    private ToolManifestHasher() {
    }

    public static String hash(String name, String description, String inputSchema) {
        MessageDigest digest = sha256();
        for (String field : new String[] {name, nullSafe(description), inputSchema}) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            digest.update(intToBytes(bytes.length));
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static byte[] intToBytes(int value) {
        return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Mandated by the JCA spec for every JVM; unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
