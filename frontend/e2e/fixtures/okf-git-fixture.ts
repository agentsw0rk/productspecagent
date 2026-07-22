import {
  mkdtempSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, relative } from "node:path";
import { execFileSync } from "node:child_process";
import { pathToFileURL } from "node:url";

const CANONICAL_BRANCH = "productspec/okf";

export interface OkfRemoteFixture {
  remoteUrl: string;
  cloneCanonical(): string;
  cleanup(): void;
}

function git(cwd: string, args: string[]) {
  execFileSync("git", args, { cwd, stdio: "pipe" });
}

function configureIdentity(cwd: string) {
  git(cwd, ["config", "user.email", "e2e@example.com"]);
  git(cwd, ["config", "user.name", "ProductSpecAgent E2E"]);
}

export function createOkfRemote(): OkfRemoteFixture {
  const root = mkdtempSync(join(tmpdir(), "productspec-okf-e2e-"));
  const bare = join(root, "remote.git");
  const seed = join(root, "seed");
  let cloneSequence = 0;

  git(root, ["init", "--bare", bare]);
  mkdirSync(seed);
  git(seed, ["init", "-b", "main"]);
  configureIdentity(seed);
  writeFileSync(join(seed, "README.md"), "# OKF E2E\n");
  git(seed, ["add", "README.md"]);
  git(seed, ["commit", "-m", "test: seed remote"]);
  git(seed, ["remote", "add", "origin", pathToFileURL(bare).toString()]);
  git(seed, ["push", "-u", "origin", "main"]);

  return {
    remoteUrl: pathToFileURL(bare).toString(),
    cloneCanonical() {
      const clone = join(root, `external-${++cloneSequence}`);
      git(root, ["clone", "--branch", CANONICAL_BRANCH, pathToFileURL(bare).toString(), clone]);
      configureIdentity(clone);
      return clone;
    },
    cleanup() {
      rmSync(root, { recursive: true, force: true });
    },
  };
}

export function commitFeatureTitle(
  repository: string,
  currentTitle: string,
  nextTitle: string,
  message: string,
): string {
  const featureFile = findFeatureDocument(repository, currentTitle);

  const content = readFileSync(featureFile, "utf8");
  const updated = content.replace(/^title:.*$/m, `title: ${nextTitle}`);
  if (updated === content) throw new Error(`feature title field not found: ${featureFile}`);
  writeFileSync(featureFile, updated);
  git(repository, ["add", relative(repository, featureFile)]);
  git(repository, ["commit", "-m", message]);
  git(repository, ["push", "origin", `HEAD:${CANONICAL_BRANCH}`]);
  return featureFile;
}

export function readFeatureDocument(repository: string, title: string): string {
  return readFileSync(findFeatureDocument(repository, title), "utf8");
}

function findFeatureDocument(repository: string, title: string): string {
  const featureDirectory = join(repository, "docs", "okf", "features");
  const featureFile = readdirSync(featureDirectory)
    .filter((name) => name.endsWith(".md") && name !== "index.md")
    .map((name) => join(featureDirectory, name))
    .find((path) => readFileSync(path, "utf8").includes(title));
  if (!featureFile) throw new Error(`feature document not found: ${title}`);
  return featureFile;
}
