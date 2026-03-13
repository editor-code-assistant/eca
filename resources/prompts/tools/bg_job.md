Manage background jobs (long-running shell processes).

Actions:
- `list`: Show all background jobs with their ID, status, elapsed time, and command.
- `read_output`: Read new output from a background job since the last read. Requires `job_id`.
- `kill`: Terminate a running background job. Requires `job_id`.

Usage notes:
- Background jobs are started via `eca__shell_command` with `background: true`.
- `read_output` returns only **new** output since your last read, so call it periodically to monitor.
- Job IDs follow the pattern `job-1`, `job-2`, etc.
- Use `list` to discover running jobs if you don't remember the ID.
- All background jobs are automatically cleaned up when ECA exits.
