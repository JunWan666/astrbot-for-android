#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

/* Private stdio protocol: type:u8, length:u32be, payload. 0=data, 1=resize,
 * 2=EOF. stdout is raw terminal data. Only the owning app writes these pipes. */
static volatile sig_atomic_t stopping = 0;
static void stop_requested(int value) { stopping = value; }
static int write_all(int fd, const void *bytes, size_t size) {
    const char *next = bytes;
    while (size) {
        ssize_t count = write(fd, next, size);
        if (count < 0 && errno == EINTR && !stopping) continue;
        if (count <= 0) return -1;
        next += count;
        size -= (size_t) count;
    }
    return 0;
}
static int dimension(const char *text) {
    char *end = NULL;
    long value = strtol(text, &end, 10);
    return end && !*end && value >= 1 && value <= 1000 ? (int) value : -1;
}

int main(int argc, char **argv) {
    if (argc < 4) return 64;
    int columns = dimension(argv[1]), rows = dimension(argv[2]);
    if (columns < 0 || rows < 0) return 64;
    struct sigaction action = {0};
    action.sa_handler = stop_requested;
    sigemptyset(&action.sa_mask);
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGINT, &action, NULL);
    sigaction(SIGHUP, &action, NULL);
    signal(SIGPIPE, SIG_IGN);
    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0 || grantpt(master) < 0 || unlockpt(master) < 0) {
        perror("terminal-runner: open PTY"); return 70;
    }
    char slave_name[128];
    if (ptsname_r(master, slave_name, sizeof(slave_name)) != 0) return 70;
    struct winsize size = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) columns };
    if (ioctl(master, TIOCSWINSZ, &size) < 0) return 70;
    pid_t parent = getpid();
    pid_t child = fork();
    if (child < 0) return 71;
    if (child == 0) {
        signal(SIGTERM, SIG_DFL); signal(SIGINT, SIG_DFL); signal(SIGHUP, SIG_DFL); signal(SIGPIPE, SIG_DFL);
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) < 0 || getppid() != parent || setsid() < 0) _exit(72);
        int slave = open(slave_name, O_RDWR);
        if (slave < 0 || ioctl(slave, TIOCSCTTY, 0) < 0) _exit(72);
        if (dup2(slave, STDIN_FILENO) < 0 || dup2(slave, STDOUT_FILENO) < 0 || dup2(slave, STDERR_FILENO) < 0) _exit(72);
        if (slave > STDERR_FILENO) close(slave);
        close(master);
        execvp(argv[3], &argv[3]);
        perror("terminal-runner: exec"); _exit(127);
    }
    unsigned char incoming[8197], output[8192];
    size_t used = 0, expected = 5;
    int input_closed = 0, failed = 0;
    while (!stopping) {
        struct pollfd fds[2] = {{ master, POLLIN, 0 }, { input_closed ? -1 : STDIN_FILENO, POLLIN, 0 }};
        int ready = poll(fds, 2, 500);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) { failed = 1; break; }
        if (fds[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t count = read(master, output, sizeof(output));
            if (count <= 0) break;
            if (write_all(STDOUT_FILENO, output, (size_t) count) < 0) break;
        }
        if (fds[1].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t count = read(STDIN_FILENO, incoming + used, expected - used);
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) {
                if (used) { failed = 1; break; }
                const char eof = 4;
                write_all(master, &eof, 1);
                input_closed = 1;
                continue;
            }
            used += (size_t) count;
            if (used == 5 && expected == 5) {
                uint32_t length = ((uint32_t) incoming[1] << 24) | ((uint32_t) incoming[2] << 16)
                    | ((uint32_t) incoming[3] << 8) | incoming[4];
                if (length > 8192 || incoming[0] > 2 || (incoming[0] == 1 && length != 4)
                    || (incoming[0] == 2 && length != 0)) { failed = 1; break; }
                expected = 5 + length;
            }
            if (used == expected) {
                if (incoming[0] == 0 && write_all(master, incoming + 5, used - 5) < 0) break;
                if (incoming[0] == 1) {
                    unsigned int cols = ((unsigned int) incoming[5] << 8) | incoming[6];
                    unsigned int lines = ((unsigned int) incoming[7] << 8) | incoming[8];
                    if (!cols || cols > 1000 || !lines || lines > 1000) { failed = 1; break; }
                    size.ws_col = (unsigned short) cols; size.ws_row = (unsigned short) lines;
                    if (ioctl(master, TIOCSWINSZ, &size) < 0) { failed = 1; break; }
                }
                if (incoming[0] == 2) {
                    const char eof = 4;
                    write_all(master, &eof, 1);
                    input_closed = 1;
                }
                used = 0; expected = 5;
            }
        }
        siginfo_t info = {0};
        if (waitid(P_PID, (id_t) child, &info, WEXITED | WNOHANG | WNOWAIT) == 0 && info.si_pid == child && ready == 0) break;
    }
    /* Child PID stays unreaped until after signalling, so a recycled PID cannot be targeted. */
    close(master);
    kill(-child, SIGKILL);
    kill(child, SIGKILL);
    int status = 0;
    while (waitpid(child, &status, 0) < 0 && errno == EINTR) { }
    if (stopping) return 128 + stopping;
    if (failed) return 74;
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}
