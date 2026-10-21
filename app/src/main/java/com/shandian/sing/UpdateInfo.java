package com.shandian.sing;

import java.io.IOException;

/** Restricts release metadata to this app's official, versioned HTTPS assets. */
final class UpdateInfo {
  static final String RELEASE =
      "https://github.com/shandianchengzi/sing/releases/download/preview/";
  final int code;
  final String name, sha256, url;
  final long size;

  UpdateInfo(int code, String name, String sha256, long size, String url) throws IOException {
    if (code <= 0
        || name.isEmpty()
        || name.length() > 64
        || !sha256.matches("[0-9a-f]{64}")
        || size < 1024
        || size > 20 * 1024 * 1024
        || !url.equals(RELEASE + "sing-" + code + "-" + sha256 + ".apk")) {
      throw new IOException("更新信息不完整或来源不正确");
    }
    this.code = code;
    this.name = name;
    this.sha256 = sha256;
    this.size = size;
    this.url = url;
  }

  boolean newerThan(long installed) {
    return code > installed;
  }
}
