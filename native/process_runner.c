#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* One launcher owns one session and adopts orphaned grandchildren. */
static volatile sig_atomic_t stop_signal = 0;
static void request_stop(int signal_number) { stop_signal = signal_number; }

static long long monotonic_ms(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (long long) now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static void pause_ms(int milliseconds) {
    struct timespec duration = { milliseconds / 1000, (milliseconds % 1000) * 1000000L };
    while (nanosleep(&duration, &duration) < 0 && errno == EINTR) { }
}

/* Children are still owned and unreaped, so their PIDs cannot be recycled during cleanup. */
static int signal_adopted_children(int signal_number) {
    char path[128];
    snprintf(path, sizeof(path), "/proc/self/task/%d/children", getpid());
    FILE *children = fopen(path, "r");
    int count = 0;
    long child;
    if (children != NULL) {
        while (fscanf(children, "%ld", &child) == 1) {
            if (child > 1 && child != getpid()) {
                kill((pid_t) child, signal_number);
                count++;
            }
        }
        fclose(children);
        return count;
    }

    /* Some Android kernels omit the children file. Discover only our direct
     * children through procfs instead. This single-threaded launcher does not
     * reap during the scan: an owned child's PID cannot be recycled between
     * checking its parent and signalling it, even if the child exits. */
    DIR *processes = opendir("/proc");
    if (processes == NULL) return -1;
    struct dirent *entry;
    while ((entry = readdir(processes)) != NULL) {
        char *end;
        errno = 0;
        child = strtol(entry->d_name, &end, 10);
        if (errno || end == entry->d_name || *end || child <= 1 || child > INT_MAX || child == getpid()) continue;
        snprintf(path, sizeof(path), "/proc/%ld/stat", child);
        FILE *stat = fopen(path, "r");
        if (stat == NULL) continue;
        char buffer[4096];
        size_t length = fread(buffer, 1, sizeof(buffer) - 1, stat);
        fclose(stat);
        buffer[length] = '\0';
        /* A comm value can contain spaces, parentheses, or newlines. */
        char *comm_end = strrchr(buffer, ')');
        char state;
        long parent;
        if (comm_end != NULL && sscanf(comm_end + 1, " %c %ld", &state, &parent) == 2 && parent == getpid()) {
            kill((pid_t) child, signal_number);
            count++;
        }
    }
    closedir(processes);
    return count;
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "process-runner: missing command\n"); return 64; }
    const pid_t original_parent = getppid();
    struct sigaction action = {0};
    action.sa_handler = request_stop;
    sigemptyset(&action.sa_mask);
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGINT, &action, NULL);
    sigaction(SIGHUP, &action, NULL);
    signal(SIGCHLD, SIG_DFL);
    /* PDEATHSIG follows the thread that forked us, not the parent's entire
     * process. Android may start this launcher on a coroutine worker which
     * retires while the app is still running. Watch the parent process below
     * instead, so retiring a worker cannot stop healthy guest sessions. */
    if (setsid() < 0 || prctl(PR_SET_CHILD_SUBREAPER, 1) < 0 || prctl(PR_SET_PDEATHSIG, 0) < 0) {
        perror("process-runner: initialize"); return 70;
    }
    if (getppid() != original_parent) return 71;
    const pid_t runner_pid = getpid();
    const pid_t child = fork();
    if (child < 0) { perror("process-runner: fork"); return 71; }
    if (child == 0) {
        signal(SIGTERM, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        if (setpgid(0, 0) < 0 || prctl(PR_SET_PDEATHSIG, SIGKILL) < 0) _exit(72);
        if (getppid() != runner_pid) _exit(72);
        execvp(argv[1], &argv[1]);
        perror("process-runner: exec");
        _exit(127);
    }
    if (setpgid(child, child) < 0 && errno != EACCES && errno != ESRCH) {
        perror("process-runner: setpgid");
        kill(child, SIGKILL);
        waitpid(child, NULL, 0);
        return 72;
    }
    long long stop_started = 0;
    siginfo_t info = {0};
    while (1) {
        if (getppid() != original_parent && !stop_signal) stop_signal = SIGTERM;
        if (waitid(P_PID, (id_t) child, &info, WEXITED | WNOHANG | WNOWAIT) == 0 && info.si_pid == child) break;
        if (stop_signal && stop_started == 0) {
            kill(-child, SIGTERM);
            stop_started = monotonic_ms();
        }
        if (stop_started && monotonic_ms() - stop_started >= 4000) {
            kill(-child, SIGKILL);
            signal_adopted_children(SIGKILL);
        }
        pause_ms(25);
    }
    /* Keep the leader unreaped until group cleanup, preventing process-group ID reuse. */
    kill(-child, SIGTERM);
    signal_adopted_children(SIGTERM);
    pause_ms(100);
    kill(-child, SIGKILL);
    signal_adopted_children(SIGKILL);
    int status = 0;
    while (waitpid(child, &status, 0) < 0 && errno == EINTR) { }
    /* Killing an adopted parent may reveal another detached generation. */
    const long long cleanup_deadline = monotonic_ms() + 1500;
    do {
        signal_adopted_children(SIGKILL);
        while (waitpid(-1, NULL, WNOHANG) > 0) { }
        pause_ms(25);
    } while (signal_adopted_children(0) > 0 && monotonic_ms() < cleanup_deadline);
    if (stop_signal) return 128 + stop_signal;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 1;
}
