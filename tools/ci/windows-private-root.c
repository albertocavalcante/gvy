// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
#define _WIN32_WINNT 0x0602
#define WIN32_LEAN_AND_MEAN
#include <aclapi.h>
#include <sddl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include <windows.h>

#define MAX_PATH_BYTES (256U * 1024U)

static int fail(void) {
  fputs("Cannot establish a private Windows provisioning root\n", stderr);
  return 2;
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
  if (length < 1 || length > 32767 || memchr(bytes, 0, total) != NULL ||
      memchr(bytes, '\r', total) != NULL ||
      memchr(bytes, '\n', total) != NULL) {
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

static TOKEN_USER *current_user(void) {
  HANDLE token = NULL;
  if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token))
    return NULL;
  DWORD size = 0;
  GetTokenInformation(token, TokenUser, NULL, 0, &size);
  if (GetLastError() != ERROR_INSUFFICIENT_BUFFER || size == 0 ||
      size > 16384U) {
    CloseHandle(token);
    return NULL;
  }
  TOKEN_USER *user = malloc(size);
  if (user == NULL ||
      !GetTokenInformation(token, TokenUser, user, size, &size) ||
      !IsValidSid(user->User.Sid)) {
    free(user);
    user = NULL;
  }
  CloseHandle(token);
  return user;
}

static TOKEN_OWNER *current_owner(void) {
  HANDLE token = NULL;
  if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token))
    return NULL;
  DWORD size = 0;
  GetTokenInformation(token, TokenOwner, NULL, 0, &size);
  if (GetLastError() != ERROR_INSUFFICIENT_BUFFER || size == 0 ||
      size > 16384U) {
    CloseHandle(token);
    return NULL;
  }
  TOKEN_OWNER *owner = malloc(size);
  if (owner == NULL ||
      !GetTokenInformation(token, TokenOwner, owner, size, &size) ||
      !IsValidSid(owner->Owner)) {
    free(owner);
    owner = NULL;
  }
  CloseHandle(token);
  return owner;
}

static int create_private(const wchar_t *path, const wchar_t *sid) {
  wchar_t descriptor[512];
  int length = swprintf(descriptor, sizeof(descriptor) / sizeof(wchar_t),
                        L"O:%lsD:P(A;OICI;FA;;;%ls)", sid, sid);
  if (length < 1 || length >= (int)(sizeof(descriptor) / sizeof(wchar_t)))
    return 0;
  PSECURITY_DESCRIPTOR security = NULL;
  if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(
          descriptor, SDDL_REVISION_1, &security, NULL))
    return 0;
  SECURITY_ATTRIBUTES attributes = {sizeof(SECURITY_ATTRIBUTES), security,
                                    FALSE};
  BOOL created = CreateDirectoryW(path, &attributes);
  LocalFree(security);
  return created != 0;
}

enum object_kind { PRIVATE_ROOT, CHILD_DIRECTORY, CHILD_FILE };

static int inspect_object(const wchar_t *path, PSID user, PSID token_owner,
                          const wchar_t *user_sid, enum object_kind kind) {
  HANDLE object = CreateFileW(
      path, READ_CONTROL | FILE_READ_ATTRIBUTES,
      FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_EXISTING,
      FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS, NULL);
  if (object == INVALID_HANDLE_VALUE)
    return 0;
  BY_HANDLE_FILE_INFORMATION information;
  FILE_ID_INFO identity;
  PSECURITY_DESCRIPTOR security = NULL;
  PACL acl = NULL;
  PSID owner = NULL;
  int ok =
      GetFileType(object) == FILE_TYPE_DISK &&
      GetFileInformationByHandle(object, &information) &&
      GetFileInformationByHandleEx(object, FileIdInfo, &identity,
                                   sizeof(identity)) &&
      (information.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) == 0 &&
      (((information.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0) ==
       (kind != CHILD_FILE)) &&
      (kind != CHILD_FILE || information.nNumberOfLinks == 1) &&
      GetSecurityInfo(object, SE_FILE_OBJECT,
                      OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
                      &owner, NULL, &acl, NULL, &security) == ERROR_SUCCESS;
  SECURITY_DESCRIPTOR_CONTROL control = 0;
  DWORD revision = 0;
  ACL_SIZE_INFORMATION acl_information;
  ACCESS_ALLOWED_ACE *ace = NULL;
  int owner_is_user = 0;
  BYTE inheritance =
      kind == CHILD_FILE ? 0 : CONTAINER_INHERIT_ACE | OBJECT_INHERIT_ACE;
  if (ok) {
    owner_is_user = owner != NULL && IsValidSid(owner) && EqualSid(owner, user);
    int owner_allowed =
        owner_is_user ||
        (kind != PRIVATE_ROOT && owner != NULL && IsValidSid(owner) &&
         token_owner != NULL && EqualSid(owner, token_owner) &&
         IsWellKnownSid(token_owner, WinBuiltinAdministratorsSid));
    ok = owner_allowed &&
         GetSecurityDescriptorControl(security, &control, &revision) &&
         (kind != PRIVATE_ROOT || (control & SE_DACL_PROTECTED) != 0) &&
         acl != NULL && IsValidAcl(acl) &&
         GetAclInformation(acl, &acl_information, sizeof(acl_information),
                           AclSizeInformation) &&
         acl_information.AceCount == 1 && GetAce(acl, 0, (LPVOID *)&ace) &&
         ace->Header.AceType == ACCESS_ALLOWED_ACE_TYPE &&
         (ace->Header.AceFlags == inheritance ||
          (kind != PRIVATE_ROOT &&
           ace->Header.AceFlags == (inheritance | INHERITED_ACE))) &&
         ace->Mask == FILE_ALL_ACCESS && IsValidSid(&ace->SidStart) &&
         EqualSid(&ace->SidStart, user);
  }
  wchar_t *owner_sid = NULL;
  if (ok)
    ok = ConvertSidToStringSidW(owner, &owner_sid) != 0;
  if (ok) {
    char user_bytes[256];
    char owner_bytes[256];
    int user_length =
        WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, user_sid, -1,
                            user_bytes, sizeof(user_bytes), NULL, NULL);
    int owner_length =
        WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, owner_sid, -1,
                            owner_bytes, sizeof(owner_bytes), NULL, NULL);
    ok = user_length > 1 && owner_length > 1;
    if (ok) {
      char file_id[33];
      for (unsigned int index = 0; index < 16; ++index)
        snprintf(file_id + index * 2U, 3U, "%02x",
                 identity.FileId.Identifier[index]);
      if (kind == PRIVATE_ROOT) {
        ok = printf("{\"volumeSerial\":\"%016llx\",\"fileId\":\"%s\","
                    "\"ownerSid\":\"%s\"}\n",
                    (unsigned long long)identity.VolumeSerialNumber, file_id,
                    owner_bytes) > 0;
      } else {
        ok = printf("{\"volumeSerial\":\"%016llx\",\"fileId\":\"%s\","
                    "\"ownerSid\":\"%s\","
                    "\"principalSid\":\"%s\",\"ownerSource\":\"%s\",\"type\":"
                    "\"%s\","
                    "\"sizeBytes\":\"%08lx%08lx\",\"linkCount\":%lu}\n",
                    (unsigned long long)identity.VolumeSerialNumber, file_id,
                    owner_bytes, user_bytes,
                    owner_is_user ? "token-user" : "token-owner",
                    kind == CHILD_FILE ? "file" : "directory",
                    (unsigned long)information.nFileSizeHigh,
                    (unsigned long)information.nFileSizeLow,
                    (unsigned long)information.nNumberOfLinks) > 0;
      }
      if (ok)
        ok = fflush(stdout) == 0;
    }
  }
  if (owner_sid != NULL)
    LocalFree(owner_sid);
  if (security != NULL)
    LocalFree(security);
  CloseHandle(object);
  return ok;
}

int main(int argc, char **argv) {
  if (argc != 2)
    return fail();
  enum object_kind kind;
  int create = strcmp(argv[1], "create") == 0;
  if (create || strcmp(argv[1], "verify") == 0)
    kind = PRIVATE_ROOT;
  else if (strcmp(argv[1], "inspect-child-directory") == 0)
    kind = CHILD_DIRECTORY;
  else if (strcmp(argv[1], "inspect-child-file") == 0)
    kind = CHILD_FILE;
  else
    return fail();
  wchar_t *path = input_path();
  TOKEN_USER *user = current_user();
  TOKEN_OWNER *token_owner = kind == PRIVATE_ROOT ? NULL : current_owner();
  wchar_t *sid = NULL;
  int ok = path != NULL && user != NULL &&
           ConvertSidToStringSidW(user->User.Sid, &sid) &&
           (kind == PRIVATE_ROOT || token_owner != NULL);
  if (ok && create)
    ok = create_private(path, sid);
  if (ok)
    ok = inspect_object(path, user->User.Sid,
                        token_owner == NULL ? NULL : token_owner->Owner, sid,
                        kind);
  if (sid != NULL)
    LocalFree(sid);
  free(token_owner);
  free(user);
  free(path);
  return ok ? 0 : fail();
}
