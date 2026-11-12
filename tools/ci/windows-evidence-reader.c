// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
#define WIN32_LEAN_AND_MEAN
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>

#define MAX_PATH_BYTES (256U * 1024U)
#define MAX_EVIDENCE_BYTES (512ULL * 1024ULL * 1024ULL)

static int fail(int status, const char *message) {
  fputs(message, stderr);
  fputc('\n', stderr);
  return status;
}

static wchar_t *input_path(void) {
  char *bytes = malloc(MAX_PATH_BYTES + 1U);
  if (bytes == NULL)
    return NULL;
  DWORD total = 0;
  for (;;) {
    DWORD count = 0;
    if (!ReadFile(GetStdHandle(STD_INPUT_HANDLE), bytes + total,
                  MAX_PATH_BYTES + 1U - total, &count, NULL)) {
      if (GetLastError() == ERROR_BROKEN_PIPE)
        break;
      free(bytes);
      return NULL;
    }
    if (count == 0)
      break;
    total += count;
    if (total > MAX_PATH_BYTES) {
      free(bytes);
      return NULL;
    }
  }
  int length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, bytes,
                                   (int)total, NULL, 0);
  if (length < 1 || length > 32767 || memchr(bytes, 0, total) != NULL) {
    free(bytes);
    return NULL;
  }
  wchar_t *path = calloc((size_t)length + 1U, sizeof(wchar_t));
  if (path != NULL && MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, bytes,
                                          (int)total, path, length) != length) {
    free(path);
    path = NULL;
  }
  free(bytes);
  return path;
}

static int write_bytes(const unsigned char *bytes, DWORD count) {
  while (count > 0) {
    DWORD written = 0;
    if (!WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), bytes, count, &written,
                   NULL) ||
        written == 0)
      return 0;
    bytes += written;
    count -= written;
  }
  return 1;
}

static int read_evidence(const wchar_t *path, unsigned long long limit) {
  HANDLE file =
      CreateFileW(path, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING,
                  FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS |
                      FILE_FLAG_SEQUENTIAL_SCAN,
                  NULL);
  if (file == INVALID_HANDLE_VALUE) {
    DWORD error = GetLastError();
    return error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND
               ? fail(6, "ENOENT: evidence does not exist")
               : fail(5, "Cannot safely open evidence");
  }
  BY_HANDLE_FILE_INFORMATION information;
  if (GetFileType(file) != FILE_TYPE_DISK ||
      !GetFileInformationByHandle(file, &information) ||
      (information.dwFileAttributes &
       (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT)) != 0) {
    CloseHandle(file);
    return fail(3, "Evidence must be a regular file without links");
  }
  LARGE_INTEGER size;
  if (!GetFileSizeEx(file, &size)) {
    CloseHandle(file);
    return fail(5, "Cannot inspect evidence size");
  }
  if (size.QuadPart < 0 || (unsigned long long)size.QuadPart > limit) {
    CloseHandle(file);
    return fail(4, "Evidence exceeds byte limit");
  }
  unsigned char chunk[64U * 1024U];
  unsigned long long total = 0;
  int status = 0;
  for (;;) {
    unsigned long long remaining = limit - total + 1ULL;
    DWORD requested =
        remaining < sizeof(chunk) ? (DWORD)remaining : sizeof(chunk);
    DWORD count = 0;
    if (!ReadFile(file, chunk, requested, &count, NULL)) {
      status = fail(5, "Cannot read evidence");
      break;
    }
    if (count == 0)
      break;
    total += count;
    if (total > limit) {
      status = fail(4, "Evidence exceeds byte limit");
      break;
    }
    if (!write_bytes(chunk, count)) {
      status = fail(7, "Cannot capture evidence bytes");
      break;
    }
  }
  CloseHandle(file);
  return status;
}

int main(int argc, char **argv) {
  if (argc != 2 || argv[1][0] == '\0')
    return fail(2, "Invalid evidence byte limit");
  char *end = NULL;
  errno = 0;
  unsigned long long limit = strtoull(argv[1], &end, 10);
  if (errno != 0 || *end != '\0' || limit < 1 || limit > MAX_EVIDENCE_BYTES) {
    return fail(2, "Invalid evidence byte limit");
  }
  wchar_t *path = input_path();
  if (path == NULL)
    return fail(2, "Invalid evidence path");
  int status = read_evidence(path, limit);
  free(path);
  return status;
}
