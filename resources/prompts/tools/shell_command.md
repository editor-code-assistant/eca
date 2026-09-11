Executes an arbitrary shell command ensuring proper handling and security measures.

Before executing the command, please follow these steps:

1. Directory Verification:
   - If the command will create new directories or files, first use the List tool to verify the parent directory exists and is the correct location
   - For example, before running "mkdir foo/bar", first use List to check that "foo" exists and is the intended parent directory

2. Command Execution:
  - Always quote file paths that contain spaces with double quotes (e.g., cd \" path with spaces/file.txt \")
  - Examples of proper quoting:
    - cd \"/Users/name/My Documents\" (correct)
    - cd /Users/name/My Documents (incorrect - will fail)
    - python \"/path/with spaces/script.py\" (correct)
    - python /path/with spaces/script.py (incorrect - will fail)
  - After ensuring proper quoting, execute the command.
  - Capture the output of the command.

Background execution:
  - Set `background` to a brief description for long-running commands that don't terminate on their own
    (e.g., `"dev-server"`, `"file-watcher"`, `"docker-compose"`).
  - Background commands return immediately with a job ID.
  - Use `eca__bg_job` with action `read_output` to check output, or `kill` to stop the process.

Usage notes:
  - The `command` argument is required.
  - It is very helpful if you write a clear, concise description of what this command does in 5-10 words.
  - When issuing multiple commands, use the ';' or '&&' operator to separate them. DO NOT use newlines (newlines are ok in quoted strings).
  - For search and reading, default to `eca__grep`, `eca__read_file`, and `eca__directory_tree` (bounded, line-numbered output) over shell `grep`, `cat`, `head`, `tail`, `ls`. If the user explicitly asks for the shell command (e.g. to pipe), run it as asked.
  - Commands start in the workspace root (or `working_directory`); `cd` does not persist between them, so prefer absolute paths.
    <good-example>
    pytest /foo/bar/tests
    </good-example>
    <bad-example>
    cd /foo/bar && pytest tests
    </bad-example>
