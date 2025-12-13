package com.intellij.ml.llm.template.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// -----------------------------
// OpenAI caller (Responses API)
// -----------------------------
class OpenAiService(
    private val openAiApiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1"
) {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private val gson = Gson()

    /**
     * Call this on your "Analyze" button.
     * You pass in the snapshot from GitHubService + any user prompt.
     */
    fun analyzePrSnapshot(
        snapshot: PrSnapshot,
        model: String = "gpt-4o",
        instructions: String = """
      You are a code review assistant.
      Use the provided PR snapshot: PR status, review status, discussion comments, inline comments and code snippets.
      Produce:
      1) Short PR summary
      2) List of required code changes (file + line range + what to change)
      3) Risky parts / edge cases
    """.trimIndent(),
        userPrompt: String = "Review this PR and propose fixes."
    ): String {
        val snapshotJson = gson.toJson(snapshot)

        val bodyMap = linkedMapOf<String, Any>(
            "model" to model,
            "messages" to listOf(
                mapOf(
                    "role" to "system",
                    "content" to instructions
                ),
                mapOf(
                    "role" to "user",
                    "content" to """
                        $userPrompt

                        PR_SNAPSHOT_JSON:
                        $snapshotJson
                    """.trimIndent()
                )
            ),
            "temperature" to 0.7
        )

        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/chat/completions"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $openAiApiKey")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(bodyMap)))
            .build()

        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() !in 200..299) {
            throw RuntimeException("OpenAI HTTP ${res.statusCode()} :: ${res.body()}")
        }

        val root: Map<String, Any?> = gson.fromJson(res.body(), object : TypeToken<Map<String, Any?>>() {}.type)
        return extractChatCompletionText(root) ?: res.body()
    }

    /**
     * Extracts text from Chat Completions API response.
     */
    private fun extractChatCompletionText(root: Map<String, Any?>): String? {
        val choices = root["choices"] as? List<*> ?: return null
        if (choices.isEmpty()) return null

        val firstChoice = choices[0] as? Map<*, *> ?: return null
        val message = firstChoice["message"] as? Map<*, *> ?: return null
        val content = message["content"] as? String

        return content?.trim()
    }
}

