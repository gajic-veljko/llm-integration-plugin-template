# PR Comment Resolver - IntelliJ Plugin

An intelligent IntelliJ IDEA plugin that helps developers resolve GitHub PR review comments using AI assistance. Fetch comments from GitHub, view them with full context, and get AI-powered suggestions for fixes.

---

## 🎯 Features

### 📥 GitHub Integration
- **Fetch PR Comments** - Pull all inline review comments and discussion comments from any GitHub PR
- **Code Context** - Automatically fetches code snippets with surrounding lines for context
- **Review Status** - Shows overall review status (Approved, Changes Requested, Commented)
- **Smart Grouping** - Groups related comments by file and code snippet
- **Visual Indicators** - Indented display for grouped comments

### 🤖 AI-Powered Analysis
- **OpenAI Integration** - Uses GPT-4o to analyze comments and suggest fixes
- **Full File Context** - Reads the entire file locally for better AI understanding
- **Focused Solutions** - AI only modifies the specific lines mentioned in comments
- **Token Optimization** - Only uses local files (no GitHub fetching) to save API costs

### 🎨 Rich UI
- **Split View** - Comments list on the left, details on the right
- **Expandable Sections** - Comment, code context, AI analysis, and proposed code
- **Syntax Highlighting** - Code displayed with proper language highlighting
- **Filter Options** - View all comments, only inline, or only discussion comments
- **Copy to Clipboard** - Easy copying of code snippets with line numbers stripped
- **Edit Comments** - Modify comment text before sending to AI

---

## 📸 Screenshots

### Main Interface
- **Left Panel**: Filterable list of PR comments (grouped by code snippet)
- **Right Panel**: Comment details, code context, AI analysis, and proposed changes

### Comment Grouping
Comments about the same code snippet appear together with visual indentation:
```
#123 • alice • App.kt:45 • open
  ↳ bob • App.kt:47 • open
  ↳ charlie • App.kt:50 • open
```

---

## 🚀 Getting Started

### Prerequisites
- IntelliJ IDEA (2023.1 or later)
- GitHub Personal Access Token (optional for public repos, required for private)
- OpenAI API Key

### Installation

1. Clone this repository
2. Open in IntelliJ IDEA
3. Build the plugin: `./gradlew buildPlugin`
4. Install from disk: `Settings → Plugins → ⚙️ → Install Plugin from Disk`

### Configuration

#### 1. Set Up API Keys

**Via Settings UI** (Recommended):
- Go to `Settings → Tools → LLM Settings`
- Enter your **OpenAI API Key**
- Enter your **GitHub Token** (optional)

**Via Environment Variables** (Fallback):
```bash
export OPENAI_API_KEY="sk-proj-..."
export GITHUB_TOKEN="ghp_..."
```

#### 2. Create GitHub Token

1. Go to https://github.com/settings/tokens
2. Click "Generate new token (classic)"
3. Select scopes:
   - `public_repo` (for public repositories)
   - `repo` (for private repositories)
4. Copy the token and paste in plugin settings

---

## 📖 Usage

### Basic Workflow

1. **Open the Tool Window**
   - `View → Tool Windows → PR Comment Resolver`

2. **Fetch PR Comments**
   - Enter repository: `owner/repo` (e.g., `facebook/react`)
   - Enter PR number: `123`
   - Click **"Fetch Comments"**

3. **Filter Comments** (Optional)
   - Use dropdown to show: All / Inline / Discussion comments

4. **Select a Comment**
   - Click on any comment in the list
   - View comment details and code context on the right

5. **Get AI Analysis**
   - Click **"Resolve with AI"**
   - Wait for AI to analyze and propose a fix
   - Review the suggested changes

6. **Copy Proposed Code**
   - Click **"Copy"** button under the AI proposed code
   - Paste into your editor

### Advanced Features

#### Edit Comment Before Analysis
1. Select a comment
2. Click **"Edit"** button
3. Modify the comment text
4. Click **"Resolve with AI"** (automatically saves)

#### Expand Sections
- Click the expand icon (↗) on any section to view full-screen
- Click again to collapse

#### View Code Context
- Inline comments automatically show code snippets
- Lines are numbered for easy reference
- Target lines marked with `>>` prefix

---

## 🔧 How It Works

### 1. Fetching Comments

```kotlin
GitHubService.fetchPrSnapshot(owner, repo, prNumber)
```

The plugin:
- Calls GitHub API to get PR details
- Fetches all review comments (inline + discussion)
- Downloads code snippets at specific commits
- Computes overall review status
- Groups comments by file and code snippet

### 2. Local File Context

When you click "Resolve with AI", the plugin:
1. **Tries to find the file locally** in your IntelliJ project
2. **Reads the entire file** (if found)
3. **Sends full file to AI** for better context
4. **Falls back to snippet-only** if file not found (saves tokens)

### 3. AI Analysis

The AI receives:
- **Full file content** (for imports, class structure, dependencies)
- **Target lines** to focus on
- **Comment text** explaining what needs to change
- **PR context** (review status, other comments)

The AI returns:
- Brief explanation of the change
- OLD CODE (before)
- NEW CODE (after) - **only the modified lines**

---

## 💰 Token Usage & Cost

### Token Optimization
- ✅ **Local files only** - No GitHub fetching for token savings
- ✅ **Smart filtering** - Only sends full file if found locally
- ✅ **Focused prompts** - Instructs AI to modify specific lines only

### Cost Estimates (GPT-4o)

| Scenario | Input Tokens | Cost per Request |
|----------|--------------|------------------|
| Small file (100 lines) | ~400 | ~$0.001 |
| Medium file (500 lines) | ~2,000 | ~$0.005 |
| Large file (2000 lines) | ~8,000 | ~$0.020 |
| Snippet only (no file) | ~200 | ~$0.0005 |

**Tip:** The plugin logs token estimates in the console for each request.

### Console Logging

When full file context is used:
```
═══════════════════════════════════════════════════════
✅ FULL FILE CONTEXT ADDED TO AI PROMPT
═══════════════════════════════════════════════════════
📄 File: src/main/java/Example.java
📊 File size: 3456 characters (120 lines)
🎯 Target lines to modify: 45-52
💰 Estimated tokens: ~864
═══════════════════════════════════════════════════════
```

When file not found locally:
```
═══════════════════════════════════════════════════════
⚠️ FILE NOT FOUND LOCALLY - SNIPPET-ONLY MODE
═══════════════════════════════════════════════════════
📄 File: src/main/java/Example.java
📊 Snippet size: 245 characters
💰 Saving tokens by not fetching from GitHub
═══════════════════════════════════════════════════════
```

---

## 🎨 UI Components

### Comment List (Left Panel)
- **Icons** indicate comment type (inline vs discussion)
- **Visual grouping** with indentation for related comments
- **Status badges** (open, resolved, outdated)
- **Location info** (file:line)

### Details Panel (Right Panel)

1. **Comment Section**
   - Author and timestamp
   - Comment body (editable)
   - Edit/Save/Cancel buttons

2. **Code Context Section** (Inline comments only)
   - Syntax-highlighted code
   - Line numbers with target lines marked
   - Copy button

3. **AI Analysis Section** (After resolving)
   - Explanation of changes
   - Old code vs new code
   - Expand for full view

4. **AI Proposed Code Section**
   - Ready-to-use code fix
   - Copy button for easy pasting

---

## 🔍 Comment Grouping Logic

Comments are grouped when they:
1. Are in the **same file** (`filePath` matches)
2. Have the **same code snippet** (normalized for whitespace)
3. Are **consecutive** in the sorted list

### Sorting Order
1. **File path** (alphabetically)
2. **Code snippet** (normalized)
3. **Line number** (ascending)

### Visual Representation
```
App.kt:10    // First comment in group
  ↳ App.kt:12  // Grouped (same file + same snippet)
  ↳ App.kt:15  // Grouped (same file + same snippet)
App.kt:50    // Different snippet, not grouped
Utils.kt:20  // Different file, not grouped
```

---

## 🛠️ Development

### Project Structure
```
src/main/kotlin/com/intellij/ml/llm/template/
├── ui/
│   ├── PrResolverPanel.kt           # Main UI panel
│   ├── components/                  # Reusable UI components
│   └── layout/                      # Custom layouts
├── services/
│   ├── GitHubService.kt             # GitHub API integration
│   └── OpenAiService.kt             # OpenAI API integration
├── models/
│   └── PrComment.kt                 # Data models
└── settings/
    ├── LLMSettingsManager.kt        # Settings management
    └── LLMConfigurable.kt           # Settings UI
```

### Key Classes

**PrResolverPanel** - Main UI component with:
- Comment list with filtering
- Details panel with sections
- Button actions (Fetch, Resolve, Copy)
- Comment grouping logic

**GitHubService** - GitHub API wrapper:
- `fetchPrSnapshot()` - Main entry point
- `fetchFileAtCommit()` - Get file at specific commit
- `computeReviewSummary()` - Calculate overall status

**OpenAiService** - OpenAI integration:
- `analyzePrSnapshot()` - Send PR data to AI
- Uses Chat Completions API (gpt-4o)

### Building

```bash
# Build plugin
./gradlew buildPlugin

# Run in sandbox IDE
./gradlew runIde

# Run tests
./gradlew test
```

---

## 📋 Requirements

### Runtime
- JVM 17+
- IntelliJ IDEA 2023.1+

### Dependencies
- Gson (JSON parsing)
- IntelliJ Platform SDK
- Java HTTP Client (for API calls)

---

## 🐛 Troubleshooting

### "GitHub HTTP 401"
- Check your GitHub token is valid
- Ensure token has correct scopes (`repo` or `public_repo`)
- Try regenerating the token

### "OpenAI HTTP 401"
- Verify OpenAI API key is correct
- Check you have credits in your OpenAI account

### "File not found locally"
- Make sure the project corresponding to the PR is open in IntelliJ
- Check the file paths match between GitHub and your local project
- The plugin will still work with snippet-only context

### "No code context available"
- This happens for discussion comments (expected)
- Discussion comments don't have associated code
- Only inline review comments show code context

---

## 🔒 Security & Privacy

### API Keys
- Stored securely in IntelliJ's PasswordSafe
- **macOS**: Keychain
- **Windows**: Windows Credential Store
- **Linux**: KWallet/Secret Service
- Never committed to Git
- Not stored in plain text

### Data Handling
- PR data fetched via HTTPS
- No data stored permanently
- OpenAI requests sent over HTTPS
- Code snippets only sent when you click "Resolve"

---

## 🚧 Known Limitations

- Only supports GitHub (no GitLab/Bitbucket yet)
- Requires local file for full context (falls back to snippet-only)
- AI can only modify specific line ranges (by design)
- No automatic code application (manual copy-paste required)

---

## 🗺️ Roadmap

### Planned Features
- [ ] Auto-apply AI suggestions to code
- [ ] Support for GitLab and Bitbucket
- [ ] Multiple AI provider support (Claude, Gemini)
- [ ] Comment resolution workflow (mark as resolved)
- [ ] Diff view for proposed changes
- [ ] Batch processing of multiple comments
- [ ] Custom AI prompts/templates
- [ ] Comment reply directly from plugin

---

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.

---

## 🙏 Acknowledgments

- Built with [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/)
- Uses [OpenAI GPT-4o](https://openai.com/) for AI analysis
- Integrates with [GitHub API](https://docs.github.com/en/rest)

---

## 📧 Support

For issues, questions, or contributions:
- Open an issue on GitHub
- Check existing issues for solutions
- Contribute via pull requests

---

## 📊 Stats

- **Lines of Code**: ~1000
- **Main UI Class**: `PrResolverPanel.kt` (980 lines)
- **Services**: GitHub + OpenAI integration
- **Supported Languages**: Any language supported by GitHub

---

## 🎓 Learn More

### Useful Links
- [IntelliJ Plugin Development](https://plugins.jetbrains.com/docs/intellij/)
- [GitHub API Documentation](https://docs.github.com/en/rest)
- [OpenAI API Documentation](https://platform.openai.com/docs/)

---

**Made with ❤️ for developers who want to resolve PR comments faster!**

