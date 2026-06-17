package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestNormalizeStatusAliases(t *testing.T) {
	cases := map[string]string{
		"started":   "IN_PROGRESS",
		"running":   "IN_PROGRESS",
		"completed": "DONE",
		"finished":  "DONE",
		"blocked":   "BLOCKED",
	}

	for input, expected := range cases {
		if got := normalizeStatus(input); got != expected {
			t.Fatalf("normalizeStatus(%q) = %q, want %q", input, got, expected)
		}
	}
}

func TestFeatureIDUsesExplicitFlagBeforeEnvironment(t *testing.T) {
	t.Setenv("LIVING_SYNC_FEATURE_ID", "feature-from-env")

	if got := featureID(" feature-from-flag "); got != "feature-from-flag" {
		t.Fatalf("featureID explicit = %q, want feature-from-flag", got)
	}
}

func TestFeatureIDFallsBackToEnvironment(t *testing.T) {
	t.Setenv("LIVING_SYNC_FEATURE_ID", " feature-from-env ")

	if got := featureID(""); got != "feature-from-env" {
		t.Fatalf("featureID fallback = %q, want feature-from-env", got)
	}
}

func TestHookPayloadIgnoresMalformedJson(t *testing.T) {
	payload := hookPayload(strings.NewReader("not json {"))
	if len(payload) != 0 {
		t.Fatalf("hookPayload returned %#v, want empty map", payload)
	}
}

func TestChangedFilesCollectsToolInputPaths(t *testing.T) {
	payload := map[string]any{
		"tool_input": map[string]any{
			"file_path": "backend/src/main.go",
			"edits": []any{
				map[string]any{"file_path": "frontend/src/app.tsx"},
				map[string]any{"file_path": "backend/src/main.go"},
			},
		},
	}

	files := changedFiles(payload)
	expected := []string{"backend/src/main.go", "frontend/src/app.tsx"}
	if len(files) != len(expected) {
		t.Fatalf("changedFiles length = %d, want %d: %#v", len(files), len(expected), files)
	}
	for i := range expected {
		if files[i] != expected[i] {
			t.Fatalf("changedFiles[%d] = %q, want %q", i, files[i], expected[i])
		}
	}
}

func TestCommandExitCodeFindsNestedStringValue(t *testing.T) {
	payload := map[string]any{
		"tool_response": map[string]any{
			"result": map[string]any{"exit_code": "1"},
		},
	}

	if got := commandExitCode(payload); got != 1 {
		t.Fatalf("commandExitCode = %d, want 1", got)
	}
}

func TestPostJSONSupportsHTTPAndHTTPS(t *testing.T) {
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Fatalf("method = %s, want POST", r.Method)
		}
		if r.URL.Path != "/report-sync-note" {
			t.Fatalf("path = %s, want /report-sync-note", r.URL.Path)
		}
		if r.Header.Get("Content-Type") != "application/json" {
			t.Fatalf("Content-Type = %q, want application/json", r.Header.Get("Content-Type"))
		}
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte(`{"ok":true}`))
	})

	httpServer := httptest.NewServer(handler)
	defer httpServer.Close()

	httpBody, err := postJSON(httpServer.URL+"/report-sync-note", []byte(`{"message":"http"}`), time.Second)
	if err != nil {
		t.Fatalf("postJSON HTTP returned error: %v", err)
	}
	if string(httpBody) != `{"ok":true}` {
		t.Fatalf("HTTP body = %q, want ok response", string(httpBody))
	}

	httpsServer := httptest.NewTLSServer(handler)
	defer httpsServer.Close()
	originalTransport := http.DefaultTransport
	http.DefaultTransport = httpsServer.Client().Transport
	defer func() {
		http.DefaultTransport = originalTransport
	}()

	httpsBody, err := postJSON(httpsServer.URL+"/report-sync-note", []byte(`{"message":"https"}`), time.Second)
	if err != nil {
		t.Fatalf("postJSON HTTPS returned error: %v", err)
	}
	if string(httpsBody) != `{"ok":true}` {
		t.Fatalf("HTTPS body = %q, want ok response", string(httpsBody))
	}
}

func TestImportDoneMarkdownUsesProjectScopedRestEndpoint(t *testing.T) {
	var received map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("method = %s, want POST", r.Method)
			http.Error(w, "wrong method", http.StatusMethodNotAllowed)
			return
		}
		expectedPath := "/api/v1/projects/project-123/living-sync/mcp/import-feature-done-markdown"
		if r.URL.Path != expectedPath {
			t.Errorf("path = %s, want %s", r.URL.Path, expectedPath)
			http.Error(w, "wrong path", http.StatusNotFound)
			return
		}
		if err := json.NewDecoder(r.Body).Decode(&received); err != nil {
			t.Errorf("decode request body: %v", err)
			http.Error(w, "bad json", http.StatusBadRequest)
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"id":"event-1"}`))
	}))
	defer server.Close()

	t.Setenv("LIVING_SYNC_BASE_URL", server.URL+"/api/v1/projects/project-123/living-sync/mcp")
	t.Setenv("LIVING_SYNC_MCP_URL", server.URL+"/mcp")

	err := importDoneMarkdown(doneImportRequest{
		FeatureID: "feature-1",
		FileName:  "feature-1-done.md",
		Markdown:  "# Done",
	}, "codex")
	if err != nil {
		t.Fatalf("importDoneMarkdown returned error: %v", err)
	}
	if received["featureId"] != "feature-1" {
		t.Fatalf("featureId = %v, want feature-1", received["featureId"])
	}
	if received["fileName"] != "feature-1-done.md" {
		t.Fatalf("fileName = %v, want feature-1-done.md", received["fileName"])
	}
	if received["markdown"] != "# Done" {
		t.Fatalf("markdown = %v, want # Done", received["markdown"])
	}
	if received["agentName"] != "codex" {
		t.Fatalf("agentName = %v, want codex", received["agentName"])
	}
}

func TestCollectDoneImportsSkipsWhenFeatureMetadataMissing(t *testing.T) {
	projectDir := t.TempDir()
	donePath := filepath.Join(projectDir, "docs", "features", "51-feature-done-markdown-import-via-mcp-done.md")
	if err := os.MkdirAll(filepath.Dir(donePath), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(donePath, []byte("# Done\ncontent"), 0o644); err != nil {
		t.Fatal(err)
	}

	requests, err := collectDoneImports(projectDir, "", []string{donePath}, reporterState{})
	if err != nil {
		t.Fatalf("collectDoneImports returned error: %v", err)
	}
	if len(requests) != 0 {
		t.Fatalf("collectDoneImports returned %#v, want no imports without feature metadata", requests)
	}
}

func TestCollectDoneImportsSkipsAlreadyImportedDigest(t *testing.T) {
	projectDir := t.TempDir()
	featurePath := filepath.Join(projectDir, "docs", "features", "51-feature-done-markdown-import-via-mcp.md")
	donePath := filepath.Join(projectDir, "docs", "features", "51-feature-done-markdown-import-via-mcp-done.md")
	if err := os.MkdirAll(filepath.Dir(donePath), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(featurePath, []byte("---\nfeature_id: feature-51\n---\n# Feature 51\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	content := []byte("# Done\nsame content")
	if err := os.WriteFile(donePath, content, 0o644); err != nil {
		t.Fatal(err)
	}

	digest := doneImportDigest("feature-51", content)
	requests, err := collectDoneImports(projectDir, "", []string{donePath}, reporterState{
		ImportedDoneFiles: map[string]string{filepath.ToSlash(donePath): digest},
	})
	if err != nil {
		t.Fatalf("collectDoneImports returned error: %v", err)
	}
	if len(requests) != 0 {
		t.Fatalf("collectDoneImports returned %#v, want no imports for unchanged digest", requests)
	}
}

func TestCollectDoneImportsReturnsChangedDoneMarkdownOnce(t *testing.T) {
	projectDir := t.TempDir()
	featurePath := filepath.Join(projectDir, "docs", "features", "51-feature-done-markdown-import-via-mcp.md")
	donePath := filepath.Join(projectDir, "docs", "features", "51-feature-done-markdown-import-via-mcp-done.md")
	if err := os.MkdirAll(filepath.Dir(donePath), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(featurePath, []byte("---\nfeature_id: feature-51\n---\n# Feature 51\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	content := []byte("# Feature 51\nUpdated summary")
	if err := os.WriteFile(donePath, content, 0o644); err != nil {
		t.Fatal(err)
	}

	requests, err := collectDoneImports(projectDir, "", []string{donePath, donePath}, reporterState{})
	if err != nil {
		t.Fatalf("collectDoneImports returned error: %v", err)
	}
	if len(requests) != 1 {
		t.Fatalf("collectDoneImports length = %d, want 1: %#v", len(requests), requests)
	}
	if requests[0].FeatureID != "feature-51" {
		t.Fatalf("FeatureID = %q, want feature-51", requests[0].FeatureID)
	}
	if requests[0].FileName != "51-feature-done-markdown-import-via-mcp-done.md" {
		t.Fatalf("FileName = %q", requests[0].FileName)
	}
	if requests[0].Markdown != string(content) {
		t.Fatalf("Markdown = %q, want %q", requests[0].Markdown, string(content))
	}
}

func TestProjectIDFromLivingSyncBaseURL(t *testing.T) {
	projectID, err := projectIDFromLivingSyncBaseURL("http://localhost:8080/api/v1/projects/project-123/living-sync/mcp")
	if err != nil {
		t.Fatalf("projectIDFromLivingSyncBaseURL returned error: %v", err)
	}
	if projectID != "project-123" {
		t.Fatalf("projectID = %q, want project-123", projectID)
	}
}

func TestFeatureIDFromFeatureMarkdown(t *testing.T) {
	projectDir := t.TempDir()
	featurePath := filepath.Join(projectDir, "docs", "features", "51-feature-done-markdown-import-via-mcp.md")
	if err := os.MkdirAll(filepath.Dir(featurePath), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(featurePath, []byte("---\nfeature_id: 7f3d8d5d-abc\n---\n# Feature 51\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	featureID, err := featureIDFromFeatureMarkdown(featurePath)
	if err != nil {
		t.Fatalf("featureIDFromFeatureMarkdown returned error: %v", err)
	}
	if featureID != "7f3d8d5d-abc" {
		t.Fatalf("featureID = %q, want 7f3d8d5d-abc", featureID)
	}
}
