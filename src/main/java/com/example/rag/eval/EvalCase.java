package com.example.rag.eval;

import java.util.List;

/**
 * One test case from the evaluation dataset, deserialized from eval/dataset.json.
 * In-scope cases name a snippet the retrieved chunks should contain and the facts a correct answer must cover.
 * Out-of-scope cases instead expect the system to decline rather than answer.
 */
public class EvalCase {

    /** Short identifier used in progress output and the results CSV. */
    public String id;

    /** The question sent to the query endpoint. */
    public String question;

    /** Whether the correct behavior is to say the answer is unknown. */
    public boolean expectOutOfScope = false;

    /** Text that at least one cited chunk must contain for a retrieval hit. */
    public String expectedChunkContains;

    /** Facts the judge checks the answer against. */
    public List<String> expectedFacts;
}
