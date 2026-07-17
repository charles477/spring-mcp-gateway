package io.mcpgateway.guardrail;

import java.util.List;

/**
 * One link in the guardrail chain (FR-GUARD-2). Each guardrail inspects content, may transform
 * it (e.g. redaction), and reports what it found. New detectors are added by declaring another
 * bean — the pipeline discovers them by type, so call sites never change.
 */
public interface Guardrail {

    Result apply(String content);

    /** The (possibly transformed) content plus a label for each finding, e.g. {@code aws-access-key}. */
    record Result(String content, List<String> findings) {
    }
}
