package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

var endpoints = map[string]string{
	"feature-progress":    "/report-feature-progress",
	"feature-done-import": "/import-feature-done-markdown",
	"test-run":            "/report-test-run",
	"token-usage":         "/report-token-usage",
	"code-changes":        "/report-code-changes",
	"sync-note":           "/report-sync-note",
}

var testCommandMarkers = []string{
	"gradlew test",
	"gradle test",
	"npm test",
	"npm run test",
	"npm run test:e2e",
	"npm run lint",
	"npm run build",
	"pnpm test",
	"pnpm run test",
	"pnpm run lint",
	"pnpm run build",
}

type stringList []string

type livingSyncConfig struct {
	LivingSyncBaseURL string `json:"livingSyncBaseUrl"`
	MCPURL            string `json:"mcpUrl"`
}

type reporterState struct {
	ImportedDoneFiles map[string]string `json:"importedDoneFiles"`
}

type doneImportRequest struct {
	FeatureID string
	FilePath  string
	FileName  string
	Markdown  string
	Digest    string
}

func (s *stringList) String() string {
	return strings.Join(*s, ",")
}

func (s *stringList) Set(value string) error {
	*s = append(*s, value)
	return nil
}

func main() {
	if len(os.Args) < 2 {
		usageAndExit()
	}

	var err error
	switch os.Args[1] {
	case "session-start":
		err = safePost("sync-note", map[string]any{"severity": "INFO", "message": "Agent session started."})
	case "stop":
		err = handleStop()
	case "post-tool-use":
		err = handlePostToolUse(os.Stdin)
	case "code-change":
		err = handleCodeChange(os.Stdin)
	case "feature-progress":
		err = handleFeatureProgress(os.Args[2:])
	case "feature-done-import":
		err = handleFeatureDoneImport(os.Args[2:])
	case "test-run":
		err = handleTestRun(os.Args[2:])
	case "code-changes":
		err = handleCodeChanges(os.Args[2:])
	case "token-usage":
		err = handleTokenUsage(os.Args[2:])
	case "sync-note":
		err = handleSyncNote(os.Args[2:])
	case "-h", "--help", "help":
		usageAndExit()
	default:
		err = fmt.Errorf("unknown command: %s", os.Args[1])
	}

	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func usageAndExit() {
	fmt.Fprintln(os.Stderr, "usage: living-sync-reporter <session-start|stop|post-tool-use|code-change|feature-progress|feature-done-import|test-run|code-changes|token-usage|sync-note> [flags]")
	os.Exit(2)
}

func syncConfig() (livingSyncConfig, error) {
	config := livingSyncConfig{
		LivingSyncBaseURL: strings.TrimRight(strings.TrimSpace(os.Getenv("LIVING_SYNC_BASE_URL")), "/"),
		MCPURL:            strings.TrimRight(strings.TrimSpace(os.Getenv("LIVING_SYNC_MCP_URL")), "/"),
	}
	projectDir, err := projectDir()
	if err != nil {
		return livingSyncConfig{}, err
	}
	configPath := os.Getenv("LIVING_SYNC_CONFIG")
	if configPath == "" {
		configPath = filepath.Join(projectDir, ".claude", "living-sync.json")
	}

	content, err := os.ReadFile(configPath)
	if err == nil {
		var fileConfig livingSyncConfig
		if err := json.Unmarshal(content, &fileConfig); err != nil {
			return livingSyncConfig{}, err
		}
		if config.LivingSyncBaseURL == "" {
			config.LivingSyncBaseURL = strings.TrimRight(strings.TrimSpace(fileConfig.LivingSyncBaseURL), "/")
		}
		if config.MCPURL == "" {
			config.MCPURL = strings.TrimRight(strings.TrimSpace(fileConfig.MCPURL), "/")
		}
	} else if config.LivingSyncBaseURL == "" || config.MCPURL == "" {
		return livingSyncConfig{}, err
	}

	if config.LivingSyncBaseURL == "" {
		return livingSyncConfig{}, errors.New("livingSyncBaseUrl missing in config")
	}
	if config.MCPURL == "" {
		derivedMCPURL, err := deriveMCPURL(config.LivingSyncBaseURL)
		if err != nil {
			return livingSyncConfig{}, err
		}
		config.MCPURL = derivedMCPURL
	}
	return config, nil
}

func baseURL() (string, error) {
	config, err := syncConfig()
	if err != nil {
		return "", err
	}
	return config.LivingSyncBaseURL, nil
}

func post(kind string, payload map[string]any) error {
	timeout := 2 * time.Second
	if raw := strings.TrimSpace(os.Getenv("LIVING_SYNC_TIMEOUT_SECONDS")); raw != "" {
		if seconds, err := strconv.ParseFloat(raw, 64); err == nil && seconds > 0 {
			timeout = time.Duration(seconds * float64(time.Second))
		}
	}
	return postWithTimeout(kind, payload, timeout)
}

func postWithTimeout(kind string, payload map[string]any, timeout time.Duration) error {
	suffix, ok := endpoints[kind]
	if !ok {
		return fmt.Errorf("unknown endpoint kind: %s", kind)
	}
	base, err := baseURL()
	if err != nil {
		return err
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}

	_, err = postJSON(base+suffix, body, timeout)
	return err
}

func postJSON(rawURL string, body []byte, timeout time.Duration) ([]byte, error) {
	parsed, err := url.Parse(rawURL)
	if err != nil {
		return nil, err
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return nil, fmt.Errorf("unsupported scheme: %s", parsed.Scheme)
	}
	request, err := http.NewRequest(http.MethodPost, rawURL, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Content-Type", "application/json")

	client := http.Client{Timeout: timeout}
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()

	responseBody, err := io.ReadAll(response.Body)
	if err != nil {
		return nil, err
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		message := strings.TrimSpace(string(responseBody))
		if message == "" {
			return nil, fmt.Errorf("HTTP %d", response.StatusCode)
		}
		return nil, fmt.Errorf("HTTP %d: %s", response.StatusCode, truncate(message, 240))
	}
	return responseBody, nil
}

func safePost(kind string, payload map[string]any) error {
	if err := post(kind, payload); err != nil {
		fmt.Fprintf(os.Stderr, "living-sync reporter skipped %s: %v\n", kind, err)
	}
	return nil
}

func normalizeStatus(value string) string {
	normalized := strings.ToUpper(strings.ReplaceAll(strings.TrimSpace(value), "-", "_"))
	switch normalized {
	case "STARTED", "START", "RUNNING":
		return "IN_PROGRESS"
	case "COMPLETED", "COMPLETE", "FINISHED":
		return "DONE"
	default:
		return normalized
	}
}

func hookPayload(reader io.Reader) map[string]any {
	raw, err := io.ReadAll(reader)
	if err != nil || strings.TrimSpace(string(raw)) == "" {
		return map[string]any{}
	}
	var payload map[string]any
	if err := json.Unmarshal(raw, &payload); err != nil {
		return map[string]any{}
	}
	return payload
}

func nested(data map[string]any, keys ...string) any {
	var current any = data
	for _, key := range keys {
		asMap, ok := current.(map[string]any)
		if !ok {
			return nil
		}
		current = asMap[key]
	}
	return current
}

func commandExitCode(data map[string]any) int {
	paths := [][]string{
		{"tool_response", "exit_code"},
		{"tool_response", "status"},
		{"tool_response", "code"},
		{"tool_response", "result", "exit_code"},
	}
	for _, path := range paths {
		switch value := nested(data, path...).(type) {
		case float64:
			return int(value)
		case int:
			return value
		case string:
			if parsed, err := strconv.Atoi(value); err == nil {
				return parsed
			}
		}
	}
	return 0
}

func changedFiles(data map[string]any) []string {
	toolInput, ok := data["tool_input"].(map[string]any)
	if !ok {
		return nil
	}
	seen := map[string]bool{}
	for _, key := range []string{"file_path", "path"} {
		if value, ok := toolInput[key].(string); ok && value != "" {
			seen[value] = true
		}
	}
	if edits, ok := toolInput["edits"].([]any); ok {
		for _, edit := range edits {
			if asMap, ok := edit.(map[string]any); ok {
				if value, ok := asMap["file_path"].(string); ok && value != "" {
					seen[value] = true
				}
			}
		}
	}
	files := make([]string, 0, len(seen))
	for file := range seen {
		files = append(files, file)
	}
	sort.Strings(files)
	return files
}

func handlePostToolUse(reader io.Reader) error {
	data := hookPayload(reader)
	command, ok := nested(data, "tool_input", "command").(string)
	if !ok {
		return nil
	}
	compactCommand := strings.Join(strings.Fields(command), " ")
	matches := false
	for _, marker := range testCommandMarkers {
		if strings.Contains(compactCommand, marker) {
			matches = true
			break
		}
	}
	if !matches {
		return nil
	}
	exitCode := commandExitCode(data)
	status := "passed"
	if exitCode != 0 {
		status = "failed"
	}
	return safePost("test-run", map[string]any{
		"command":   truncate(compactCommand, 240),
		"status":    status,
		"summary":   fmt.Sprintf("Command %s: %s", status, truncate(compactCommand, 160)),
		"passed":    boolInt(exitCode == 0),
		"failed":    boolInt(exitCode != 0),
		"featureId": featureID(""),
		"agentName": "claude-code",
	})
}

func handleCodeChange(reader io.Reader) error {
	files := changedFiles(hookPayload(reader))
	if len(files) == 0 {
		return nil
	}
	if err := safePost("code-changes", map[string]any{
		"summary":   "Files changed by coding agent.",
		"files":     files,
		"featureId": featureID(""),
		"agentName": "claude-code",
	}); err != nil {
		return err
	}
	return safeAutoImportDoneMarkdown(files)
}

func handleStop() error {
	if err := safePost("sync-note", map[string]any{"severity": "INFO", "message": "Agent turn completed."}); err != nil {
		return err
	}
	return safeAutoImportDoneMarkdown(nil)
}

func handleFeatureProgress(args []string) error {
	fs := flag.NewFlagSet("feature-progress", flag.ContinueOnError)
	task, agent := commonFlags(fs)
	feature := fs.String("feature", "", "")
	status := fs.String("status", "IN_PROGRESS", "")
	summary := fs.String("summary", "", "")
	var evidence stringList
	fs.Var(&evidence, "evidence", "")
	if err := fs.Parse(args); err != nil {
		return err
	}
	resolvedFeature := featureID(*feature)
	if resolvedFeature == "" || *summary == "" {
		return errors.New("feature-progress requires --feature and --summary")
	}
	return safePost("feature-progress", map[string]any{
		"featureId": resolvedFeature,
		"status":    normalizeStatus(*status),
		"summary":   *summary,
		"evidence":  []string(evidence),
		"taskId":    *task,
		"agentName": *agent,
	})
}

func handleFeatureDoneImport(args []string) error {
	fs := flag.NewFlagSet("feature-done-import", flag.ContinueOnError)
	featureID := fs.String("feature", "", "")
	filePath := fs.String("file", "", "")
	agentName := fs.String("agent", "claude-code", "")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *filePath == "" {
		return errors.New("feature-done-import requires --file")
	}

	projectDir, err := projectDir()
	if err != nil {
		return err
	}
	requests, err := collectDoneImports(projectDir, *featureID, []string{*filePath}, reporterState{})
	if err != nil {
		return err
	}
	if len(requests) != 1 {
		return fmt.Errorf("feature-done-import found %d importable files, want exactly 1", len(requests))
	}
	return importDoneMarkdown(requests[0], *agentName)
}

func handleTestRun(args []string) error {
	fs := flag.NewFlagSet("test-run", flag.ContinueOnError)
	task, agent := commonFlags(fs)
	feature := fs.String("feature", "", "")
	command := fs.String("command", "", "")
	status := fs.String("status", "", "")
	summary := fs.String("summary", "", "")
	passed := fs.Int("passed", 0, "")
	failed := fs.Int("failed", 0, "")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *command == "" || *status == "" || *summary == "" {
		return errors.New("test-run requires --command, --status, and --summary")
	}
	return safePost("test-run", map[string]any{
		"command":   *command,
		"status":    *status,
		"summary":   *summary,
		"passed":    *passed,
		"failed":    *failed,
		"featureId": featureID(*feature),
		"taskId":    *task,
		"agentName": *agent,
	})
}

func handleCodeChanges(args []string) error {
	fs := flag.NewFlagSet("code-changes", flag.ContinueOnError)
	task, agent := commonFlags(fs)
	feature := fs.String("feature", "", "")
	summary := fs.String("summary", "", "")
	var files stringList
	var commits stringList
	fs.Var(&files, "file", "")
	fs.Var(&commits, "commit", "")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *summary == "" {
		return errors.New("code-changes requires --summary")
	}
	return safePost("code-changes", map[string]any{
		"summary":   *summary,
		"files":     []string(files),
		"commits":   []string(commits),
		"featureId": featureID(*feature),
		"taskId":    *task,
		"agentName": *agent,
	})
}

func handleTokenUsage(args []string) error {
	fs := flag.NewFlagSet("token-usage", flag.ContinueOnError)
	task, agent := commonFlags(fs)
	feature := fs.String("feature", "", "")
	model := fs.String("model", "", "")
	inputTokens := fs.Int("input-tokens", 0, "")
	outputTokens := fs.Int("output-tokens", 0, "")
	totalTokens := fs.Int("total-tokens", -1, "")
	summary := fs.String("summary", "Token usage reported.", "")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *model == "" {
		return errors.New("token-usage requires --model")
	}
	total := *totalTokens
	if total < 0 {
		total = *inputTokens + *outputTokens
	}
	return safePost("token-usage", map[string]any{
		"agentName":    *agent,
		"model":        *model,
		"inputTokens":  *inputTokens,
		"outputTokens": *outputTokens,
		"totalTokens":  total,
		"summary":      *summary,
		"featureId":    featureID(*feature),
		"taskId":       *task,
	})
}

func handleSyncNote(args []string) error {
	fs := flag.NewFlagSet("sync-note", flag.ContinueOnError)
	task, agent := commonFlags(fs)
	feature := fs.String("feature", "", "")
	severity := fs.String("severity", "INFO", "")
	message := fs.String("message", "", "")
	suggestedAction := fs.String("suggested-action", "", "")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *message == "" {
		return errors.New("sync-note requires --message")
	}
	return safePost("sync-note", map[string]any{
		"severity":        *severity,
		"message":         *message,
		"suggestedAction": *suggestedAction,
		"featureId":       featureID(*feature),
		"taskId":          *task,
		"agentName":       *agent,
	})
}

func safeAutoImportDoneMarkdown(candidates []string) error {
	projectDir, err := projectDir()
	if err != nil {
		fmt.Fprintf(os.Stderr, "living-sync reporter skipped done import: %v\n", err)
		return nil
	}
	if len(candidates) == 0 {
		candidates, err = scanDoneMarkdownFiles(projectDir)
		if err != nil {
			fmt.Fprintf(os.Stderr, "living-sync reporter skipped done import scan: %v\n", err)
			return nil
		}
	}

	doneCandidates := doneMarkdownCandidates(projectDir, candidates)
	if len(doneCandidates) == 0 {
		return nil
	}

	state, err := loadReporterState(projectDir)
	if err != nil {
		fmt.Fprintf(os.Stderr, "living-sync reporter skipped done import state load: %v\n", err)
		return nil
	}
	requests, err := collectDoneImports(projectDir, "", doneCandidates, state)
	if err != nil {
		fmt.Fprintf(os.Stderr, "living-sync reporter skipped done import: %v\n", err)
		return nil
	}
	if len(requests) == 0 {
		return nil
	}

	if state.ImportedDoneFiles == nil {
		state.ImportedDoneFiles = map[string]string{}
	}
	didImport := false
	for _, request := range requests {
		if err := importDoneMarkdown(request, "claude-code"); err != nil {
			fmt.Fprintf(os.Stderr, "living-sync reporter skipped done import for %s: %v\n", request.FilePath, err)
			continue
		}
		state.ImportedDoneFiles[request.FilePath] = request.Digest
		didImport = true
	}
	if didImport {
		if err := saveReporterState(projectDir, state); err != nil {
			fmt.Fprintf(os.Stderr, "living-sync reporter skipped done import state save: %v\n", err)
		}
	}
	return nil
}

func importDoneMarkdown(request doneImportRequest, agentName string) error {
	return postWithTimeout("feature-done-import", map[string]any{
		"featureId": request.FeatureID,
		"fileName":  request.FileName,
		"markdown":  request.Markdown,
		"agentName": agentName,
	}, 5*time.Second)
}

func collectDoneImports(projectDir string, featureID string, candidates []string, state reporterState) ([]doneImportRequest, error) {
	imported := state.ImportedDoneFiles
	if imported == nil {
		imported = map[string]string{}
	}
	paths := doneMarkdownCandidates(projectDir, candidates)
	requests := make([]doneImportRequest, 0, len(paths))
	for _, path := range paths {
		resolvedFeatureID := strings.TrimSpace(featureID)
		if resolvedFeatureID == "" {
			var err error
			resolvedFeatureID, err = featureIDForDoneMarkdown(path)
			if err != nil {
				fmt.Fprintf(os.Stderr, "living-sync reporter skipped done import for %s: %v\n", path, err)
				continue
			}
		}
		content, err := os.ReadFile(path)
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			return nil, err
		}
		digest := doneImportDigest(resolvedFeatureID, content)
		normalizedPath := filepath.ToSlash(path)
		if imported[normalizedPath] == digest {
			continue
		}
		requests = append(requests, doneImportRequest{
			FeatureID: resolvedFeatureID,
			FilePath:  normalizedPath,
			FileName:  filepath.Base(path),
			Markdown:  string(content),
			Digest:    digest,
		})
	}
	return requests, nil
}

func doneMarkdownCandidates(projectDir string, candidates []string) []string {
	seen := map[string]bool{}
	files := make([]string, 0, len(candidates))
	for _, candidate := range candidates {
		candidate = strings.TrimSpace(candidate)
		if candidate == "" {
			continue
		}
		resolved := candidate
		if !filepath.IsAbs(resolved) {
			resolved = filepath.Join(projectDir, resolved)
		}
		resolved = filepath.Clean(resolved)
		if !strings.HasSuffix(strings.ToLower(filepath.Base(resolved)), "-done.md") {
			continue
		}
		normalized := filepath.ToSlash(resolved)
		if seen[normalized] {
			continue
		}
		seen[normalized] = true
		files = append(files, resolved)
	}
	sort.Strings(files)
	return files
}

func scanDoneMarkdownFiles(projectDir string) ([]string, error) {
	files := make([]string, 0)
	err := filepath.WalkDir(projectDir, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if entry.IsDir() {
			switch entry.Name() {
			case ".git", "node_modules", ".next":
				return filepath.SkipDir
			}
			return nil
		}
		if strings.HasSuffix(strings.ToLower(entry.Name()), "-done.md") {
			files = append(files, path)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	sort.Strings(files)
	return files, nil
}

func loadReporterState(projectDir string) (reporterState, error) {
	path := reporterStatePath(projectDir)
	content, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return reporterState{ImportedDoneFiles: map[string]string{}}, nil
	}
	if err != nil {
		return reporterState{}, err
	}
	var state reporterState
	if err := json.Unmarshal(content, &state); err != nil {
		return reporterState{}, err
	}
	if state.ImportedDoneFiles == nil {
		state.ImportedDoneFiles = map[string]string{}
	}
	return state, nil
}

func saveReporterState(projectDir string, state reporterState) error {
	if state.ImportedDoneFiles == nil {
		state.ImportedDoneFiles = map[string]string{}
	}
	path := reporterStatePath(projectDir)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	content, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, content, 0o644)
}

func reporterStatePath(projectDir string) string {
	return filepath.Join(projectDir, ".asset-bundles", "living-sync-reporter-state.json")
}

func featureIDForDoneMarkdown(donePath string) (string, error) {
	featurePath := strings.TrimSuffix(donePath, "-done.md") + ".md"
	return featureIDFromFeatureMarkdown(featurePath)
}

func featureIDFromFeatureMarkdown(featurePath string) (string, error) {
	content, err := os.ReadFile(featurePath)
	if err != nil {
		return "", err
	}
	lines := strings.Split(string(content), "\n")
	if len(lines) == 0 || strings.TrimSpace(lines[0]) != "---" {
		return "", fmt.Errorf("feature markdown missing frontmatter: %s", featurePath)
	}
	for _, line := range lines[1:] {
		trimmed := strings.TrimSpace(line)
		if trimmed == "---" {
			break
		}
		if strings.HasPrefix(trimmed, "feature_id:") {
			value := strings.TrimSpace(strings.TrimPrefix(trimmed, "feature_id:"))
			if value == "" {
				return "", fmt.Errorf("feature_id is empty in %s", featurePath)
			}
			return value, nil
		}
	}
	return "", fmt.Errorf("feature_id missing in %s", featurePath)
}

func deriveMCPURL(livingSyncBaseURL string) (string, error) {
	parsed, err := url.Parse(livingSyncBaseURL)
	if err != nil {
		return "", err
	}
	prefix := "/api/v1/projects/"
	index := strings.Index(parsed.Path, prefix)
	if index < 0 {
		return "", fmt.Errorf("cannot derive mcpUrl from livingSyncBaseUrl: %s", livingSyncBaseURL)
	}
	parsed.Path = "/mcp"
	parsed.RawPath = ""
	parsed.RawQuery = ""
	parsed.Fragment = ""
	return strings.TrimRight(parsed.String(), "/"), nil
}

func projectIDFromLivingSyncBaseURL(livingSyncBaseURL string) (string, error) {
	parsed, err := url.Parse(livingSyncBaseURL)
	if err != nil {
		return "", err
	}
	segments := strings.Split(strings.Trim(parsed.Path, "/"), "/")
	for index := 0; index < len(segments)-1; index++ {
		if segments[index] == "projects" && index+1 < len(segments) {
			if segments[index+1] != "" {
				return segments[index+1], nil
			}
			break
		}
	}
	return "", fmt.Errorf("projectId missing in livingSyncBaseUrl: %s", livingSyncBaseURL)
}

func projectDir() (string, error) {
	if value := os.Getenv("CLAUDE_PROJECT_DIR"); value != "" {
		return value, nil
	}
	return os.Getwd()
}

func doneImportDigest(featureID string, content []byte) string {
	sum := sha256.Sum256(append([]byte(featureID+"\n"), content...))
	return fmt.Sprintf("%x", sum)
}

func commonFlags(fs *flag.FlagSet) (*string, *string) {
	task := fs.String("task", "", "")
	agent := fs.String("agent", "claude-code", "")
	return task, agent
}

func featureID(flagValue string) string {
	if value := strings.TrimSpace(flagValue); value != "" {
		return value
	}
	return strings.TrimSpace(os.Getenv("LIVING_SYNC_FEATURE_ID"))
}

func boolInt(value bool) int {
	if value {
		return 1
	}
	return 0
}

func truncate(value string, max int) string {
	if len(value) <= max {
		return value
	}
	return value[:max]
}
