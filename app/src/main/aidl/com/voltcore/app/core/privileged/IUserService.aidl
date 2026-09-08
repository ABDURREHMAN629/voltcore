package com.voltcore.app.core.privileged;

/**
 * Runs inside a shell-UID process spawned by Shizuku.
 *
 * The transaction id on destroy() is fixed by Shizuku's UserService contract — the server
 * calls that specific code to tear the process down, so it must not be renumbered.
 */
interface IUserService {
    void destroy() = 16777114;

    /** Runs a command and returns its combined stdout/stderr. */
    String execute(in String[] command) = 1;

    /** Runs a command and returns only its exit code. */
    int executeForExitCode(in String[] command) = 2;
}
