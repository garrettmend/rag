package com.example.rag.eval;

/**
 * The measured outcome of running a single evaluation case.
 * It records whether retrieval surfaced the expected chunk and the score the LLM judge gave the answer.
 * End-to-end latency is kept alongside so runs can be compared on speed as well as quality.
 *
 * @param id           the case identifier
 * @param retrievalHit whether a cited chunk contained the expected text (always true for out-of-scope cases)
 * @param judgeScore   the judge's 1-5 score, or 0 if its response could not be parsed
 * @param latencyMs    the query round-trip time in milliseconds
 */
public record EvalResult(String id, boolean retrievalHit, int judgeScore, long latencyMs) {}
