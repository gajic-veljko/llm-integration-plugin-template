@file:Suppress("MemberVisibilityCanBePrivate")

package com.intellij.ml.llm.template.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

// -----------------------------
// Data model (object you send to OpenAI)
// -----------------------------
data class PrSnapshot(
    val repo: String,
    val pr: PrInfo,
    val reviewSummary: ReviewSummary,
    val discussionComments: List<IssueComment>,
    val inlineComments: List<InlineReviewComment>
)

data class PrInfo(
    val number: Int,
    val title: String?,
    val url: String?,
    val author: String?,
    val state: String?,          // open / closed
    val merged: Boolean,
    val createdAt: String?,
    val updatedAt: String?,
    val headRef: String?,
    val baseRef: String?,
    val additions: Int?,
    val deletions: Int?,
    val changedFiles: Int?
)

data class ReviewSummary(
    val overall: String, // APPROVED / CHANGES_REQUESTED / COMMENTED / NO_REVIEWS
    val byUser: List<PerUserReviewState>
)

data class PerUserReviewState(
    val user: String,
    val state: String?,
    val submittedAt: String?,
    val reviewId: Long?
)

data class IssueComment(
    val id: Long,
    val user: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val url: String?,
    val body: String?
)

data class InlineReviewComment(
    val id: Long,
    val user: String?,
    val url: String?,
    val body: String?,
    val path: String?,
    val side: String?,       // RIGHT/LEFT
    val startLine: Int?,     // "start_line"
    val line: Int?,          // "line"
    val position: Int?,      // older diff position (fallback)
    val commitId: String?,
    val diffHunk: String?,
    val snippet: String?,    // highlighted snippet (if we could fetch file)
    val snippetError: String?,
    val createdAt: String?   // timestamp
)

// -----------------------------
// GitHub fetcher
// -----------------------------
class GitHubService(
    private val githubToken: String? = null,
    private val apiBase: String = "https://api.github.com",
    private val rawBase: String = "https://raw.githubusercontent.com"
) {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private val gson = Gson()

    /** Main function you call on "Fetch" button */
    fun fetchPrSnapshot(owner: String, repo: String, prNumber: Int, snippetContextLines: Int = 3): PrSnapshot {
        val prDetail = getMap("/repos/${enc(owner)}/${enc(repo)}/pulls/$prNumber")

        val reviews = listAll("/repos/${enc(owner)}/${enc(repo)}/pulls/$prNumber/reviews")
        val reviewSummary = computeReviewSummary(reviews)

        val issueCommentsRaw = listAll("/repos/${enc(owner)}/${enc(repo)}/issues/$prNumber/comments")
        val discussionComments = issueCommentsRaw.map { toIssueComment(it) }

        val inlineCommentsRaw = listAll("/repos/${enc(owner)}/${enc(repo)}/pulls/$prNumber/comments")
        val inlineComments = inlineCommentsRaw.map { toInlineReviewComment(owner, repo, it, snippetContextLines) }

        val prInfo = PrInfo(
            number = prNumber,
            title = prDetail["title"] as? String,
            url = prDetail["html_url"] as? String,
            author = (prDetail["user"] as? Map<*, *>)?.get("login") as? String,
            state = prDetail["state"] as? String,
            merged = prDetail["merged_at"] != null,
            createdAt = prDetail["created_at"] as? String,
            updatedAt = prDetail["updated_at"] as? String,
            headRef = (prDetail["head"] as? Map<*, *>)?.get("ref") as? String,
            baseRef = (prDetail["base"] as? Map<*, *>)?.get("ref") as? String,
            additions = (prDetail["additions"] as? Number)?.toInt(),
            deletions = (prDetail["deletions"] as? Number)?.toInt(),
            changedFiles = (prDetail["changed_files"] as? Number)?.toInt()
        )

        return PrSnapshot(
            repo = "$owner/$repo",
            pr = prInfo,
            reviewSummary = reviewSummary,
            discussionComments = discussionComments,
            inlineComments = inlineComments
        )
    }

    // ---------- GitHub calls ----------

    private fun getMap(path: String): Map<String, Any?> {
        val json = getJson(path)
        return gson.fromJson(json, object : TypeToken<Map<String, Any?>>() {}.type)
    }

    /** Paginates until empty */
    private fun listAll(path: String, perPage: Int = 100): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        var page = 1
        while (true) {
            val json = getJson("$path?per_page=$perPage&page=$page")
            val arr: List<Map<String, Any?>> = gson.fromJson(
                json,
                object : TypeToken<List<Map<String, Any?>>>() {}.type
            )
            if (arr.isEmpty()) break
            out += arr
            if (arr.size < perPage) break
            page++
        }
        return out
    }

    private fun getJson(pathOrUrl: String): String {
        val url = if (pathOrUrl.startsWith("http")) pathOrUrl else "$apiBase$pathOrUrl"

        val reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(40))
            .GET()

        // headers for GitHub API JSON
        if (url.startsWith(apiBase)) {
            reqBuilder.header("Accept", "application/vnd.github+json")
            reqBuilder.header("X-GitHub-Api-Version", "2022-11-28")
        } else {
            reqBuilder.header("Accept", "*/*")
        }

        if (!githubToken.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer ${githubToken.trim()}")
        }

        val res = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() !in 200..299) {
            throw RuntimeException("GitHub HTTP ${res.statusCode()} for $url :: ${res.body()}")
        }
        return res.body()
    }

    // ---------- Comment mapping + snippets ----------

    private fun toIssueComment(m: Map<String, Any?>): IssueComment {
        val user = (m["user"] as? Map<*, *>)?.get("login") as? String
        return IssueComment(
            id = (m["id"] as Number).toLong(),
            user = user,
            createdAt = m["created_at"] as? String,
            updatedAt = m["updated_at"] as? String,
            url = m["html_url"] as? String,
            body = m["body"] as? String
        )
    }

    private fun toInlineReviewComment(owner: String, repo: String, m: Map<String, Any?>, ctx: Int): InlineReviewComment {
        val id = (m["id"] as Number).toLong()
        val user = (m["user"] as? Map<*, *>)?.get("login") as? String

        val path = m["path"] as? String
        val line = (m["line"] as? Number)?.toInt()
        val startLine = (m["start_line"] as? Number)?.toInt()
        val commitId = m["commit_id"] as? String
        val position = (m["position"] as? Number)?.toInt()

        var snippet: String? = null
        var snippetError: String? = null

        if (!path.isNullOrBlank() && !commitId.isNullOrBlank() && line != null) {
            val from = startLine ?: line
            val to = line
            try {
                val fileText = fetchFileAtCommit(owner, repo, path, commitId)
                snippet = buildHighlightedSnippet(fileText, from, to, ctx)
            } catch (e: Exception) {
                snippetError = e.message ?: e.toString()
            }
        } else {
            snippetError = "Missing path/commit_id/line (position=$position)"
        }

        return InlineReviewComment(
            id = id,
            user = user,
            url = m["html_url"] as? String,
            body = m["body"] as? String,
            path = path,
            side = m["side"] as? String,
            startLine = startLine,
            line = line,
            position = position,
            commitId = commitId,
            diffHunk = m["diff_hunk"] as? String,
            snippet = snippet,
            createdAt = m["created_at"] as? String,
            snippetError = snippetError
        )
    }

    private fun fetchFileAtCommit(owner: String, repo: String, path: String, commitSha: String): String {
        // 1) Contents API (base64 JSON)
        val contentsJson = try {
            getJson("/repos/${enc(owner)}/${enc(repo)}/contents/${pathEncode(path)}?ref=${enc(commitSha)}")
        } catch (_: Exception) {
            null
        }

        if (contentsJson != null) {
            val obj: Map<String, Any?> = gson.fromJson(contentsJson, object : TypeToken<Map<String, Any?>>() {}.type)
            val encoding = obj["encoding"] as? String
            val content = obj["content"] as? String
            if (encoding.equals("base64", ignoreCase = true) && content != null) {
                val cleaned = content.replace("\n", "").replace("\r", "")
                val decoded = Base64.getDecoder().decode(cleaned)
                return String(decoded, StandardCharsets.UTF_8)
            }
        }

        // 2) raw.githubusercontent fallback
        val rawUrl = "$rawBase/${enc(owner)}/${enc(repo)}/${enc(commitSha)}/${pathEncode(path)}"
        return getJson(rawUrl)
    }

    private fun buildHighlightedSnippet(fileText: String, fromLine1: Int, toLine1: Int, context: Int): String {
        val lines = fileText.split("\n")
        val n = lines.size

        val from = kotlin.math.max(1, kotlin.math.min(fromLine1, toLine1))
        val to = kotlin.math.max(fromLine1, toLine1)

        val start = kotlin.math.max(1, from - context)
        val end = kotlin.math.min(n, to + context)

        val sb = StringBuilder()
        for (i in start..end) {
            val inRange = i in from..to
            val prefix = if (inRange) ">> " else "   "
            sb.append(String.format("%s%5d | %s%n", prefix, i, lines[i - 1]))
        }
        return sb.toString()
    }

    // ---------- Reviews -> summary ----------

    private fun computeReviewSummary(reviews: List<Map<String, Any?>>): ReviewSummary {
        if (reviews.isEmpty()) return ReviewSummary("NO_REVIEWS", emptyList())

        // latest review per user by submitted_at (ISO string compare works)
        val latestByUser = linkedMapOf<String, Map<String, Any?>>()

        fun isAfter(a: Map<String, Any?>, b: Map<String, Any?>): Boolean {
            val at = a["submitted_at"] as? String
            val bt = b["submitted_at"] as? String
            return when {
                at != null && bt != null -> at > bt
                at != null && bt == null -> true
                at == null && bt != null -> false
                else -> ((a["id"] as? Number)?.toLong() ?: 0L) > ((b["id"] as? Number)?.toLong() ?: 0L)
            }
        }

        for (r in reviews) {
            val user = (r["user"] as? Map<*, *>)?.get("login") as? String ?: continue
            val prev = latestByUser[user]
            if (prev == null || isAfter(r, prev)) latestByUser[user] = r
        }

        val perUser = mutableListOf<PerUserReviewState>()
        var anyApproved = false
        var anyCommented = false

        for ((user, r) in latestByUser) {
            val state = r["state"] as? String
            val submittedAt = r["submitted_at"] as? String
            val reviewId = (r["id"] as? Number)?.toLong()

            perUser += PerUserReviewState(user, state, submittedAt, reviewId)

            if (state == null) continue
            if (state.equals("DISMISSED", ignoreCase = true)) continue
            if (state.equals("CHANGES_REQUESTED", ignoreCase = true)) {
                return ReviewSummary("CHANGES_REQUESTED", perUser)
            }
            if (state.equals("APPROVED", ignoreCase = true)) anyApproved = true
            if (state.equals("COMMENTED", ignoreCase = true)) anyCommented = true
        }

        val overall = when {
            anyApproved -> "APPROVED"
            anyCommented -> "COMMENTED"
            else -> "NO_REVIEWS"
        }
        return ReviewSummary(overall, perUser)
    }

    // ---------- encoding helpers ----------
    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

    private fun pathEncode(path: String): String =
        path.split("/").joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8) }
}

