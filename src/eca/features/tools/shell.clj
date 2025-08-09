(ns eca.features.tools.shell
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.shared :as shared :refer [multi-str]]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[TOOLS-SHELL]")

(defn ^:private shell-command [arguments {:keys [db config]}]
  (let [command-args (get arguments "command")
        command (first (string/split command-args #"\s+"))
        user-work-dir (get arguments "working_directory")
        exclude-cmds (-> config :nativeTools :shell :excludeCommands set)]
    (or (tools.util/invalid-arguments arguments [["working_directory" #(or (nil? %)
                                                                           (fs/exists? %)) "working directory $working_directory does not exist"]
                                                 ["commmand" (constantly (not (contains? exclude-cmds command))) (format "Cannot run command '%s' because it is excluded by eca config."
                                                                                                                         command)]])
        (let [work-dir (or (some-> user-work-dir fs/canonicalize str)
                           (some-> (:workspace-folders db)
                                   first
                                   :uri
                                   shared/uri->filename)
                           (config/get-property "user.home"))
              _ (logger/debug logger-tag "Running command:" command-args)
              result (try
                       (p/shell {:dir work-dir
                                 :out :string
                                 :err :string
                                 :continue true} "bash -c" command-args)
                       (catch Exception e
                         {:exit 1 :err (.getMessage e)}))
              err (some-> (:err result) string/trim)
              out (some-> (:out result) string/trim)]
          (logger/debug logger-tag "Command executed:" result)
          (if (zero? (:exit result))
            (tools.util/single-text-content (:out result))
            {:error true
             :contents (remove nil?
                               (concat [{:type :text
                                         :text (str "Exit code " (:exit result))}]
                                       (when-not (string/blank? err)
                                         [{:type :text
                                           :text (str "Stderr:\n" err)}])
                                       (when-not (string/blank? out)
                                         [{:type :text
                                           :text (str "Stdout:\n" out)}])))})))))

(defn shell-command-summary [args]
  (if-let [command (get args "command")]
    (if (> (count command) 20)
      (format "Running '%s...'" (subs command 0 20))
      (format "Running '%s'" command))
    "Running shell command"))

(def definitions
  {"eca_shell_command"
   {:description (multi-str "Execute an arbitrary shell command and return the output.
1. Command Execution:
  - Always quote file paths that contain spaces with double quotes (e.g., cd \" path with spaces/file.txt \")
  - Examples of proper quoting:
    - cd \"/Users/name/My Documents\" (correct)
    - cd /Users/name/My Documents (incorrect - will fail)
    - python \"/path/with spaces/script.py\" (correct)
    - python /path/with spaces/script.py (incorrect - will fail)
  - After ensuring proper quoting, execute the command.
  - Capture the output of the command.
  - VERY IMPORTANT: You MUST avoid using search command `grep`. Instead use eca_grep to search. You MUST avoid read tools like `cat`, `head`, `tail`, and `ls`, and use eca_read_file or eca_directory_tree.

# Committing changes with git

When the user asks you to create a new git commit, follow these steps carefully:

1.:
   - Run a git status command to see all untracked files.
   - Run a git diff command to see both staged and unstaged changes that will be committed.
   - Run a git log command to see recent commit messages, so that you can follow this repository's commit message style.

2. Analyze all staged changes (both previously staged and newly added) and draft a commit message. Wrap your analysis process in <commit_analysis> tags:

<commit_analysis>
- List the files that have been changed or added
- Summarize the nature of the changes (eg. new feature, enhancement to an existing feature, bug fix, refactoring, test, docs, etc.)
- Brainstorm the purpose or motivation behind these changes
- Assess the impact of these changes on the overall project
- Check for any sensitive information that shouldn't be committed
- Draft a concise (1-2 sentences) commit message that focuses on the \"why\" rather than the \"what\"
- Ensure your language is clear, concise, and to the point
- Ensure the message accurately reflects the changes and their purpose (i.e. \"add\" means a wholly new feature, \" update \" means an enhancement to an existing feature, \"fix\" means a bug fix, etc.)
- Ensure the message is not generic (avoid words like \"Update\" or \"Fix\" without context)
- Review the draft message to ensure it accurately reflects the changes and their purpose
</commit_analysis>

3.:
   - Add relevant untracked files to the staging area.
   - Create the commit with a message ending with:
   🤖 Generated with [eca](https://eca.dev)

   Co-Authored-By: eca <noreply@eca.dev>
   - Run git status to make sure the commit succeeded.

4. If the commit fails due to pre-commit hook changes, retry the commit ONCE to include these automated changes. If it fails again, it usually means a pre-commit hook is preventing the commit. If the commit succeeds but you notice that files were modified by the pre-commit hook, you MUST amend your commit to include them.

Important notes:
- Use the git context at the start of this conversation to determine which files are relevant to your commit. Be careful not to stage and commit files (e.g. with `git add .`) that aren't relevant to your commit.
- NEVER update the git config
- DO NOT run additional commands to read or explore code, beyond what is available in the git context
- DO NOT push to the remote repository
- IMPORTANT: Never use git commands with the -i flag (like git rebase -i or git add -i) since they require interactive input which is not supported.
- If there are no changes to commit (i.e., no untracked files and no modifications), do not create an empty commit
- Ensure your commit message is meaningful and concise. It should explain the purpose of the changes, not just describe them.
- Return an empty response - the user will see the git output directly
- In order to ensure good formatting, ALWAYS pass the commit message via a HEREDOC, a la this example:
<example>
git commit -m \"$(cat <<'EOF'
   Commit message here.

   🤖 Generated with [opencode](https://opencode.ai)

   Co-Authored-By: opencode <noreply@opencode.ai>
   EOF
   )\"
</example>

# Creating pull requests
Use the gh command via the Bash tool for ALL GitHub-related tasks including working with issues, pull requests, checks, and releases. If given a Github URL use the gh command to get the information needed.

IMPORTANT: When the user asks you to create a pull request, follow these steps carefully:

1. In order to understand the current state of the branch since it diverged from the main branch:
   - Run a git status command to see all untracked files
   - Run a git diff command to see both staged and unstaged changes that will be committed
   - Check if the current branch tracks a remote branch and is up to date with the remote, so you know if you need to push to the remote
   - Run a git log command and `git diff main...HEAD` to understand the full commit history for the current branch (from the time it diverged from the `main` branch)

2. Analyze all changes that will be included in the pull request, making sure to look at all relevant commits (NOT just the latest commit, but ALL commits that will be included in the pull request!!!), and draft a pull request summary. Wrap your analysis process in <pr_analysis> tags:

<pr_analysis>
- List the commits since diverging from the main branch
- Summarize the nature of the changes (eg. new feature, enhancement to an existing feature, bug fix, refactoring, test, docs, etc.)
- Brainstorm the purpose or motivation behind these changes
- Assess the impact of these changes on the overall project
- Do not use tools to explore code, beyond what is available in the git context
- Check for any sensitive information that shouldn't be committed
- Draft a concise (1-2 bullet points) pull request summary that focuses on the \"why\" rather than the \"what\"
- Ensure the summary accurately reflects all changes since diverging from the main branch
- Ensure your language is clear, concise, and to the point
- Ensure the summary accurately reflects the changes and their purpose (ie. \"add\" means a wholly new feature, " update " means an enhancement to an existing feature, \"fix\" means a bug fix, etc.)
- Ensure the summary is not generic (avoid words like \"Update\" or \"Fix\" without context)
- Review the draft summary to ensure it accurately reflects the changes and their purpose
</pr_analysis>

3. ALWAYS run the following commands in parallel:
   - Create new branch if needed
   - Push to remote with -u flag if needed
   - Create PR using gh pr create with the format below. Use a HEREDOC to pass the body to ensure correct formatting.
<example>
gh pr create --title \"the pr title\" --body \"$(cat <<'EOF'
## Summary
<1-3 bullet points>

## Test plan
[Checklist of TODOs for testing the pull request...]

🤖 Generated with [opencode](https://opencode.ai)
EOF
)\"
</example>

Important:
- NEVER update the git config
- Return the PR URL when you're done, so the user can see it

# Other common operations
- View comments on a Github PR: gh api repos/foo/bar/pulls/123/comments
")
    :parameters {:type "object"
                 :properties {"command" {:type "string"
                                         :description "The shell command to execute."}
                              "working_directory" {:type "string"
                                                   :description "The directory to run the command in. Default to the first workspace root."}}
                 :required ["command"]}
    :handler #'shell-command
    :summary-fn #'shell-command-summary}})
