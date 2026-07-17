package io.mcpgateway.guardrail;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Chain-of-Responsibility over every registered {@link Guardrail} bean (FR-GUARD-2): content
 * flows through each detector in declaration order, transformations compose, findings aggregate.
 * Adding a detector means adding a bean — this class and its callers never change.
 */
@Component
public class GuardrailPipeline {

    private final List<Guardrail> chain;

    public GuardrailPipeline(List<Guardrail> chain) {
        this.chain = chain;
    }

    public Guardrail.Result inspect(String content) {
        String current = content;
        List<String> findings = new ArrayList<>();
        for (Guardrail guardrail : chain) {
            Guardrail.Result result = guardrail.apply(current);
            current = result.content();
            findings.addAll(result.findings());
        }
        return new Guardrail.Result(current, findings);
    }
}
