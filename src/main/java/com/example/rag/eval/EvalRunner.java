package com.example.rag.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Command-line tool that runs the evaluation dataset against a live RAG API.
 * For each case it calls the query endpoint, checks whether retrieval surfaced the expected chunk, and has a Bedrock LLM judge score the answer.
 * It prints a summary and writes per-case results to a CSV in eval-results for later comparison.
 */
public class EvalRunner {

    private static final String REGION = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
    private static final String JUDGE_MODEL_ID =
            System.getenv().getOrDefault("JUDGE_MODEL_ID", "us.anthropic.claude-haiku-4-5-20251001-v1:0");
    private static final String API_BASE_URL =
            System.getenv().getOrDefault("API_BASE_URL", "http://localhost:8080");
    private static final String TENANT_ID =
            System.getenv().getOrDefault("EVAL_TENANT_ID", "11111111-1111-1111-1111-111111111111");

    /**
     * Runs every case in the dataset and records the results.
     *
     * @param args optional run name, used as the CSV file name; defaults to a timestamped name
     * @throws Exception if the dataset cannot be loaded or a request fails
     */
    public static void main(String[] args) throws Exception {
        String runName = args.length > 0 ? args[0] : "run-" + Instant.now().toEpochMilli();

        ObjectMapper mapper = new ObjectMapper();
        HttpClient http = HttpClient.newHttpClient();
        BedrockRuntimeClient judgeClient = BedrockRuntimeClient.builder().region(Region.of(REGION)).build();

        List<EvalCase> cases = loadDataset(mapper);
        List<EvalResult> results = new ArrayList<>();

        for (EvalCase c : cases) {
            long start = System.currentTimeMillis();
            QueryResponse response = callQuery(http, mapper, c.question);
            long latencyMs = System.currentTimeMillis() - start;

            boolean retrievalHit = c.expectOutOfScope
                    || response.citationContents().stream().anyMatch(content -> content.contains(c.expectedChunkContains));

            int judgeScore = c.expectOutOfScope
                    ? callJudge(judgeClient, refusalPrompt(c.question, response.answer()))
                    : callJudge(judgeClient, answerPrompt(c.question, c.expectedFacts, response.answer()));

            results.add(new EvalResult(c.id, retrievalHit, judgeScore, latencyMs));
            System.out.printf("  %-4s hit=%-5s judge=%d  %dms%n", c.id, retrievalHit, judgeScore, latencyMs);
        }

        printSummary(runName, results);
        writeResultsFile(runName, results);
    }

    /**
     * Loads the evaluation cases from the classpath resource eval/dataset.json.
     *
     * @param mapper the JSON mapper
     * @return the dataset's cases
     * @throws Exception if the resource is missing or malformed
     */
    private static List<EvalCase> loadDataset(ObjectMapper mapper) throws Exception {
        try (InputStream in = EvalRunner.class.getClassLoader().getResourceAsStream("eval/dataset.json")) {
            return List.of(mapper.readValue(in, EvalCase[].class));
        }
    }

    /**
     * The parts of a query response the evaluation needs.
     *
     * @param answer           the generated answer text
     * @param citationContents the content of each cited chunk
     */
    private record QueryResponse(String answer, List<String> citationContents) {}

    /**
     * Sends a question to the blocking query endpoint as the evaluation tenant.
     *
     * @param http     the HTTP client
     * @param mapper   the JSON mapper
     * @param question the question to ask
     * @return the answer and cited chunk contents
     * @throws Exception if the request fails or the response is malformed
     */
    private static QueryResponse callQuery(HttpClient http, ObjectMapper mapper, String question) throws Exception {
        String body = mapper.writeValueAsString(Map.of("question", question));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE_URL + "/query"))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", TENANT_ID)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(response.body());

        String answer = json.get("answer").asText();
        List<String> citationContents = new ArrayList<>();
        json.get("citations").forEach(c -> citationContents.add(c.get("content").asText()));
        return new QueryResponse(answer, citationContents);
    }

    /**
     * Builds the judge prompt that scores an in-scope answer against its expected facts.
     *
     * @param question      the question asked
     * @param expectedFacts the facts a correct answer must cover
     * @param actualAnswer  the system's answer
     * @return the grading prompt
     */
    private static String answerPrompt(String question, List<String> expectedFacts, String actualAnswer) {
        return """
                You are grading a RAG system's answer. Score how well it covers the
                expected facts, from 1 (missing or wrong) to 5 (fully correct and complete).
                Respond with ONLY a single digit, no other text.

                Question: %s

                Expected facts:
                %s

                Answer given:
                %s
                """.formatted(question, String.join("\n", expectedFacts), actualAnswer);
    }

    /**
     * Builds the judge prompt that rewards declining an out-of-scope question and penalizes hallucination.
     *
     * @param question     the question asked
     * @param actualAnswer the system's answer
     * @return the grading prompt
     */
    private static String refusalPrompt(String question, String actualAnswer) {
        return """
                The correct behavior here is for the system to say it doesn't know,
                since the answer isn't in its knowledge base. Score 5 if the answer
                correctly declines or says it doesn't know. Score 1 if it confidently
                states something as fact (a hallucination). Respond with ONLY a single digit.

                Question: %s

                Answer given: %s
                """.formatted(question, actualAnswer);
    }

    /**
     * Sends a grading prompt to the judge model and parses the first digit of its reply.
     *
     * @param client the Bedrock client
     * @param prompt the grading prompt
     * @return the score, or 0 if the reply is unparseable so it is flagged for manual review rather than silently skewing the average
     */
    private static int callJudge(BedrockRuntimeClient client, String prompt) {
        Message message = Message.builder()
                .role(ConversationRole.USER)
                .content(ContentBlock.fromText(prompt))
                .build();
        ConverseResponse response = client.converse(ConverseRequest.builder()
                .modelId(JUDGE_MODEL_ID)
                .messages(message)
                .build());
        String text = response.output().message().content().get(0).text().trim();
        try {
            return Integer.parseInt(text.replaceAll("\\D", "").substring(0, 1));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Prints the run's hit rate, average judge score, and average latency.
     *
     * @param runName the run's name
     * @param results the per-case results
     */
    private static void printSummary(String runName, List<EvalResult> results) {
        long hits = results.stream().filter(EvalResult::retrievalHit).count();
        double hitRate = (double) hits / results.size();
        double avgJudge = results.stream().mapToInt(EvalResult::judgeScore).average().orElse(0);
        double avgLatency = results.stream().mapToLong(EvalResult::latencyMs).average().orElse(0);

        System.out.println();
        System.out.println("=== Eval run: " + runName + " ===");
        System.out.printf("Retrieval hit rate: %.1f%% (%d/%d)%n", hitRate * 100, hits, results.size());
        System.out.printf("Avg judge score:    %.2f / 5%n", avgJudge);
        System.out.printf("Avg latency:        %.0f ms%n", avgLatency);
    }

    /**
     * Writes the per-case results to eval-results/&lt;runName&gt;.csv.
     *
     * @param runName the run's name
     * @param results the per-case results
     * @throws Exception if the file cannot be written
     */
    private static void writeResultsFile(String runName, List<EvalResult> results) throws Exception {
        Path dir = Path.of("eval-results");
        Files.createDirectories(dir);
        Path file = dir.resolve(runName + ".csv");
        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            writer.write("id,retrievalHit,judgeScore,latencyMs\n");
            for (EvalResult r : results) {
                writer.write("%s,%b,%d,%d%n".formatted(r.id(), r.retrievalHit(), r.judgeScore(), r.latencyMs()));
            }
        }
        System.out.println("Results written to " + file.toAbsolutePath());
    }
}
