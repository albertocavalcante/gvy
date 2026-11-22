// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/audit.h>
#include <linux/capability.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <unistd.h>

#if !defined(__linux__) || !defined(__x86_64__) || defined(__ILP32__) ||       \
    __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error "Qualification launcher supports only Linux x86_64 LP64 little-endian"
#endif
_Static_assert(sizeof(void *) == 8 && sizeof(long) == 8, "LP64 required");
_Static_assert(_LINUX_CAPABILITY_U32S_3 == 2, "64-bit capability ABI required");
_Static_assert(sizeof(struct sock_filter) == 8, "Unexpected BPF encoding");
_Static_assert(offsetof(struct seccomp_data, nr) == 0 &&
                   offsetof(struct seccomp_data, arch) == 4,
               "Unexpected seccomp data layout");
_Static_assert(__NR_io_uring_setup == 425 && __NR_io_uring_enter == 426 &&
                   __NR_io_uring_register == 427 && ENOSYS == 38,
               "Unexpected io_uring syscall ABI");

static _Noreturn void fail(const char *message) {
  fprintf(stderr, "qualification launcher: %s (errno=%d)\n", message, errno);
  exit(125);
}

struct capability_snapshot {
  unsigned long long effective, permitted, inheritable, ambient;
};

static struct capability_snapshot read_capabilities(void) {
  struct __user_cap_header_struct header = {
      .version = _LINUX_CAPABILITY_VERSION_3, .pid = 0};
  struct __user_cap_data_struct data[2] = {{0}, {0}};
  if (syscall(__NR_capget, &header, data) != 0)
    fail("Cannot inspect capability sets");
  struct capability_snapshot result = {
      .effective =
          ((unsigned long long)data[1].effective << 32) | data[0].effective,
      .permitted =
          ((unsigned long long)data[1].permitted << 32) | data[0].permitted,
      .inheritable =
          ((unsigned long long)data[1].inheritable << 32) | data[0].inheritable,
      .ambient = 0};
  for (unsigned int capability = 0; capability < 64; capability++) {
    int present = prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_IS_SET,
                        (unsigned long)capability, 0L, 0L);
    if (present < 0 && errno != EINVAL)
      fail("Cannot inspect ambient capabilities");
    if (present > 0)
      result.ambient |= 1ULL << capability;
  }
  return result;
}

static void drop_capabilities(void) {
  if (prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_CLEAR_ALL, 0L, 0L, 0L) != 0)
    fail("Cannot clear ambient capabilities");
  struct __user_cap_header_struct header = {
      .version = _LINUX_CAPABILITY_VERSION_3, .pid = 0};
  struct __user_cap_data_struct empty[2] = {{0}, {0}};
  if (syscall(__NR_capset, &header, empty) != 0)
    fail("Cannot clear effective/permitted/inheritable capabilities");
  struct capability_snapshot actual = read_capabilities();
  if (actual.effective || actual.permitted || actual.inheritable ||
      actual.ambient)
    fail("Capabilities remain after dropping them");
}

static void require_host(void) {
  struct utsname host;
  if (getuid() == 0 || getuid() != geteuid())
    fail("Expected unchanged non-root uid");
  if (uname(&host) != 0 || strcmp(host.machine, "x86_64") != 0)
    fail("Unsupported host ABI");
}

/* Values derive from kernel UAPI; the input must match every byte of this
 * profile. */
static struct sock_filter expected[] = {
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_X86_64, 1, 0),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
    BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K, 0x40000000, 0, 1),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_io_uring_setup, 3, 0),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_io_uring_enter, 2, 0),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_io_uring_register, 1, 0),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ENOSYS),
};

static void validate_profile(const char *path) {
  if (path[0] != '/')
    fail("Profile path must be absolute");
  int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
  struct stat status;
  if (fd < 0 || fstat(fd, &status) != 0 || !S_ISREG(status.st_mode) ||
      status.st_size != (off_t)sizeof(expected))
    fail("Invalid bounded profile file");
  unsigned char bytes[sizeof(expected) + 1];
  size_t count = 0;
  while (count < sizeof(bytes)) {
    ssize_t received = read(fd, bytes + count, sizeof(bytes) - count);
    if (received < 0 && errno == EINTR)
      continue;
    if (received < 0)
      fail("Profile read failed");
    if (received == 0)
      break;
    count += (size_t)received;
  }
  if (close(fd) != 0 || count != sizeof(expected) ||
      memcmp(bytes, expected, sizeof(expected)) != 0)
    fail("Profile differs from fixed reviewed denial program");
}

static void close_inherited_descriptors(void) {
  /* exec removed previous mappings. Do not carry a live ring through stdio or
   * fd>=3. */
  for (int fd = 0; fd <= 2; fd++) {
    char path[32];
    char target[PATH_MAX];
    struct stat status;
    if (snprintf(path, sizeof(path), "/proc/self/fd/%d", fd) < 0)
      fail("Descriptor path failed");
    ssize_t length = readlink(path, target, sizeof(target) - 1);
    if (length < 0 || length == (ssize_t)sizeof(target) - 1 ||
        fstat(fd, &status) != 0)
      fail("Cannot establish stdio descriptor identity");
    target[length] = '\0';
    if (strstr(target, "io_uring") != NULL ||
        !(S_ISREG(status.st_mode) || S_ISCHR(status.st_mode) ||
          S_ISFIFO(status.st_mode) || S_ISSOCK(status.st_mode)))
      fail("Unsupported inherited stdio descriptor");
  }
  /* Require a kernel with close_range. Never silently keep unknown inherited
   * descriptors. */
  if (syscall(__NR_close_range, 3U, UINT_MAX, 0U) != 0)
    fail("Could not close inherited descriptors");
}

static void canary(const char *path) {
  if (path[0] != '/')
    fail("Canary output path must be absolute");
  DIR *descriptors = opendir("/proc/self/fd");
  if (descriptors == NULL)
    fail("Cannot inspect inherited descriptors");
  int extra_descriptors = 0;
  unsigned int entries = 0;
  struct dirent *entry;
  errno = 0;
  while ((entry = readdir(descriptors)) != NULL) {
    if (++entries > 4096)
      fail("Descriptor inventory bound exceeded");
    char *end;
    long number = strtol(entry->d_name, &end, 10);
    if (*end == '\0' && number > 2 && number != dirfd(descriptors))
      extra_descriptors++;
  }
  if (errno != 0 || closedir(descriptors) != 0)
    fail("Descriptor inventory failed");
  long results[3];
  int errors[3];
  errno = 0;
  results[0] = syscall(__NR_io_uring_setup, 0U, NULL);
  errors[0] = errno;
  errno = 0;
  results[1] = syscall(__NR_io_uring_enter, -1, 0U, 0U, 0U, NULL, 0U);
  errors[1] = errno;
  errno = 0;
  results[2] = syscall(__NR_io_uring_register, -1, 0U, NULL, 0U);
  errors[2] = errno;
  if (results[0] >= 0) {
    close((int)results[0]);
    fail("Non-allocating canary unexpectedly created an io_uring");
  }
  int nnp = prctl(PR_GET_NO_NEW_PRIVS, 0L, 0L, 0L, 0L);
  int mode = prctl(PR_GET_SECCOMP, 0L, 0L, 0L, 0L);
  if (nnp < 0 || mode < 0)
    fail("Cannot read inherited seccomp state");
  struct capability_snapshot capabilities = read_capabilities();
  char temporary[PATH_MAX];
  int length = snprintf(temporary, sizeof(temporary), "%s.partial", path);
  if (length < 0 || length >= (int)sizeof(temporary))
    fail("Canary output path too long");
  int fd = open(temporary, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
  if (fd < 0)
    fail("Canary output creation failed");
  FILE *output = fdopen(fd, "w");
  if (output == NULL)
    fail("Canary output stream failed");
  const char *names[] = {"io_uring_setup", "io_uring_enter",
                         "io_uring_register"};
  if (fprintf(
          output,
          "{\"uid\":%u,\"noNewPrivs\":%d,\"seccomp\":%d,"
          "\"forked\":true,\"inheritedExtraDescriptors\":%d,"
          "\"capabilities\":{\"effective\":\"%016llx\",\"permitted\":\"%"
          "016llx\","
          "\"inheritable\":\"%016llx\",\"ambient\":\"%016llx\"},\"calls\":[",
          (unsigned)getuid(), nnp, mode, extra_descriptors,
          capabilities.effective, capabilities.permitted,
          capabilities.inheritable, capabilities.ambient) < 0)
    fail("Canary output failed");
  for (int index = 0; index < 3; index++) {
    if (fprintf(output, "%s{\"name\":\"%s\",\"result\":%ld,\"errno\":%d}",
                index ? "," : "", names[index], results[index],
                errors[index]) < 0)
      fail("Canary output failed");
  }
  if (fprintf(output, "]}\n") < 0 || fclose(output) != 0)
    fail("Canary output close failed");
  if (link(temporary, path) != 0 || unlink(temporary) != 0)
    fail("Canary atomic publication failed");
}

int main(int argc, char **argv) {
  require_host();
  if (argc == 3 && strcmp(argv[1], "--canary") == 0) {
    pid_t child = fork();
    if (child < 0)
      fail("Canary fork failed");
    if (child == 0) {
      canary(argv[2]);
      _exit(0);
    }
    int status;
    pid_t waited;
    do {
      waited = waitpid(child, &status, 0);
    } while (waited < 0 && errno == EINTR);
    if (waited != child || !WIFEXITED(status) || WEXITSTATUS(status) != 0)
      fail("Forked canary failed");
    return 0;
  }
  if (argc < 5 || strcmp(argv[1], "--profile") != 0 ||
      strcmp(argv[3], "--") != 0 || argv[4][0] != '/')
    fail("Usage: launcher --profile /file -- /command [args], or --canary "
         "/output");
  validate_profile(argv[2]);
  close_inherited_descriptors();
  if (prctl(PR_SET_NO_NEW_PRIVS, 1L, 0L, 0L, 0L) != 0)
    fail("no_new_privs failed");
  drop_capabilities();
  struct sock_fprog program = {.len = sizeof(expected) / sizeof(expected[0]),
                               .filter = expected};
  if (prctl(PR_SET_SECCOMP, (long)SECCOMP_MODE_FILTER, &program, 0L, 0L) != 0)
    fail("seccomp filter installation failed");
  execv(argv[4], &argv[4]);
  fail("Candidate exec failed");
}
