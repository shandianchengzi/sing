package com.shandian.sing;

public class UpdateTest {
  static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  static void check(boolean value) {
    if (!value) throw new AssertionError();
  }

  static void reject(int code, String hash, long size, String url) throws Exception {
    try {
      new UpdateInfo(code, "test", hash, size, url);
      throw new AssertionError("Unsafe update accepted");
    } catch (java.io.IOException expected) {
    }
  }

  public static void main(String[] args) throws Exception {
    String url = UpdateInfo.RELEASE + "sing-1010-" + HASH + ".apk";
    UpdateInfo info = new UpdateInfo(1010, "1.6.10", HASH, 45000, url);
    check(info.newerThan(1009));
    check(!info.newerThan(1010));
    check(!info.newerThan(1011));
    reject(1010, HASH, 45000, url.replace("https:", "http:"));
    reject(1010, HASH, 45000, url.replace("shandianchengzi/sing", "other/app"));
    reject(1010, HASH, 45000, url + "?redirect=evil");
    reject(1010, HASH, 45000, UpdateInfo.RELEASE + "sing-1011-" + HASH + ".apk");
    reject(0, HASH, 45000, UpdateInfo.RELEASE + "sing-0-" + HASH + ".apk");
    reject(1010, "wrong", 45000, url);
    reject(1010, HASH, 21 * 1024 * 1024, url);
    reject(1010, HASH, 0, url);
    System.out.println("Update version, official URL, checksum and size policy tests passed");
  }
}
